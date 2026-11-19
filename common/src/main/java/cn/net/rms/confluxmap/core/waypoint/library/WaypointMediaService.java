package cn.net.rms.confluxmap.core.waypoint.library;

import cn.net.rms.confluxmap.core.model.WorldIdentity;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/** Private, world/source scoped media. All disk access and decoding run on the supplied IO executor. */
public final class WaypointMediaService implements AutoCloseable {
    public record Key(WorldIdentity world, boolean shared, UUID waypoint) {
        public Key { Objects.requireNonNull(world); Objects.requireNonNull(waypoint); }
        String binding() { return (shared ? "shared/" : "local/") + waypoint; }
    }
    public record Photo(UUID id, String extension, String caption, int width, int height, double rotation) {
        public Photo {
            rotation = PhotoRotation.normalize(rotation);
        }
    }
    public record Pixels(int width, int height, int[] argb) {}
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting()
        .registerTypeAdapter(Photo.class, WaypointJson.readUsing(PhotoData.class, PhotoData::toPhoto))
        .create();
    private static final long MAX_BYTES = 32L * 1024 * 1024;
    private static final long MAX_PIXELS = 40_000_000;
    private static final int MAX_PHOTOS = 64;
    private static final int MAX_CAPTION = 512;
    private static final class Document {
        int schemaVersion = 1;
        Map<String, List<Photo>> bindings = new LinkedHashMap<>();
    }
    private static final class PhotoData {
        UUID id;
        String extension, caption;
        int width, height;
        double rotation;

        Photo toPhoto() {
            return new Photo(id, extension, caption, width, height, rotation);
        }
    }
    private final Path root;
    private final Executor executor;
    private ExecutorService ownedExecutor;
    public WaypointMediaService(Path root) {
        this(root, Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "confluxmap-media"); thread.setDaemon(true); return thread;
        }));
        ownedExecutor = (ExecutorService) executor;
    }
    public WaypointMediaService(Path root, Executor executor) {
        this.root = root.toAbsolutePath().normalize(); this.executor = executor;
    }
    private Path folder(Key key) throws IOException {
        Path result = root.resolve(key.world.serverId()).resolve(key.world.worldId()).normalize();
        if (!result.startsWith(root) || result.equals(root)) throw new IOException("Invalid world media path");
        return result;
    }
    private Path image(Key key, Photo photo, boolean thumbnail) throws IOException {
        if (photo.id == null || !("png".equals(photo.extension) || "jpg".equals(photo.extension)))
            throw new IOException("Invalid media entry");
        return folder(key).resolve(photo.id + (thumbnail ? ".thumb.png" : "." + photo.extension));
    }
    private Document load(Key key) throws IOException {
        Path file = folder(key).resolve("index.json");
        if (!Files.exists(file)) return new Document();
        try {
            var json = GSON.fromJson(Files.readString(file), JsonObject.class);
            if (!json.has("schemaVersion") || !json.get("schemaVersion").isJsonPrimitive()
                || !json.getAsJsonPrimitive("schemaVersion").isNumber()
                || json.get("schemaVersion").getAsBigDecimal().compareTo(java.math.BigDecimal.ONE) != 0)
                throw new IOException("Unsupported media index; original preserved");
            Document doc = GSON.fromJson(json, Document.class);
            if (doc == null || doc.schemaVersion != 1 || doc.bindings == null) throw new IOException("Unsupported media index; original preserved");
            for (List<Photo> photos : doc.bindings.values()) {
                if (photos == null || photos.size() > MAX_PHOTOS) throw new IOException("Invalid media index");
                for (Photo photo : photos) {
                    if (photo == null || photo.width <= 0 || photo.height <= 0 || photo.caption == null)
                        throw new IOException("Invalid photo metadata");
                    image(key, photo, false);
                }
            }
            return doc;
        } catch (RuntimeException e) { throw new IOException("Invalid media index; original preserved", e); }
    }
    private void save(Key key, Document doc) throws IOException {
        Path dir = folder(key); Files.createDirectories(dir);
        Path temp = Files.createTempFile(dir, "index-", ".tmp");
        try {
            Files.writeString(temp, GSON.toJson(doc));
            try { Files.move(temp, dir.resolve("index.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, dir.resolve("index.json"), StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
    @FunctionalInterface private interface IO<T> { T run() throws IOException; }
    private <T> CompletableFuture<T> async(IO<T> task) {
        return CompletableFuture.supplyAsync(() -> { synchronized (this) {
            try { return task.run(); } catch (IOException e) { throw new CompletionException(e); }
        } }, executor);
    }
    public CompletableFuture<List<Photo>> list(Key key) {
        return async(() -> List.copyOf(load(key).bindings.getOrDefault(key.binding(), List.of())));
    }
    public CompletableFuture<Map<Key, Photo>> firstPhotos(WorldIdentity world) {
        return async(() -> {
            Document document = load(new Key(world, false, new UUID(0, 0)));
            Map<Key, Photo> photos = new HashMap<>();
            for (var binding : document.bindings.entrySet()) {
                final String name = binding.getKey();
                if (binding.getValue().isEmpty() || !(name.startsWith("local/") || name.startsWith("shared/"))) continue;
                final int separator = name.indexOf('/');
                final String id = name.substring(separator + 1);
                if (id.length() != 36) continue;
                final UUID waypoint;
                try { waypoint = UUID.fromString(id); }
                catch (IllegalArgumentException invalidBinding) { continue; }
                photos.put(new Key(world, name.startsWith("shared/"), waypoint), binding.getValue().get(0));
            }
            return Map.copyOf(photos);
        });
    }

    public CompletableFuture<Photo> add(Key key, Path external) {
        return async(() -> {
            Document doc = load(key);
            List<Photo> photos = new ArrayList<>(doc.bindings.getOrDefault(key.binding(), List.of()));
            if (photos.size() >= MAX_PHOTOS) throw new IOException("Each waypoint supports at most " + MAX_PHOTOS + " photos");
            if (Files.size(external) > MAX_BYTES) throw new IOException("Image exceeds 32 MiB");
            // Copy first: header validation and decode must operate on the same immutable input.
            Files.createDirectories(folder(key));
            Path staged = Files.createTempFile(folder(key), "import-", ".tmp");
            Path original = null, thumb = null;
            boolean committed = false;
            try {
                try (var in = Files.newInputStream(external); var out = Files.newOutputStream(staged)) {
                    byte[] buffer = new byte[8192]; long total = 0; int read;
                    while ((read = in.read(buffer)) != -1) {
                        total += read; if (total > MAX_BYTES) throw new IOException("Image exceeds 32 MiB");
                        out.write(buffer, 0, read);
                    }
                }
                BufferedImage decoded; String extension;
                try (ImageInputStream stream = ImageIO.createImageInputStream(staged.toFile())) {
                    Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
                    if (!readers.hasNext()) throw new IOException("Only PNG and JPEG images are supported");
                    ImageReader reader = readers.next();
                    try {
                        String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                        if (!(format.equals("png") || format.equals("jpeg") || format.equals("jpg"))) throw new IOException("Only PNG and JPEG images are supported");
                        extension = format.equals("png") ? "png" : "jpg";
                        reader.setInput(stream, true, true);
                        validateSize(reader.getWidth(0), reader.getHeight(0));
                        decoded = reader.read(0);
                    } finally { reader.dispose(); }
                }
                Photo photo = new Photo(UUID.randomUUID(), extension, "", decoded.getWidth(), decoded.getHeight(), 0);
                original = image(key, photo, false); thumb = image(key, photo, true);
                Files.move(staged, original);
                ImageIO.write(resize(decoded, 256), "png", thumb.toFile());
                photos.add(photo); doc.bindings.put(key.binding(), photos); save(key, doc);
                committed = true; return photo;
            } finally {
                Files.deleteIfExists(staged);
                if (!committed) { if (original != null) Files.deleteIfExists(original); if (thumb != null) Files.deleteIfExists(thumb); }
            }
        });
    }
    private static void validateSize(int width, int height) throws IOException {
        if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS) throw new IOException("Image exceeds 40 million pixels");
    }
    private static BufferedImage resize(BufferedImage input, int limit) {
        double scale = Math.min(1, (double) limit / Math.max(input.getWidth(), input.getHeight()));
        BufferedImage result = new BufferedImage(Math.max(1, (int) (input.getWidth() * scale)), Math.max(1, (int) (input.getHeight() * scale)), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = result.createGraphics();
        try { graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR); graphics.drawImage(input, 0, 0, result.getWidth(), result.getHeight(), null); }
        finally { graphics.dispose(); }
        return result;
    }
    public CompletableFuture<Pixels> pixels(Key key, UUID id, boolean thumbnail) {
        return async(() -> {
            Photo photo = load(key).bindings.getOrDefault(key.binding(), List.of()).stream().filter(p -> p.id.equals(id)).findFirst().orElseThrow(() -> new IOException("Photo no longer exists"));
            return decodePixels(image(key, photo, thumbnail), thumbnail);
        });
    }

    public CompletableFuture<Pixels> waypointThumbnail(Key key, Photo photo) {
        return async(() -> decodePixels(image(key, photo, true), true));
    }

    public CompletableFuture<Pixels> screenshotPixels(Path file, boolean thumbnail) {
        return async(() -> decodePixels(file, thumbnail));
    }

    private static Pixels decodePixels(Path file, boolean thumbnail) throws IOException {
        if (Files.size(file) > MAX_BYTES) throw new IOException("Image exceeds 32 MiB");
        BufferedImage input;
        try (ImageInputStream stream = ImageIO.createImageInputStream(file.toFile())) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IOException("Invalid image");
            ImageReader reader = readers.next();
            try { reader.setInput(stream, true, true); validateSize(reader.getWidth(0), reader.getHeight(0)); input = reader.read(0); }
            finally { reader.dispose(); }
        }
        BufferedImage scaled = resize(input, thumbnail ? 256 : 2048);
        return new Pixels(scaled.getWidth(), scaled.getHeight(), scaled.getRGB(0, 0, scaled.getWidth(), scaled.getHeight(), null, 0, scaled.getWidth()));
    }
    public CompletableFuture<Photo> saveRotation(Key key, UUID id, double rotation) {
        return async(() -> {
            Document doc = load(key);
            List<Photo> photos = new ArrayList<>(doc.bindings.getOrDefault(key.binding(), List.of()));
            int index = findPhoto(photos, id);
            Photo old = photos.get(index);
            Photo rotated = new Photo(old.id, old.extension, old.caption, old.width, old.height, rotation);
            photos.set(index, rotated);
            doc.bindings.put(key.binding(), photos);
            save(key, doc);
            return rotated;
        });
    }

    private static int findPhoto(List<Photo> photos, UUID id) throws IOException {
        for (int i = 0; i < photos.size(); i++) {
            if (photos.get(i).id.equals(id)) return i;
        }
        throw new IOException("Photo no longer exists");
    }

    /** Reorder/caption changes preserve all other photos. Deletion only unlinks private owned copies. */
    public CompletableFuture<Void> edit(Key key, UUID id, String caption, int move, boolean remove) {
        return async(() -> {
            Document doc = load(key); List<Photo> photos = new ArrayList<>(doc.bindings.getOrDefault(key.binding(), List.of()));
            int index = findPhoto(photos, id);
            Photo old = photos.get(index);
            if (remove) photos.remove(index);
            else {
                if (caption == null || caption.length() > MAX_CAPTION) throw new IOException("Caption exceeds 512 characters");
                photos.set(index, new Photo(old.id, old.extension, caption, old.width, old.height, old.rotation));
                int target = Math.max(0, Math.min(photos.size() - 1, index + Integer.signum(move)));
                Collections.swap(photos, index, target);
            }
            doc.bindings.put(key.binding(), photos); save(key, doc);
            // Keep removed owned files as recoverable orphans; never touch the selected external file.
            return null;
        });
    }
    @Override public void close() { if (ownedExecutor != null) ownedExecutor.shutdown(); }
}
