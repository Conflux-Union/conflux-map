package cn.net.rms.confluxmap.server.web;

import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Same-user, same-host registry and elected HTTP/WS relay. Only loopback agents are routable. */
public final class WebMapGateway implements AutoCloseable {
    private record Agent(UUID id, int port, String name, String gameMode, long expires, String token) {}

    private final Path dir, own;
    private final UUID id;
    private final int port, localPort;
    private final String host, token;
    private final Supplier<String> name, gameMode;
    private final ScheduledExecutorService heartbeat =
            Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "webmap-election"));
    private final ExecutorService workers =
            Executors.newCachedThreadPool(r -> daemon(r, "webmap-relay"));
    private final Semaphore slots;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private FileChannel lockChannel, agentLockChannel;
    private FileLock agentLock;
    private FileLock lock;
    private volatile ServerSocket listener;

    public WebMapGateway(
            WebMapConfig cfg, UUID id, int localPort, String token, Supplier<String> name,
            Supplier<String> gameMode)
            throws IOException {
        this.id = id;
        this.port = cfg.port;
        this.localPort = localPort;
        this.name = name;
        this.gameMode = gameMode;
        this.host = cfg.bindAddress;
        this.token = token;
        slots = new Semaphore(Math.max(32, cfg.maxConnections * 4));
        String key =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(host.getBytes(StandardCharsets.UTF_8));
        dir =
                Path.of(
                        System.getProperty("user.home"),
                        ".confluxmap",
                        "web-gateway",
                        key + "-" + port);
        Files.createDirectories(dir);
        try {
            Files.setPosixFilePermissions(
                    dir, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException ignored) {
        }
        own = dir.resolve(id + ".agent");
        try {
            agentLockChannel =
                    FileChannel.open(
                            dir.resolve(id + ".lock"),
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE);
            try {
                agentLock = agentLockChannel.tryLock();
            } catch (OverlappingFileLockException e) {
                throw new IOException("duplicate server instance ID", e);
            }
            if (agentLock == null) throw new IOException("duplicate server instance ID");
            tick();
            heartbeat.scheduleWithFixedDelay(this::safeTick, 1, 1, TimeUnit.SECONDS);
        } catch (IOException | RuntimeException ex) {
            close();
            throw ex;
        }
    }

    private static Thread daemon(Runnable r, String n) {
        Thread t = new Thread(r, n);
        t.setDaemon(true);
        return t;
    }

    private void safeTick() {
        try {
            tick();
        } catch (Exception e) {
            System.err.println("[ConfluxMap] gateway: " + e.getMessage());
        }
    }

    private synchronized void tick() throws IOException {
        if (closed.get()) return;
        Properties p = new Properties();
        p.setProperty("id", id.toString());
        p.setProperty("port", Integer.toString(localPort));
        p.setProperty("name", name.get());
        final String mode = gameMode.get();
        if (mode != null) p.setProperty("gameMode", mode);
        p.setProperty("expires", Long.toString(System.currentTimeMillis() + 8000));
        p.setProperty("token", token);
        Path tmp = Files.createTempFile(dir, "agent-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                p.store(out, null);
            }
            Files.move(
                    tmp, own, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
        if (listener != null) return;
        if (lockChannel == null)
            lockChannel =
                    FileChannel.open(
                            dir.resolve("leader.lock"),
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE);
        try {
            lock = lockChannel.tryLock();
        } catch (OverlappingFileLockException e) {
            return;
        }
        if (lock == null) return;
        ServerSocket candidate = new ServerSocket();
        try {
            candidate.setReuseAddress(true);
            candidate.bind(new InetSocketAddress(host, port));
            listener = candidate;
            workers.execute(() -> accept(candidate));
        } catch (IOException | RuntimeException e) {
            candidate.close();
            lock.release();
            lock = null;
            throw e;
        }
    }

    private List<Agent> agents() throws IOException {
        List<Agent> list = new ArrayList<>();
        long now = System.currentTimeMillis();
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(dir, "*.agent")) {
            for (Path path : paths) {
                if (list.size() >= 128) break;
                try {
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                            || Files.size(path) > 4096) continue;
                    Properties p = new Properties();
                    try (InputStream in = Files.newInputStream(path)) {
                        p.load(in);
                    }
                    UUID aid = UUID.fromString(p.getProperty("id"));
                    int ap = Integer.parseInt(p.getProperty("port"));
                    long ex = Long.parseLong(p.getProperty("expires"));
                    if (!path.getFileName().toString().equals(aid + ".agent")
                            || ap < 1
                            || ap > 65535
                            || ex < now
                            || ex > now + 16000) continue;
                    String auth = p.getProperty("token", "");
                    if (!auth.matches("[A-Za-z0-9_-]{43}")) continue;
                    list.add(new Agent(aid, ap, p.getProperty("name", "Server"),
                        p.getProperty("gameMode"), ex, auth));
                } catch (Exception ignored) {
                }
            }
        }
        list.sort(Comparator.comparing(a -> a.id.toString()));
        return list;
    }

    private void accept(ServerSocket server) {
        while (!closed.get() && !server.isClosed())
            try {
                Socket client = server.accept();
                if (!slots.tryAcquire()) {
                    client.close();
                    continue;
                }
                sockets.add(client);
                workers.execute(
                        () -> {
                            try (client) {
                                relay(client);
                            } catch (IOException | RuntimeException ignored) {
                            } finally {
                                sockets.remove(client);
                                slots.release();
                            }
                        });
            } catch (IOException | RejectedExecutionException e) {
                break;
            }
    }

    private static String readLine(InputStream in, int max) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == 10) {
                byte[] data = buf.toByteArray();
                if (data.length == 0 || data[data.length - 1] != 13)
                    throw new IOException("bad HTTP line");
                return new String(data, 0, data.length - 1, StandardCharsets.ISO_8859_1);
            }
            if (buf.size() >= max) throw new IOException("header too large");
            buf.write(b);
        }
        throw new EOFException();
    }

    private static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 32) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }

    private static void response(Socket s, int status, String reason, String type, String body)
            throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = s.getOutputStream()) {
            out.write(
                    ("HTTP/1.1 "
                                    + status
                                    + " "
                                    + reason
                                    + "\r\nContent-Type: "
                                    + type
                                    + "\r\nContent-Length: "
                                    + data.length
                                    + "\r\n"
                                    + "Cache-Control: no-store\r\n"
                                    + "Connection: close\r\n"
                                    + "X-Content-Type-Options: nosniff\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
            out.write(data);
            out.flush();
        }
    }

    private void relay(Socket client) throws IOException {
        client.setSoTimeout(10000);
        InputStream in = client.getInputStream();
        String first = readLine(in, 8192);
        String[] words = first.split(" ");
        if (words.length != 3
                || !words[0].equals("GET")
                || !words[1].startsWith("/")
                || words[1].startsWith("//")
                || !words[2].equals("HTTP/1.1")) {
            response(client, 405, "Method Not Allowed", "text/plain", "GET required");
            return;
        }
        URI target = URI.create(words[1]);
        Map<String, String> headers = new LinkedHashMap<>();
        int size = first.length();
        for (; ; ) {
            String line = readLine(in, 8192);
            if (line.isEmpty()) break;
            size += line.length();
            if (size > 32768) throw new IOException("headers too large");
            int colon = line.indexOf(':');
            if (colon <= 0) throw new IOException("bad header");
            String k = line.substring(0, colon).toLowerCase(Locale.ROOT);
            if (line.substring(colon + 1).chars().anyMatch(c -> c < 32 && c != 9 || c == 127))
                throw new IOException("invalid header value");
            if (!k.matches("[a-z0-9-]+")
                    || headers.putIfAbsent(k, line.substring(colon + 1).trim()) != null)
                throw new IOException("duplicate/invalid header");
        }
        if (!headers.containsKey("host")
                || headers.containsKey("transfer-encoding")
                || !headers.getOrDefault("content-length", "0").equals("0"))
            throw new IOException("invalid request");
        List<Agent> agents = agents();
        if (target.getPath().equals("/api/v1/servers")) {
            StringJoiner json = new StringJoiner(",", "[", "]");
            for (Agent a : agents) {
                String mode = a.gameMode == null ? "" : ",\"gameMode\":" + quote(a.gameMode);
                json.add("{\"id\":" + quote(a.id.toString()) + ",\"name\":" + quote(a.name) + mode + "}");
            }
            response(client, 200, "OK", "application/json; charset=utf-8", json.toString());
            return;
        }
        String requested = null;
        for (String q : Optional.ofNullable(target.getRawQuery()).orElse("").split("&"))
            if (q.startsWith("serverId=")) requested = q.substring(9);
        Agent agent = null;
        for (Agent a : agents)
            if (requested == null || a.id.toString().equals(requested)) {
                agent = a;
                break;
            }
        if (agent == null) {
            response(client, 503, "Service Unavailable", "text/plain", "Server unavailable");
            return;
        }
        boolean ws = "websocket".equalsIgnoreCase(headers.get("upgrade"));
        try (Socket backend = new Socket()) {
            sockets.add(backend);
            try {
                backend.connect(new InetSocketAddress("127.0.0.1", agent.port), 2000);
                backend.setSoTimeout(10000);
                StringBuilder request =
                        new StringBuilder("GET ").append(words[1]).append(" HTTP/1.1\r\n");
                Set<String> allowed =
                        Set.of(
                                "host",
                                "origin",
                                "accept",
                                "accept-encoding",
                                "if-none-match",
                                "user-agent",
                                "sec-websocket-key",
                                "sec-websocket-version",
                                "sec-websocket-protocol",
                                "upgrade");
                for (var entry : headers.entrySet())
                    if (allowed.contains(entry.getKey()))
                        request.append(entry.getKey())
                                .append(": ")
                                .append(entry.getValue())
                                .append("\r\n");
                String remote = client.getInetAddress().getHostAddress();
                String proto = "http";
                if (client.getInetAddress().isLoopbackAddress()) {
                    if ("https".equalsIgnoreCase(headers.get("x-forwarded-proto"))) proto = "https";
                    String forwarded =
                            headers.getOrDefault("x-forwarded-for", "").split(",", 2)[0].trim();
                    if (forwarded.matches("[0-9A-Fa-f:.]{3,45}")) remote = forwarded;
                }
                request.append("X-Conflux-Gateway: ").append(agent.token).append("\r\n");
                request.append("X-Forwarded-For: ")
                        .append(remote)
                        .append("\r\nX-Forwarded-Proto: ")
                        .append(proto)
                        .append("\r\nConnection: ")
                        .append(ws ? "Upgrade" : "close")
                        .append("\r\n\r\n");
                backend.getOutputStream()
                        .write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
                backend.getOutputStream().flush();
                if (ws) {
                    client.setSoTimeout(90000);
                    backend.setSoTimeout(90000);
                    workers.execute(
                            () -> {
                                try (backend) {
                                    in.transferTo(backend.getOutputStream());
                                } catch (IOException ignored) {
                                }
                            });
                }
                backend.getInputStream().transferTo(client.getOutputStream());
            } finally {
                sockets.remove(backend);
            }
        }
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) return;
        heartbeat.shutdownNow();
        try {
            if (listener != null) listener.close();
        } catch (IOException ignored) {
        }
        for (Socket s : sockets)
            try {
                s.close();
            } catch (IOException ignored) {
            }
        workers.shutdownNow();
        try {
            if (agentLock != null) Files.deleteIfExists(own);
        } catch (IOException ignored) {
        }
        try {
            if (lock != null) lock.release();
            if (lockChannel != null) lockChannel.close();
            if (agentLock != null) agentLock.release();
            if (agentLockChannel != null) agentLockChannel.close();
        } catch (IOException ignored) {
        }
    }
}
