package cn.net.rms.confluxmap.server.web;

import cn.net.rms.confluxmap.core.net.MapRegionSyncSubscribeC2S;
import cn.net.rms.confluxmap.core.net.MapViewReqC2S;
import cn.net.rms.confluxmap.core.net.Message;
import cn.net.rms.confluxmap.core.net.MsgCodec;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.net.ProtoException;
import cn.net.rms.confluxmap.server.PlayerBudget;
import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoWSD;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPOutputStream;

/** Same-port HTTP asset server and binary WebSocket transport for the browser map. */
public final class WebMapServer implements AutoCloseable {
    private static final int SOCKET_TIMEOUT_MS = 60_000;

    private final Transport transport;

    private WebMapServer(final Transport transport) {
        this.transport = transport;
    }

    public static WebMapServer start(
        final WebMapConfig requestedConfig,
        final WebMapBackend backend
    ) throws IOException {
        if (requestedConfig == null || backend == null) {
            throw new IllegalArgumentException("web map config and backend are required");
        }
        requestedConfig.normalize();
        if (!requestedConfig.loopbackOnly() && !requestedConfig.allowInsecureRemote) {
            throw new IOException("remote web-map bind requires allowInsecureRemote=true");
        }
        final Transport transport = new Transport(requestedConfig, backend);
        transport.start(SOCKET_TIMEOUT_MS, false);
        return new WebMapServer(transport);
    }

    public URI uri(final String path) {
        return URI.create("http://127.0.0.1:" + transport.getListeningPort() + path);
    }

    @Override
    public void close() {
        transport.closeTransport();
    }

    private record StaticAsset(byte[] body, String contentType, String etag) {
    }

    private static final class AddressBudget {
        final PlayerBudget budget;
        volatile long lastSeen;

        AddressBudget(final WebMapConfig config, final long now) {
            budget = new PlayerBudget(
                config.maxBytesPerSecondPerAddress, config.minRequestIntervalMs
            );
            lastSeen = now;
        }
    }

    private static final class Transport extends NanoWSD {
        private final WebMapBackend backend;
        private final WebMapConfig config;
        private final Semaphore mapConnections;
        private final ConcurrentHashMap<UUID, MapSocket> sockets = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, AddressBudget> addressBudgets = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, StaticAsset> assets = new ConcurrentHashMap<>();
        private final ScheduledExecutorService eventWorker;

        Transport(final WebMapConfig config, final WebMapBackend backend) {
            super(config.bindAddress, config.port);
            this.backend = backend;
            this.config = config;
            mapConnections = new Semaphore(config.maxConnections);
            eventWorker = Executors.newSingleThreadScheduledExecutor(runnable -> {
                final Thread thread = new Thread(runnable, "ConfluxMap-web-events");
                thread.setDaemon(true);
                return thread;
            });
            eventWorker.scheduleAtFixedRate(this::publishPlayers, 0L, 2L, TimeUnit.SECONDS);
        }

        @Override
        protected boolean isWebsocketRequested(final IHTTPSession session) {
            return "/api/v1/map".equals(session.getUri()) && super.isWebsocketRequested(session);
        }

        @Override
        protected WebSocket openWebSocket(final IHTTPSession handshake) {
            return new MapSocket(handshake);
        }

        @Override
        protected Response serveHttp(final IHTTPSession session) {
            final String path = session.getUri();
            if ("/api/v1/manifest".equals(path)) {
                if (session.getMethod() != Method.GET) return methodNotAllowed("GET");
                return secure(text(
                    Response.Status.OK,
                    "application/json; charset=utf-8",
                    backend.manifest().toJson()
                ));
            }
            if ("/api/v1/regions".equals(path) || "/api/v1/events".equals(path)) {
                return secure(text(
                    Response.Status.GONE,
                    NanoHTTPD.MIME_PLAINTEXT,
                    "use /api/v1/map websocket"
                ));
            }
            if (path.startsWith("/api/v1/avatars/")) {
                return avatar(session, path);
            }
            return staticAsset(session, path);
        }

        private Response avatar(final IHTTPSession session, final String path) {
            if (session.getMethod() != Method.GET) return methodNotAllowed("GET");
            final String id = path.substring("/api/v1/avatars/".length());
            final UUID playerId;
            try {
                playerId = UUID.fromString(id.endsWith(".png")
                    ? id.substring(0, id.length() - 4) : id);
            } catch (final IllegalArgumentException e) {
                return secure(text(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "avatar not found"));
            }
            try {
                final byte[] body = backend.avatar(playerId).get(10L, TimeUnit.SECONDS);
                if (body == null || body.length == 0) {
                    return secure(text(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "avatar not found"));
                }
                final Response response = bytes(Response.Status.OK, "image/png", body);
                response.addHeader("Cache-Control", "public, max-age=3600");
                return secure(response);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return secure(text(
                    Response.Status.SERVICE_UNAVAILABLE,
                    NanoHTTPD.MIME_PLAINTEXT,
                    "avatar request interrupted"
                ));
            } catch (final Exception e) {
                return secure(text(
                    Response.Status.SERVICE_UNAVAILABLE,
                    NanoHTTPD.MIME_PLAINTEXT,
                    "avatar unavailable"
                ));
            }
        }

        private Response staticAsset(final IHTTPSession session, final String requestedPath) {
            if (session.getMethod() != Method.GET) return methodNotAllowed("GET");
            final String path = "/".equals(requestedPath) ? "/index.html" : requestedPath;
            if (!path.matches("/[A-Za-z0-9._/-]+") || path.contains("..")) {
                return secure(text(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "not found"));
            }
            final boolean gzip = path.endsWith(".wasm")
                && acceptsGzip(session);
            final String assetKey = gzip ? path + "|gzip" : path;
            StaticAsset asset = assets.get(assetKey);
            if (asset == null) {
                try {
                    asset = loadAsset(path, gzip);
                } catch (final IOException e) {
                    return secure(text(
                        Response.Status.INTERNAL_ERROR,
                        NanoHTTPD.MIME_PLAINTEXT,
                        "asset unavailable"
                    ));
                }
                if (asset == null) {
                    return secure(text(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "not found"));
                }
                final StaticAsset existing = assets.putIfAbsent(assetKey, asset);
                if (existing != null) asset = existing;
            }
            if (asset.etag().equals(header(session, "if-none-match"))) {
                final Response response = text(Response.Status.NOT_MODIFIED, asset.contentType(), "");
                response.addHeader("ETag", asset.etag());
                return secure(response);
            }
            final Response response = bytes(Response.Status.OK, asset.contentType(), asset.body());
            response.addHeader("Cache-Control", path.startsWith("/vendor/")
                ? "public, max-age=31536000, immutable" : "no-cache");
            response.addHeader("ETag", asset.etag());
            if (gzip) response.addHeader("Content-Encoding", "gzip");
            response.addHeader("Vary", "Accept-Encoding");
            response.addHeader(
                "Content-Security-Policy",
                "default-src 'self'; img-src 'self' data:; style-src 'self'; "
                    + "script-src 'self' 'wasm-unsafe-eval'; worker-src 'self'; connect-src 'self'"
            );
            return secure(response);
        }

        private Response methodNotAllowed(final String allowed) {
            final Response response = text(
                Response.Status.METHOD_NOT_ALLOWED,
                NanoHTTPD.MIME_PLAINTEXT,
                "method not allowed"
            );
            response.addHeader("Allow", allowed);
            return secure(response);
        }

        private static Response secure(final Response response) {
            response.addHeader("X-Content-Type-Options", "nosniff");
            response.addHeader("Referrer-Policy", "no-referrer");
            return response;
        }

        private static Response text(
            final Response.IStatus status,
            final String contentType,
            final String body
        ) {
            return NanoHTTPD.newFixedLengthResponse(status, contentType, body);
        }

        private static Response bytes(
            final Response.IStatus status,
            final String contentType,
            final byte[] body
        ) {
            return NanoHTTPD.newFixedLengthResponse(
                status, contentType, new ByteArrayInputStream(body), body.length
            );
        }

        private void publishPlayers() {
            final WebMapSnapshot snapshot = backend.snapshot();
            for (final MapSocket socket : sockets.values()) {
                socket.sendMapState(snapshot);
            }
        }

        private AddressBudget addressBudget(final String address) {
            evictAddressIfFull(address);
            final long now = System.nanoTime();
            final AddressBudget result = addressBudgets.computeIfAbsent(
                address, ignored -> new AddressBudget(config, now)
            );
            result.lastSeen = now;
            return result;
        }

        private void evictAddressIfFull(final String incoming) {
            if (addressBudgets.containsKey(incoming)
                || addressBudgets.size() < config.maxConnections * 16) return;
            String oldest = null;
            long oldestSeen = Long.MAX_VALUE;
            for (final var entry : addressBudgets.entrySet()) {
                if (entry.getValue().lastSeen < oldestSeen) {
                    oldest = entry.getKey();
                    oldestSeen = entry.getValue().lastSeen;
                }
            }
            if (oldest != null) addressBudgets.remove(oldest);
        }

        private void closeTransport() {
            eventWorker.shutdownNow();
            for (final MapSocket socket : sockets.values()) {
                socket.closeQuietly(WebSocketFrame.CloseCode.GoingAway, "server stopping");
            }
            stop();
        }

        private final class MapSocket extends WebSocket {
            private final UUID clientId = UUID.randomUUID();
            private final AddressBudget budget;
            private final AtomicBoolean registered = new AtomicBoolean();
            private volatile long playerRevision = Long.MIN_VALUE;

            MapSocket(final IHTTPSession handshakeRequest) {
                super(handshakeRequest);
                budget = addressBudget(clientAddress(handshakeRequest));
            }

            @Override
            protected void onOpen() {
                if (!mapConnections.tryAcquire()) {
                    closeQuietly(WebSocketFrame.CloseCode.PolicyViolation, "too many map connections");
                    return;
                }
                registered.set(true);
                sockets.put(clientId, this);
                sendMapState(backend.snapshot());
            }

            @Override
            protected void onClose(
                final WebSocketFrame.CloseCode code,
                final String reason,
                final boolean initiatedByRemote
            ) {
                cleanup();
            }

            @Override
            protected void onMessage(final WebSocketFrame frame) {
                if (frame.getOpCode() == WebSocketFrame.OpCode.Text) {
                    if ("ping".equals(frame.getTextPayload())) {
                        try {
                            send("pong");
                        } catch (final IOException e) {
                            closeQuietly(WebSocketFrame.CloseCode.GoingAway, "write failed");
                        }
                    }
                    return;
                }
                if (frame.getOpCode() != WebSocketFrame.OpCode.Binary) {
                    closeQuietly(WebSocketFrame.CloseCode.UnsupportedData, "binary messages required");
                    return;
                }
                final byte[] payload = frame.getBinaryPayload();
                if (payload.length == 0 || payload.length > Proto.MAX_C2S_PAYLOAD) {
                    closeQuietly(WebSocketFrame.CloseCode.MessageTooBig, "invalid map message size");
                    return;
                }
                final long now = System.nanoTime();
                budget.lastSeen = now;
                if (!budget.budget.beginRequest(now)) {
                    closeQuietly(WebSocketFrame.CloseCode.PolicyViolation, "map request is rate limited");
                    return;
                }
                final Message decoded;
                try {
                    decoded = MsgCodec.decode(payload);
                } catch (final ProtoException e) {
                    closeQuietly(WebSocketFrame.CloseCode.InvalidFramePayloadData, "invalid map message");
                    return;
                }
                if (decoded instanceof final MapViewReqC2S request) {
                    backend.requestTiles(clientId, request, payload.length, this::sendPayload);
                } else if (decoded instanceof final MapRegionSyncSubscribeC2S subscription) {
                    backend.subscribeRegions(clientId, subscription, this::sendPayload);
                } else {
                    closeQuietly(WebSocketFrame.CloseCode.UnsupportedData, "unsupported map message");
                }
            }

            @Override
            protected void onPong(final WebSocketFrame frame) {
            }

            @Override
            protected void onException(final IOException exception) {
                cleanup();
            }

            private void sendPayload(final byte[] payload) {
                if (!isOpen() || payload == null || payload.length == 0
                    || payload.length > Proto.MAX_S2C_PAYLOAD) return;
                if (!budget.budget.allowBytes(payload.length, System.nanoTime())) {
                    closeQuietly(WebSocketFrame.CloseCode.PolicyViolation, "map bandwidth exceeded");
                    return;
                }
                try {
                    send(payload);
                } catch (final IOException e) {
                    closeQuietly(WebSocketFrame.CloseCode.GoingAway, "write failed");
                }
            }

            private void sendMapState(final WebMapSnapshot snapshot) {
                if (!isOpen() || snapshot.revision() == playerRevision) return;
                try {
                    send("{\"type\":\"map-state\",\"snapshot\":" + snapshot.toJson() + "}");
                    playerRevision = snapshot.revision();
                } catch (final IOException e) {
                    closeQuietly(WebSocketFrame.CloseCode.GoingAway, "write failed");
                }
            }

            private void cleanup() {
                if (!registered.compareAndSet(true, false)) return;
                sockets.remove(clientId, this);
                mapConnections.release();
                backend.removeClient(clientId);
            }

            private void closeQuietly(
                final WebSocketFrame.CloseCode code,
                final String reason
            ) {
                try {
                    close(code, reason, false);
                } catch (final IOException ignored) {
                    cleanup();
                }
            }
        }
    }

    private static String header(final NanoHTTPD.IHTTPSession session, final String name) {
        return session.getHeaders().get(name.toLowerCase(java.util.Locale.ROOT));
    }

    private static String clientAddress(final NanoHTTPD.IHTTPSession session) {
        final String direct = session.getRemoteIpAddress();
        if (!loopback(direct)) return direct;
        final String forwarded = header(session, "x-forwarded-for");
        if (forwarded == null) return direct;
        final String first = forwarded.split(",", 2)[0].trim();
        return first.matches("[0-9A-Fa-f:.]{3,45}") ? first : direct;
    }

    private static boolean loopback(final String address) {
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (final UnknownHostException e) {
            return false;
        }
    }

    private static boolean acceptsGzip(final NanoHTTPD.IHTTPSession session) {
        final String accepted = header(session, "accept-encoding");
        return accepted != null && java.util.Arrays.stream(accepted.split(","))
            .map(String::trim)
            .anyMatch(value -> value.equals("gzip") || value.startsWith("gzip;"));
    }

    private static StaticAsset loadAsset(final String path, final boolean gzip) throws IOException {
        try (InputStream input = WebMapServer.class.getResourceAsStream("/webmap" + path)) {
            if (input == null) return null;
            byte[] body = input.readAllBytes();
            if (gzip) {
                final ByteArrayOutputStream compressed = new ByteArrayOutputStream(body.length / 2);
                try (GZIPOutputStream output = new GZIPOutputStream(compressed)) {
                    output.write(body);
                }
                body = compressed.toByteArray();
            }
            return new StaticAsset(body, contentType(path), etag(body));
        }
    }

    private static String etag(final byte[] body) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256").digest(body);
            final StringBuilder hex = new StringBuilder(18).append('"');
            for (int i = 0; i < 8; i++) hex.append(String.format("%02x", digest[i]));
            return hex.append('"').toString();
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String contentType(final String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (path.endsWith(".json")) return "application/json; charset=utf-8";
        if (path.endsWith(".wasm")) return "application/wasm";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }
}
