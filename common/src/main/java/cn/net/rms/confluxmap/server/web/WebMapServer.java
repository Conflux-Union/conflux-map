package cn.net.rms.confluxmap.server.web;

import cn.net.rms.confluxmap.core.net.MapRegionViewReqC2S;
import cn.net.rms.confluxmap.core.net.Message;
import cn.net.rms.confluxmap.core.net.MsgCodec;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.net.ProtoException;
import cn.net.rms.confluxmap.server.PlayerBudget;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Small loopback-first HTTP transport for the browser map. */
public final class WebMapServer implements AutoCloseable {
    private static final String SESSION_COOKIE = "CFXWEB";
    private static final Duration REGION_TIMEOUT = Duration.ofSeconds(15);

    private final HttpServer server;
    private final ExecutorService executor;
    private final WebMapBackend backend;
    private final WebMapConfig config;
    private final ConcurrentHashMap<String, AddressBudget> addressBudgets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, StaticAsset> assets = new ConcurrentHashMap<>();

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

    private WebMapServer(
        final HttpServer server,
        final ExecutorService executor,
        final WebMapBackend backend,
        final WebMapConfig config
    ) {
        this.server = server;
        this.executor = executor;
        this.backend = backend;
        this.config = config;
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
        final HttpServer http = HttpServer.create(
            new InetSocketAddress(requestedConfig.bindAddress, requestedConfig.port),
            Math.max(16, requestedConfig.maxConnections * 2)
        );
        final ThreadPoolExecutor workers = new ThreadPoolExecutor(
            1,
            requestedConfig.maxConnections,
            30L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(Math.max(32, requestedConfig.maxConnections * 4)),
            runnable -> {
                final Thread thread = new Thread(runnable, "ConfluxMap-web");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy()
        );
        workers.allowCoreThreadTimeOut(true);
        http.setExecutor(workers);
        final WebMapServer result = new WebMapServer(http, workers, backend, requestedConfig);
        http.createContext("/api/v1/manifest", result::manifest);
        http.createContext("/api/v1/regions", result::regions);
        http.createContext("/api/v1/events", result::events);
        http.createContext("/api/v1/avatars/", result::avatar);
        http.createContext("/", result::staticAsset);
        http.start();
        return result;
    }

    public URI uri(final String path) {
        final InetSocketAddress address = server.getAddress();
        return URI.create("http://127.0.0.1:" + address.getPort() + path);
    }

    private void manifest(final HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "GET");
            return;
        }
        final byte[] body = backend.manifest().toJson().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("content-type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("cache-control", "no-cache");
        send(exchange, 200, body);
    }

    private void regions(final HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "POST");
            return;
        }
        final long requestTime = System.nanoTime();
        final String address = clientAddress(exchange);
        evictAddressIfFull(address);
        final AddressBudget addressEntry = addressBudgets.computeIfAbsent(
            address, ignored -> new AddressBudget(config, requestTime)
        );
        addressEntry.lastSeen = requestTime;
        if (!addressEntry.budget.beginRequest(requestTime)) {
            sendText(exchange, 429, "map request is rate limited");
            return;
        }
        final byte[] requestBody = readBounded(exchange, Proto.MAX_C2S_PAYLOAD);
        if (requestBody == null) {
            return;
        }
        final Message decoded;
        try {
            decoded = MsgCodec.decode(requestBody);
        } catch (final ProtoException e) {
            sendText(exchange, 400, "invalid map request");
            return;
        }
        if (!(decoded instanceof final MapRegionViewReqC2S request)) {
            sendText(exchange, 400, "expected map region request");
            return;
        }
        final UUID clientId = session(exchange);
        try {
            final List<byte[]> frames = backend.requestRegions(clientId, request, requestBody.length)
                .get(REGION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            final DataOutputStream out = new DataOutputStream(bytes);
            for (final byte[] frame : frames) {
                if (frame == null || frame.length > Proto.MAX_S2C_PAYLOAD) {
                    throw new IOException("backend returned an invalid map frame");
                }
                out.writeInt(frame.length);
                out.write(frame);
            }
            out.flush();
            if (!addressEntry.budget.allowBytes(bytes.size(), System.nanoTime())) {
                sendText(exchange, 429, "map response exceeds the address bandwidth budget");
                return;
            }
            exchange.getResponseHeaders().set("content-type", "application/octet-stream");
            exchange.getResponseHeaders().set("cache-control", "no-store");
            send(exchange, 200, bytes.toByteArray());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            sendText(exchange, 503, "map request interrupted");
        } catch (final Exception e) {
            sendText(exchange, 504, "map request timed out");
        }
    }

    private static String clientAddress(final HttpExchange exchange) {
        final String direct = exchange.getRemoteAddress().getAddress().getHostAddress();
        if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress()) return direct;
        final String forwarded = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
        if (forwarded == null) return direct;
        final String first = forwarded.split(",", 2)[0].trim();
        return first.matches("[0-9A-Fa-f:.]{3,45}") ? first : direct;
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

    private void staticAsset(final HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "GET");
            return;
        }
        String path = exchange.getRequestURI().getPath();
        if ("/".equals(path)) {
            path = "/index.html";
        }
        if (!path.matches("/[A-Za-z0-9._/-]+") || path.contains("..")) {
            sendText(exchange, 404, "not found");
            return;
        }
        StaticAsset asset = assets.get(path);
        if (asset == null) {
            asset = loadAsset(path);
            if (asset == null) {
                sendText(exchange, 404, "not found");
                return;
            }
            final StaticAsset existing = assets.putIfAbsent(path, asset);
            if (existing != null) asset = existing;
        }
        final Headers headers = exchange.getResponseHeaders();
        headers.set("content-type", asset.contentType());
        headers.set("cache-control", path.startsWith("/vendor/")
            ? "public, max-age=31536000, immutable" : "no-cache");
        headers.set("etag", asset.etag());
        headers.set(
            "content-security-policy",
            "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; connect-src 'self'"
        );
        if (asset.etag().equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
            exchange.sendResponseHeaders(304, -1);
            exchange.close();
            return;
        }
        send(exchange, 200, asset.body());
    }

    private void avatar(final HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "GET");
            return;
        }
        final String path = exchange.getRequestURI().getPath();
        final String id = path.substring("/api/v1/avatars/".length());
        final UUID playerId;
        try {
            playerId = UUID.fromString(id.endsWith(".png")
                ? id.substring(0, id.length() - 4) : id);
        } catch (final IllegalArgumentException e) {
            sendText(exchange, 404, "avatar not found");
            return;
        }
        try {
            final byte[] body = backend.avatar(playerId).get(10, TimeUnit.SECONDS);
            if (body == null || body.length == 0) {
                sendText(exchange, 404, "avatar not found");
                return;
            }
            exchange.getResponseHeaders().set("content-type", "image/png");
            exchange.getResponseHeaders().set("cache-control", "public, max-age=3600");
            send(exchange, 200, body);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            sendText(exchange, 503, "avatar request interrupted");
        } catch (final Exception e) {
            sendText(exchange, 502, "avatar unavailable");
        }
    }

    private static StaticAsset loadAsset(final String path) throws IOException {
        try (InputStream input = WebMapServer.class.getResourceAsStream("/webmap" + path)) {
            if (input == null) return null;
            final byte[] body = input.readAllBytes();
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

    private void events(final HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "GET");
            return;
        }
        final Headers headers = exchange.getResponseHeaders();
        headers.set("content-type", "text/event-stream; charset=utf-8");
        headers.set("cache-control", "no-cache, no-transform");
        headers.set("X-Accel-Buffering", "no");
        headers.set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(200, 0);
        long lastRevision = Long.MIN_VALUE;
        try (exchange; OutputStream out = exchange.getResponseBody()) {
            while (!Thread.currentThread().isInterrupted()) {
                final WebPlayerSnapshot snapshot = backend.players();
                if (snapshot.revision() != lastRevision) {
                    final String event = "id: " + snapshot.revision() + "\n"
                        + "event: players\ndata: " + snapshot.toJson() + "\n\n";
                    out.write(event.getBytes(StandardCharsets.UTF_8));
                    lastRevision = snapshot.revision();
                } else {
                    out.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
                }
                out.flush();
                try {
                    Thread.sleep(2_000L);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        } catch (final IOException ignored) {
            // Browsers normally close the stream while panning away or reloading.
        }
    }

    private static String contentType(final String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (path.endsWith(".wasm")) return "application/wasm";
        if (path.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }

    private UUID session(final HttpExchange exchange) {
        final List<String> cookies = exchange.getRequestHeaders().getOrDefault("Cookie", List.of());
        for (final String header : cookies) {
            for (final String cookie : header.split(";")) {
                final String trimmed = cookie.trim();
                if (trimmed.startsWith(SESSION_COOKIE + "=")) {
                    try {
                        final UUID existing = UUID.fromString(
                            trimmed.substring(SESSION_COOKIE.length() + 1)
                        );
                        sessions.put(existing, System.nanoTime());
                        return existing;
                    } catch (final IllegalArgumentException ignored) {
                        break;
                    }
                }
            }
        }
        final UUID created = UUID.randomUUID();
        evictSessionIfFull();
        sessions.put(created, System.nanoTime());
        exchange.getResponseHeaders().add(
            "Set-Cookie",
            SESSION_COOKIE + "=" + created + "; Path=/; HttpOnly; SameSite=Strict"
        );
        return created;
    }

    private void evictSessionIfFull() {
        if (sessions.size() < config.maxConnections * 4) return;
        UUID oldest = null;
        long oldestSeen = Long.MAX_VALUE;
        for (final var entry : sessions.entrySet()) {
            if (entry.getValue() < oldestSeen) {
                oldest = entry.getKey();
                oldestSeen = entry.getValue();
            }
        }
        if (oldest != null && sessions.remove(oldest) != null) {
            backend.removeClient(oldest);
        }
    }

    private static byte[] readBounded(final HttpExchange exchange, final int maximum) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[4096];
        int total = 0;
        int read;
        while ((read = exchange.getRequestBody().read(buffer)) != -1) {
            total += read;
            if (total > maximum) {
                sendText(exchange, 413, "request body too large");
                return null;
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static void methodNotAllowed(final HttpExchange exchange, final String allowed) throws IOException {
        exchange.getResponseHeaders().set("Allow", allowed);
        sendText(exchange, 405, "method not allowed");
    }

    private static void sendText(final HttpExchange exchange, final int status, final String text) throws IOException {
        exchange.getResponseHeaders().set("content-type", "text/plain; charset=utf-8");
        send(exchange, status, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(final HttpExchange exchange, final int status, final byte[] body) throws IOException {
        final Headers headers = exchange.getResponseHeaders();
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
        exchange.sendResponseHeaders(status, body.length);
        try (exchange; var response = exchange.getResponseBody()) {
            response.write(body);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
        for (final UUID clientId : sessions.keySet()) {
            backend.removeClient(clientId);
        }
        sessions.clear();
    }
}
