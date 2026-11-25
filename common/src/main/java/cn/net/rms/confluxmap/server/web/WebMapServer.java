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
import java.nio.file.Path;
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
    private WebMapGateway gateway;
    private URI publicUri;

    private WebMapServer(final Transport transport) {
        this.transport = transport;
    }

    public static WebMapServer start(
        final WebMapConfig requestedConfig,
        final WebMapBackend backend
    ) throws IOException {
        return start(requestedConfig, backend, null);
    }

    public static WebMapServer start(
        final WebMapConfig requestedConfig,
        final WebMapBackend backend,
        final Path configDirectory
    ) throws IOException {
        if (requestedConfig == null || backend == null) {
            throw new IllegalArgumentException("web map config and backend are required");
        }
        requestedConfig.normalize();
        if (!requestedConfig.loopbackOnly() && !requestedConfig.allowInsecureRemote) {
            throw new IOException("remote web-map bind requires allowInsecureRemote=true");
        }
        final Transport transport = new Transport(requestedConfig, backend, configDirectory);
        try { transport.start(SOCKET_TIMEOUT_MS, false); }
        catch (IOException | RuntimeException e) { transport.closeTransport(); WebMapFeatures f=backend.features();if(f!=null)f.close();throw e; }
        return new WebMapServer(transport);
    }

    public static WebMapServer startShared(WebMapConfig config, WebMapBackend backend, Path directory) throws IOException {
        config.normalize();
        if (!config.sharedGateway || config.port == 0) return start(config, backend, directory);
        if (!config.loopbackOnly() && !config.allowInsecureRemote) throw new IOException("remote web-map bind requires allowInsecureRemote=true");
        WebMapConfig internal = WebMapConfig.loopbackEphemeral();
        byte[] secret=new byte[32];new java.security.SecureRandom().nextBytes(secret);
        internal.gatewayToken=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        internal.title=config.title; internal.favicon=config.favicon;
        internal.maxConnections=config.maxConnections; internal.maxBytesPerSecondPerAddress=config.maxBytesPerSecondPerAddress;
        internal.minRequestIntervalMs=config.minRequestIntervalMs;
        WebMapServer local=start(internal, backend, directory);
        try {
            local.gateway=new WebMapGateway(config, UUID.fromString(backend.manifest().presentation().serverInstanceId()),
                local.transport.getListeningPort(), internal.gatewayToken,
                () -> backend.manifest().presentation().serverDisplayName(), backend::defaultGameMode);
            local.publicUri=URI.create("http://"+displayHost(config.bindAddress)+":"+config.port+"/");
            return local;
        } catch(IOException | RuntimeException e) {local.close();throw e;}
    }

    public URI uri(final String path) {
        if (publicUri != null) return publicUri.resolve(path);
        return URI.create("http://" + displayHost(transport.config.bindAddress)
            + ":" + transport.getListeningPort() + path);
    }

    @Override
    public void close() {
        if (gateway != null) gateway.close();
        transport.closeTransport();
        WebMapFeatures features=transport.backend.features();
        if(features!=null) features.close();
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
        private final String title;
        private final WebMapBranding.Icon favicon;
        private final ConcurrentHashMap<UUID, MapSocket> sockets = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, AddressBudget> addressBudgets = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, StaticAsset> assets = new ConcurrentHashMap<>();
        private final ScheduledExecutorService eventWorker;

        Transport(final WebMapConfig config, final WebMapBackend backend, final Path configDirectory) {
            super(config.bindAddress, config.port);
            this.backend = backend;
            this.config = config;
            title = config.title;
            favicon = WebMapBranding.loadIcon(config.favicon, configDirectory);
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
            return gatewayAuthorized(session) && "/api/v1/map".equals(session.getUri())
                && webSocketOriginAllowed(session)
                && super.isWebsocketRequested(session);
        }

        @Override
        protected WebSocket openWebSocket(final IHTTPSession handshake) {
            return new MapSocket(handshake);
        }

        @Override
        protected Response serveHttp(final IHTTPSession session) {
            if(!gatewayAuthorized(session)) return secure(text(Response.Status.FORBIDDEN,NanoHTTPD.MIME_PLAINTEXT,"gateway authentication required"));
            final String path = session.getUri();
            if ("/api/v1/map".equals(path) && super.isWebsocketRequested(session)) {
                return secure(text(
                    Response.Status.FORBIDDEN,
                    NanoHTTPD.MIME_PLAINTEXT,
                    "websocket origin is not allowed"
                ));
            }
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
            if (path.startsWith("/api/v1/waypoint-images/")) {
                if (session.getMethod() != Method.GET) return methodNotAllowed("GET");
                try {
                    String name=path.substring("/api/v1/waypoint-images/".length());
                    UUID id=UUID.fromString(name.endsWith(".png")?name.substring(0,name.length()-4):name);
                    WebMapFeatures features=backend.features();
                    byte[] data=features==null?null:features.image(id);
                    if(data!=null){Response result=bytes(Response.Status.OK,"image/png",data);result.addHeader("Cache-Control","no-store");return secure(result);}
                }catch(IllegalArgumentException ignored){}
                return secure(text(Response.Status.NOT_FOUND,NanoHTTPD.MIME_PLAINTEXT,"image unavailable"));
            }
            if (path.startsWith("/api/v1/avatars/")) {
                return avatar(session, path);
            }
            return staticAsset(session, path);
        }

        private boolean gatewayAuthorized(IHTTPSession session){
            String token=config.gatewayToken;
            String supplied=header(session,"x-conflux-gateway");
            return token==null||(supplied!=null&&MessageDigest.isEqual(token.getBytes(StandardCharsets.US_ASCII),supplied.getBytes(StandardCharsets.US_ASCII)));
        }
        private static boolean webSocketOriginAllowed(final IHTTPSession session) {
            final String originValue = header(session, "origin");
            if (originValue == null || originValue.isBlank()) {
                // Non-browser clients are not required to send Origin. Browser WebSocket APIs do.
                return true;
            }
            final String hostValue = header(session, "host");
            if (hostValue == null || hostValue.isBlank()) return false;
            try {
                final URI origin = URI.create(originValue);
                if (origin.getHost() == null || origin.getUserInfo() != null
                    || origin.getRawQuery() != null || origin.getRawFragment() != null
                    || !(origin.getRawPath() == null || origin.getRawPath().isEmpty())) {
                    return false;
                }
                final String expectedScheme = forwardedScheme(session);
                if (!expectedScheme.equalsIgnoreCase(origin.getScheme())) return false;
                final URI request = URI.create(expectedScheme + "://" + hostValue);
                return request.getHost() != null
                    && request.getHost().equalsIgnoreCase(origin.getHost())
                    && effectivePort(request) == effectivePort(origin);
            } catch (final IllegalArgumentException e) {
                return false;
            }
        }

        private static String forwardedScheme(final IHTTPSession session) {
            final String direct = session.getRemoteIpAddress();
            final String forwarded = header(session, "x-forwarded-proto");
            if (loopback(direct) && forwarded != null) {
                final String first = forwarded.split(",", 2)[0].trim();
                if ("http".equalsIgnoreCase(first) || "https".equalsIgnoreCase(first)) {
                    return first.toLowerCase(java.util.Locale.ROOT);
                }
            }
            return "http";
        }

        private static int effectivePort(final URI uri) {
            if (uri.getPort() >= 0) return uri.getPort();
            return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
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
                ? "public, max-age=31536000, immutable"
                : path.equals("/favicon.ico") ? "public, max-age=300" : "no-cache");
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

        private StaticAsset loadAsset(final String path, final boolean gzip) throws IOException {
            if ("/favicon.ico".equals(path) && favicon != null) {
                return new StaticAsset(favicon.body(), favicon.contentType(), etag(favicon.body()));
            }
            try (InputStream input = WebMapServer.class.getResourceAsStream("/webmap" + path)) {
                if (input == null) return null;
                byte[] body = input.readAllBytes();
                if ("/index.html".equals(path)) {
                    String html = new String(body, StandardCharsets.UTF_8).replace(
                        "<title>Conflux Map</title>", "<title>" + WebMapBranding.escapeHtml(title) + "</title>"
                    );
                    if (favicon != null) {
                        html = html.replace("</head>", "  <link rel=\"icon\" href=\"/favicon.ico\" type=\""
                            + favicon.contentType() + "\">\n</head>");
                    }
                    body = html.getBytes(StandardCharsets.UTF_8);
                }
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
                socket.sendFeatures();
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
            private volatile long waypointRevision = Long.MIN_VALUE;
            private final String address;
            private String lastPolicy="", lastChat="", lastImages="";

            MapSocket(final IHTTPSession handshakeRequest) {
                super(handshakeRequest);
                address=clientAddress(handshakeRequest);
                budget = addressBudget(address);
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
                sendFeatures();
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
                    } else {
                        String text=frame.getTextPayload();
                        WebMapFeatures features=backend.features();
                        if(features!=null && text.length()<=2048){
                            try{
                                com.google.gson.JsonObject msg=new com.google.gson.Gson().fromJson(text,com.google.gson.JsonObject.class);
                                if("chat-send".equals(msg.get("type").getAsString())){
                                    String error=features.webChat(address,msg.get("text").getAsString());
                                    com.google.gson.JsonObject response=new com.google.gson.JsonObject();
                                    response.addProperty("type","chat-result");response.addProperty("message",error);
                                    sendStatePayload(response.toString());
                                }
                            }catch(RuntimeException ignored){}
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
                if (!budget.budget.beginRequest(now, backend.manifest().limits().minRequestIntervalMs())) {
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
                if (!isOpen()) return;
                if (snapshot.playerRevision() != playerRevision) {
                    final String message = "{\"type\":\"players\",\"snapshot\":"
                        + snapshot.playersJson() + "}";
                    if (sendStatePayload(message)) playerRevision = snapshot.playerRevision();
                }
                if (snapshot.waypointRevision() != waypointRevision) {
                    final String message = "{\"type\":\"waypoints\",\"snapshot\":"
                        + snapshot.waypointsJson() + "}";
                    if (sendStatePayload(message)) waypointRevision = snapshot.waypointRevision();
                }
            }

            private synchronized void sendFeatures(){
                WebMapFeatures f=backend.features();if(f==null||!isOpen())return;
                String policy=f.policyJson(backend.manifest().limits()),chat=f.chatJson(),images=f.imagesJson();
                if(!policy.equals(lastPolicy)&&sendStatePayload(policy))lastPolicy=policy;
                if(!chat.equals(lastChat)&&sendStatePayload(chat))lastChat=chat;
                if(!images.equals(lastImages)&&sendStatePayload(images))lastImages=images;
            }

            private boolean sendStatePayload(final String payload) {
                final byte[] encoded = payload.getBytes(StandardCharsets.UTF_8);
                final long now = System.nanoTime();
                budget.lastSeen = now;
                if (encoded.length > Proto.MAX_S2C_PAYLOAD
                    || !budget.budget.allowBytes(encoded.length, now)) return false;
                try {
                    send(payload);
                    return true;
                } catch (final IOException e) {
                    closeQuietly(WebSocketFrame.CloseCode.GoingAway, "write failed");
                    return false;
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

    /**
     * Host to show in {@link #uri}: the configured bind address as the operator wrote it, except
     * that a wildcard bind — which listens on every interface — is displayed as a loopback
     * address that still opens. Resolution matches the transport's own bind, which resolves the
     * same string via {@code InetSocketAddress}.
     */
    private static String displayHost(final String bindAddress) {
        try {
            if (InetAddress.getByName(bindAddress).isAnyLocalAddress()) {
                return "127.0.0.1";
            }
        } catch (final UnknownHostException e) {
            // An unresolvable address could not have produced a bound transport either.
        }
        final String literal = bindAddress.startsWith("[") && bindAddress.endsWith("]")
            ? bindAddress.substring(1, bindAddress.length() - 1)
            : bindAddress;
        // URI authorities bracket IPv6 literals and percent-encode zone ids (RFC 6874).
        return literal.contains(":") ? "[" + literal.replace("%", "%25") + "]" : literal;
    }

    private static boolean acceptsGzip(final NanoHTTPD.IHTTPSession session) {
        final String accepted = header(session, "accept-encoding");
        return accepted != null && java.util.Arrays.stream(accepted.split(","))
            .map(String::trim)
            .anyMatch(value -> value.equals("gzip") || value.startsWith("gzip;"));
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
