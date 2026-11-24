package cn.net.rms.confluxmap.server.web;

import com.google.gson.*;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Per-world feature state. Game mutations are serialized on the owning game thread. */
public final class WebMapFeatures implements AutoCloseable {
    public static final String CHANNEL = "confluxmap:webmap_v1";
    private static final int MAX_IMAGE = 2 * 1024 * 1024, MAX_CHUNK = 8192, MAX_IMAGES = 256;
    private static final Gson JSON = new Gson();
    private final WebMapConfig cfg;
    private final Path dir;
    private final Predicate<UUID> exists;
    private BiPredicate<UUID, UUID> owner = (actor, waypoint) -> true;
    private final BooleanSupplier save;
    private final Consumer<Runnable> gameThread;
    private final Consumer<String> broadcast;
    private final ExecutorService imageWorker =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread t = new Thread(r, "webmap-image");
                        t.setDaemon(true);
                        return t;
                    });
    private final Map<UUID, Upload> uploads = new HashMap<>();
    private final Map<UUID, long[]> packetWindows = new HashMap<>();
    private long packetWindow;
    private int packetCount;

    public synchronized boolean allowPacket(UUID id) {
        if (closed) return false;
        long now = System.nanoTime() / 1_000_000_000L;
        if (packetWindow != now) {
            packetWindow = now;
            packetCount = 0;
            packetWindows.entrySet().removeIf(e -> e.getValue()[0] < now - 60);
        }
        if (packetCount >= 1024) return false;
        long[] entry = packetWindows.computeIfAbsent(id, k -> new long[] {now, 0});
        if (entry[0] != now) {
            entry[0] = now;
            entry[1] = 0;
        }
        if (entry[1] >= 64) return false;
        entry[1]++;
        packetCount++;
        return true;
    }

    private final Set<UUID> processing = new HashSet<>();
    private final Map<UUID, Image> images = new LinkedHashMap<>();
    private final ArrayDeque<Chat> history = new ArrayDeque<>();
    private final Map<UUID, URI> skins = new HashMap<>();
    private final LinkedHashMap<String, Long> rate = new LinkedHashMap<>();
    private long chatRevision, imageRevision;
    private long lastWebMessage;
    private volatile boolean closed;

    private record Chat(
            long id,
            String source,
            String senderId,
            String senderName,
            String text,
            long timestamp) {}

    private record Image(
            UUID id, UUID waypoint, UUID author, String authorName, boolean approved) {}

    private static final class Upload {
        final UUID waypoint;
        final int length;
        final long expires = System.currentTimeMillis() + 120000;
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        Upload(UUID w, int n) {
            waypoint = w;
            length = n;
        }
    }

    public WebMapFeatures(
            WebMapConfig cfg,
            Path dir,
            Predicate<UUID> exists,
            BooleanSupplier save,
            Consumer<Runnable> gameThread,
            Consumer<String> broadcast) {
        this.cfg = cfg;
        this.dir = dir;
        this.exists = exists;
        this.save = save;
        this.gameThread = gameThread;
        this.broadcast = broadcast;
        try {
            Files.createDirectories(dir);
            try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
                for (Path p : files) {
                    if (images.size() >= MAX_IMAGES) break;
                    try {
                        if (Files.size(p) > 4096) continue;
                        Image im = JSON.fromJson(Files.readString(p), Image.class);
                        if (im != null
                                && im.id() != null
                                && im.waypoint() != null
                                && im.author() != null
                                && p.getFileName().toString().equals(im.id() + ".json")
                                && Files.isRegularFile(file(im.id()))) images.put(im.id(), im);
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("[ConfluxMap] image store: " + e.getMessage());
        }
    }

    public synchronized void restrictAttachments(BiPredicate<UUID, UUID> owner) {
        this.owner = owner;
    }

    public static byte[] publicationTarget(
        cn.net.rms.confluxmap.core.net.shared.SharedWaypointMessage request,
        java.util.List<cn.net.rms.confluxmap.core.net.shared.SharedWaypointMessage> deltas
    ) {
        if (!(request instanceof cn.net.rms.confluxmap.core.net.shared.CreateC2S create)) return null;
        for (var delta : deltas) {
            if (delta instanceof cn.net.rms.confluxmap.core.net.shared.UpsertS2C upsert) {
                JsonObject reply = new JsonObject();
                reply.addProperty("type", "image-target");
                reply.addProperty("operation", create.operationId().toString());
                reply.addProperty("waypoint", upsert.waypoint().id().toString());
                return JSON.toJson(reply).getBytes(StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private Path file(UUID id) {
        return dir.resolve(id + ".png");
    }

    private static String clean(String text, int max) {
        if (text == null) return "";
        String s = text.replaceAll("[\\p{Cc}\\p{Cf}]", " ").strip();
        return s.codePointCount(0, s.length()) > max
                ? s.substring(0, s.offsetByCodePoints(0, max))
                : s;
    }

    public synchronized void gameChat(UUID id, String name, String text, URI skin) {
        if (closed || !cfg.enableChat || !cfg.showGameChat) return;
        if (skin != null) skins.put(id, skin);
        append("game", id.toString(), clean(name, 64), clean(text, 256));
    }

    private void append(String source, String id, String name, String text) {
        if (text.isBlank()) return;
        history.addLast(
                new Chat(++chatRevision, source, id, name, text, System.currentTimeMillis()));
        while (history.size() > 100) history.removeFirst();
        skins.keySet()
                .removeIf(
                        key -> history.stream().noneMatch(c -> c.senderId.equals(key.toString())));
    }

    public synchronized URI chatSkin(UUID id) {
        return cfg.enableChat && cfg.showGameChat ? skins.get(id) : null;
    }

    public synchronized String webChat(String address, String text) {
        if (closed || !cfg.enableChat || !cfg.allowWebChat) return "Chat disabled";
        String message = clean(text, 256);
        if (message.isBlank()) return "Empty message";
        long now = System.currentTimeMillis();
        Long last = rate.get(address);
        if ((last != null && now - last < 2000) || now - lastWebMessage < 250)
            return "Rate limited";
        lastWebMessage = now;
        rate.put(address, now);
        while (rate.size() > 1024) rate.remove(rate.keySet().iterator().next());
        gameThread.accept(
                () -> {
                    synchronized (this) {
                        if (closed || !cfg.enableChat || !cfg.allowWebChat) return;
                        broadcast.accept("网页地图：" + message);
                        append("web", "", "网页地图", message);
                    }
                });
        return "";
    }

    public synchronized String chatJson() {
        JsonObject o = new JsonObject();
        o.addProperty("type", "chat-history");
        o.addProperty("revision", chatRevision);
        o.add(
                "messages",
                JSON.toJsonTree(
                        cfg.enableChat
                                ? history.stream()
                                        .filter(c -> cfg.showGameChat || !c.source.equals("game"))
                                        .toList()
                                : List.of()));
        return JSON.toJson(o);
    }

    public synchronized String policyJson() {
        JsonObject o = new JsonObject();
        o.addProperty("type", "web-policy");
        o.addProperty("enableChat", cfg.enableChat);
        o.addProperty("allowWebChat", cfg.enableChat && cfg.allowWebChat);
        o.addProperty("showWaypointImages", cfg.showWaypointImages);
        o.addProperty("allowPlayerSearch", cfg.sharePlayers && cfg.allowPlayerSearch);
        o.addProperty("allowWaypointSearch", cfg.allowWaypointSearch);
        o.addProperty("sharePlayers", cfg.sharePlayers);
        return JSON.toJson(o);
    }

    public synchronized String imagesJson() {
        JsonObject o = new JsonObject();
        o.addProperty("type", "waypoint-images");
        JsonArray a = new JsonArray();
        if (cfg.showWaypointImages)
            for (Image im : images.values())
                if (im.approved && exists.test(im.waypoint)) {
                    JsonObject item = new JsonObject();
                    item.addProperty("id", im.id.toString());
                    item.addProperty("waypoint", im.waypoint.toString());
                    a.add(item);
                }
        o.add("images", a);
        o.addProperty("revision", imageRevision);
        return JSON.toJson(o);
    }

    public synchronized byte[] image(UUID id) {
        Image im = images.get(id);
        if (closed
                || !cfg.showWaypointImages
                || im == null
                || !im.approved
                || !exists.test(im.waypoint)) return null;
        try {
            Path p = file(id);
            if (Files.size(p) > 8L * 1024 * 1024) return null;
            return Files.readAllBytes(p);
        } catch (IOException e) {
            return null;
        }
    }

    private boolean allowed(boolean op) {
        return cfg.waypointImageMode != WebMapConfig.ImageMode.DISABLED
                && (op || cfg.waypointImageMode != WebMapConfig.ImageMode.OP_ONLY);
    }

    private boolean canUpload(boolean op) {
        return allowed(op) && cfg.showWaypointImages;
    }

    private void result(Consumer<byte[]> reply, String text) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "result");
        o.addProperty("message", text);
        reply.accept(JSON.toJson(o).getBytes(StandardCharsets.UTF_8));
    }

    private void status(Consumer<byte[]> reply, boolean op) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "status");
        o.addProperty("admin", op && cfg.allowOperatorClientConfig);
        o.addProperty("upload", canUpload(op));
        o.addProperty("albumUpload", canUpload(op));
        o.addProperty("review", op);
        o.addProperty("sharePlayers", cfg.sharePlayers);
        o.addProperty("allowPlayerSearch", cfg.allowPlayerSearch);
        o.addProperty("allowWaypointSearch", cfg.allowWaypointSearch);
        o.addProperty("showWaypointImages", cfg.showWaypointImages);
        o.addProperty("waypointImageMode", cfg.waypointImageMode.name());
        o.addProperty("enableChat", cfg.enableChat);
        o.addProperty("showGameChat", cfg.showGameChat);
        o.addProperty("allowWebChat", cfg.allowWebChat);
        reply.accept(JSON.toJson(o).getBytes(StandardCharsets.UTF_8));
    }

    public synchronized void settingsChanged() {
        if (!cfg.enableChat) {
            history.clear();
            skins.clear();
        }
        if (!cfg.showGameChat) {
            history.removeIf(c -> c.source.equals("game"));
            skins.clear();
        }
        chatRevision++;
        imageRevision++;
    }

    public synchronized void disconnect(UUID id) {
        uploads.remove(id);
        packetWindows.remove(id);
    }

    public synchronized void handle(
            UUID actor,
            String actorName,
            BooleanSupplier op,
            BooleanSupplier active,
            byte[] payload,
            Consumer<byte[]> reply,
            Consumer<String> notifyOps) {
        if (closed || payload.length < 2 || payload.length > 16000 || !active.getAsBoolean())
            return;
        try {
            JsonObject o =
                    JSON.fromJson(new String(payload, StandardCharsets.UTF_8), JsonObject.class);
            String type = o.get("type").getAsString();
            boolean admin = op.getAsBoolean();
            uploads.values().removeIf(u -> u.expires < System.currentTimeMillis());
            switch (type) {
                case "status" -> status(reply, admin);
                case "set" -> {
                    if (!admin || !cfg.allowOperatorClientConfig) {
                        result(reply, "Forbidden");
                        return;
                    }
                    String key = o.get("key").getAsString(), value = o.get("value").getAsString();
                    String previous = ServerConfigControl.value(cfg, key);
                    if (previous == null) {
                        result(reply, "Unknown setting");
                        return;
                    }
                    ServerConfigControl.set(cfg, key, value);
                    if (!save.getAsBoolean()) {
                        ServerConfigControl.set(cfg, key, previous);
                        result(reply, "Save failed");
                    } else {
                        settingsChanged();
                        result(reply, "Saved");
                    }
                    status(reply, admin);
                }
                case "image-start" -> {
                    UUID w = UUID.fromString(o.get("waypoint").getAsString());
                    int n = o.get("length").getAsInt();
                    if (!canUpload(admin)
                            || !exists.test(w)
                            || !admin && !owner.test(actor, w)
                            || n < 1
                            || n > MAX_IMAGE
                            || uploads.size() >= 16
                            || processing.contains(actor)
                            || images.size() >= MAX_IMAGES) {
                        result(reply, "Upload refused");
                        return;
                    }
                    uploads.put(actor, new Upload(w, n));
                }
                case "image-chunk" -> {
                    Upload u = uploads.get(actor);
                    if (u == null) return;
                    byte[] chunk = Base64.getDecoder().decode(o.get("data").getAsString());
                    if (chunk.length < 1
                            || chunk.length > MAX_CHUNK
                            || u.bytes.size() + chunk.length > u.length) {
                        uploads.remove(actor);
                        result(reply, "Invalid image chunk");
                        return;
                    }
                    u.bytes.writeBytes(chunk);
                }
                case "image-finish" -> {
                    Upload u = uploads.remove(actor);
                    if (u == null
                            || u.bytes.size() != u.length
                            || !canUpload(admin)
                            || !exists.test(u.waypoint)
                            || processing.size() >= 4) {
                        result(reply, "Incomplete or forbidden upload");
                        return;
                    }
                    processing.add(actor);
                    byte[] data = u.bytes.toByteArray();
                    imageWorker.execute(
                            () -> {
                                byte[] png = null;
                                try {
                                    png = WaypointImageCodec.decode(data);
                                } catch (Exception ignored) {
                                }
                                final byte[] ready = png;
                                gameThread.accept(
                                        () ->
                                                finish(
                                                        actor,
                                                        actorName,
                                                        u.waypoint,
                                                        ready,
                                                        op,
                                                        active,
                                                        reply,
                                                        notifyOps));
                            });
                }
                case "image-review" -> sendReview(o.get("index").getAsInt(), admin, reply);
                case "image-approve", "image-reject", "image-delete" -> {
                    if (!admin) {
                        result(reply, "Forbidden");
                        return;
                    }
                    UUID id = UUID.fromString(o.get("id").getAsString());
                    result(
                            reply,
                            moderate(id, type)
                                    ? "Image updated"
                                    : "Image not found or unavailable");
                }
                default -> result(reply, "Unknown request");
            }
        } catch (Exception e) {
            result(reply, "Invalid request");
        }
    }

    private void sendReview(int index, boolean operator, Consumer<byte[]> reply) throws IOException {
        final JsonObject review = new JsonObject();
        review.addProperty("type", "image-review");
        if (operator) {
            List<Image> pending = images.values().stream()
                .filter(image -> !image.approved && exists.test(image.waypoint)).toList();
            review.addProperty("count", pending.size());
            if (!pending.isEmpty()) {
                int selected = Math.floorMod(index, pending.size());
                Image image = pending.get(selected);
                review.addProperty("index", selected);
                review.addProperty("id", image.id.toString());
                review.addProperty("waypoint", image.waypoint.toString());
                review.addProperty("author", image.authorName);
                review.addProperty("preview",
                    Base64.getEncoder().encodeToString(WaypointImageCodec.reviewThumbnail(file(image.id))));
            }
        }
        reply.accept(JSON.toJson(review).getBytes(StandardCharsets.UTF_8));
    }

    private synchronized void finish(
            UUID actor,
            String name,
            UUID waypoint,
            byte[] png,
            BooleanSupplier op,
            BooleanSupplier active,
            Consumer<byte[]> reply,
            Consumer<String> notifyOps) {
        processing.remove(actor);
        if (closed || !active.getAsBoolean()) return;
        boolean admin = op.getAsBoolean();
        if (png == null
                || !canUpload(admin)
                || !exists.test(waypoint)
                || !admin && !owner.test(actor, waypoint)
                || images.size() >= MAX_IMAGES
                || images.values().stream().filter(i -> i.waypoint.equals(waypoint)).count() >= 4) {
            result(reply, "Invalid image, forbidden or quota exceeded");
            return;
        }
        UUID id = UUID.randomUUID();
        Image im =
                new Image(
                        id,
                        waypoint,
                        actor,
                        clean(name, 64),
                        admin || cfg.waypointImageMode == WebMapConfig.ImageMode.EVERYONE);
        try {
            atomic(file(id), png);
            persist(im);
            images.put(id, im);
            imageRevision++;
        } catch (IOException e) {
            try {
                Files.deleteIfExists(file(id));
            } catch (IOException ignored) {
            }
            result(reply, "Image save failed");
            return;
        }
        result(reply, im.approved ? "Image published" : "Awaiting operator approval");
        if (!im.approved)
            notifyOps.accept(
                    "[ConfluxMap] "
                            + im.authorName
                            + " 请求添加公共路径点图片 "
                            + id
                            + " (waypoint "
                            + waypoint
                            + ") | /confluxmap webmap approve "
                            + id
                            + " | /confluxmap webmap reject "
                            + id);
    }

    private void persist(Image im) throws IOException {
        atomic(dir.resolve(im.id + ".json"), JSON.toJson(im).getBytes(StandardCharsets.UTF_8));
    }

    private static void atomic(Path target, byte[] data) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(), "image-", ".tmp");
        try {
            Files.write(tmp, data);
            Files.move(
                    tmp,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public synchronized boolean moderate(UUID id, String action) {
        Image im = images.get(id);
        if (im == null) return false;
        try {
            if (action.equals("image-approve")) {
                if (cfg.waypointImageMode == WebMapConfig.ImageMode.DISABLED
                        || !exists.test(im.waypoint)
                        || im.approved) return false;
                Image next = new Image(im.id, im.waypoint, im.author, im.authorName, true);
                persist(next);
                images.put(id, next);
            } else if (action.equals("image-reject") || action.equals("image-delete")) {
                if (action.equals("image-reject") && im.approved) return false;
                Files.deleteIfExists(dir.resolve(id + ".json"));
                images.remove(id);
                Files.deleteIfExists(file(id));
            } else return false;
            imageRevision++;
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public synchronized List<String> pending() {
        return images.values().stream()
                .filter(i -> !i.approved)
                .map(i -> i.id + " " + i.authorName + " waypoint=" + i.waypoint)
                .toList();
    }

    @Override
    public synchronized void close() {
        closed = true;
        uploads.clear();
        history.clear();
        skins.clear();
        imageWorker.shutdownNow();
    }
}
