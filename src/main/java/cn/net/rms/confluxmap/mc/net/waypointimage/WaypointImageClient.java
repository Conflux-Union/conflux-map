package cn.net.rms.confluxmap.mc.net.waypointimage;

import cn.net.rms.confluxmap.compat.Ids;
import cn.net.rms.confluxmap.compat.NativeImages;
import cn.net.rms.confluxmap.compat.PlayNetworking;
import cn.net.rms.confluxmap.core.net.image.ImageDataS2C;
import cn.net.rms.confluxmap.core.net.image.ImageFetchC2S;
import cn.net.rms.confluxmap.core.net.image.ImageMissingS2C;
import cn.net.rms.confluxmap.core.net.image.ImageUploadBeginC2S;
import cn.net.rms.confluxmap.core.net.image.ImageUploadChunkC2S;
import cn.net.rms.confluxmap.core.net.image.ImageUploadResultS2C;
import cn.net.rms.confluxmap.core.net.image.WaypointImageCodec;
import cn.net.rms.confluxmap.core.net.image.WaypointImageMessage;
import cn.net.rms.confluxmap.core.net.image.WaypointImageProto;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointProtocolException;
import cn.net.rms.confluxmap.core.waypoint.image.ImageScale;
import cn.net.rms.confluxmap.core.waypoint.image.WaypointImageEncode;
import cn.net.rms.confluxmap.core.waypoint.image.WaypointImageStore;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Client side of the waypoint-image channel: owns the local image store, the
 * thumbnail texture cache, the decode/encode worker, and the chunked upload
 * path. Everything heavy runs on the single worker; the render thread only
 * uploads textures and draws. Fetched shared images stay memory-only - they
 * never enter the local store, which keeps the owner semantics and the local
 * GC story clean.
 */
public final class WaypointImageClient {
    public static final Identifier CHANNEL = Ids.of(WaypointImageProto.CHANNEL_ID);
    private static final Logger LOGGER = LogManager.getLogger("WaypointImageClient");
    private static final int LRU_CAPACITY = 16;
    private static final int MAX_PENDING_FETCHES = 32;
    static final int UPLOAD_CHUNK_BYTES = 16 * 1024;
    private static final long PENDING_UPLOAD_TIMEOUT_MS = 60_000;

    /** A resident thumbnail texture; drawn through the shared texture-bind seam. */
    public record Thumbnail(int width, int height, NativeImageBackedTexture texture) {
    }

    private record QueuedTexture(String imageId, int width, int height, int[] argb) {
    }

    private final MinecraftClient client;
    private final Supplier<Path> imagesDir;
    private final IntSupplier negotiatedMinor;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "confluxmap-waypoint-images");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, NativeImageBackedTexture> textures =
        new LinkedHashMap<>(16, 0.75f, true);
    private final Map<String, QueuedTexture> completed = new ConcurrentHashMap<>();
    private final FetchTracker fetchTracker = new FetchTracker();
    private final Map<String, PendingUpload> pendingUploads = new ConcurrentHashMap<>();
    private final Set<String> refusedUploads = ConcurrentHashMap.newKeySet();
    private final AtomicLong generation = new AtomicLong();
    private volatile WaypointImageStore store;
    private volatile Path storeDir;

    public WaypointImageClient(
        final MinecraftClient client,
        final Supplier<Path> imagesDir,
        final IntSupplier negotiatedMinor
    ) {
        this.client = client;
        this.imagesDir = imagesDir;
        this.negotiatedMinor = negotiatedMinor;
    }

    public void register() {
        PlayNetworking.registerClient(CHANNEL, (client, handler, payload) -> onReceive(client, payload));
    }

    /** Rebinds the store when the world session changes; drops the texture cache. */
    public void onSessionChanged() {
        final Path dir = imagesDir.get();
        generation.incrementAndGet();
        fetchTracker.reset();
        refusedUploads.clear();
        synchronized (completed) {
            completed.clear();
        }
        client.execute(() -> {
            synchronized (textures) {
                textures.values().forEach(NativeImageBackedTexture::close);
                textures.clear();
            }
        });
        if (dir == null) {
            store = null;
            storeDir = null;
            return;
        }
        if (dir.equals(storeDir)) {
            return;
        }
        storeDir = dir;
        store = new WaypointImageStore(dir);
    }

    /**
     * Returns the thumbnail when resident; a miss starts a fetch (store first,
     * then the channel) and returns empty so callers draw their placeholder.
     */
    public Optional<Thumbnail> thumbnail(final String imageId) {
        if (imageId == null || imageId.isEmpty()) {
            return Optional.empty();
        }
        synchronized (textures) {
            final NativeImageBackedTexture texture = textures.get(imageId);
            if (texture != null) {
                final int width = texture.getImage() != null ? texture.getImage().getWidth() : 0;
                final int height = texture.getImage() != null ? texture.getImage().getHeight() : 0;
                return Optional.of(new Thumbnail(width, height, texture));
            }
        }
        ensureFetched(List.of(imageId));
        return Optional.empty();
    }

    /** Fetches every missing image id, bounded per call; shared images stay memory-only. */
    public void ensureFetched(final Collection<String> imageIds) {
        if (!imagesSupported()) {
            return;
        }
        final List<String> missing = new ArrayList<>();
        for (final String imageId : imageIds) {
            if (imageId == null || imageId.isEmpty() || fetchTracker.pending(imageId)) {
                continue;
            }
            synchronized (textures) {
                if (textures.containsKey(imageId)) {
                    continue;
                }
            }
            missing.add(imageId);
        }
        for (final String imageId : missing.subList(
            0, Math.min(missing.size(), MAX_PENDING_FETCHES - fetchTracker.size())
        )) {
            fetchTracker.start(imageId);
            final long taskGeneration = generation.get();
            final WaypointImageStore currentStore = store;
            try {
                worker.execute(() -> readOrFetch(taskGeneration, imageId, currentStore));
            } catch (final RejectedExecutionException e) {
                fetchTracker.complete(imageId);
            }
        }
    }

    /**
     * Encodes an in-memory draft screenshot and uploads it chunked. The
     * callback receives the server status (or -1 when the channel or the
     * encode cannot serve the upload) on the client main thread; every non-OK
     * outcome is recorded, and {@link #uploadRefused} lets editors drop the
     * draft reference instead of publishing a dangling id.
     */
    public void uploadDraftImage(
        final UUID imageId,
        final int[] argb,
        final int width,
        final int height,
        final IntConsumer onStatus
    ) {
        if (!imagesSupported() || argb.length != width * height) {
            completeUpload(imageId, onStatus, -1);
            return;
        }
        final long taskGeneration = generation.get();
        final Path scratch = imagesDir.get();
        try {
            worker.execute(() -> encodeAndUpload(
                taskGeneration, imageId, new ImageScale.Raster(argb, width, height), scratch, onStatus
            ));
        } catch (final RejectedExecutionException e) {
            completeUpload(imageId, onStatus, -1);
        }
    }

    /** True when this imageId's most recent upload attempt failed or was refused. */
    public boolean uploadRefused(final String imageId) {
        return refusedUploads.contains(imageId);
    }

    /**
     * Reads an image off the system clipboard on the worker (X11 clipboard
     * roundtrips can block for seconds) and delivers ARGB pixels on the main
     * thread; a failure delivers 0x0. Flavor order: image/png first, then the
     * AWT image flavor.
     */
    public void pasteFromClipboard(final PixelsConsumer onPixels) {
        final long taskGeneration = generation.get();
        try {
            worker.execute(() -> {
                if (taskGeneration != generation.get()) {
                    return;
                }
                final ClipboardPixels pixels = readClipboard();
                client.execute(() -> {
                    if (pixels == null) {
                        onPixels.accept(0, 0, null);
                    } else {
                        onPixels.accept(pixels.width, pixels.height, pixels.argb);
                    }
                });
            });
        } catch (final RejectedExecutionException e) {
            client.execute(() -> onPixels.accept(0, 0, null));
        }
    }

    @FunctionalInterface
    public interface PixelsConsumer {
        void accept(int width, int height, int[] argb);
    }

    private record ClipboardPixels(int width, int height, int[] argb) {
    }

    private static ClipboardPixels readClipboard() {
        try {
            final java.awt.datatransfer.Clipboard clipboard =
                java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
            final java.awt.datatransfer.Transferable contents = clipboard.getContents(null);
            if (contents == null) {
                return null;
            }
            if (contents.isDataFlavorSupported(new java.awt.datatransfer.DataFlavor("image/png"))) {
                final byte[] png = (byte[]) contents.getTransferData(
                    new java.awt.datatransfer.DataFlavor("image/png")
                );
                try (NativeImage image = NativeImage.read(new ByteArrayInputStream(png))) {
                    return new ClipboardPixels(image.getWidth(), image.getHeight(), pixels(image));
                }
            }
            if (contents.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.imageFlavor)) {
                final java.awt.Image awtImage = (java.awt.Image) contents.getTransferData(
                    java.awt.datatransfer.DataFlavor.imageFlavor
                );
                final java.awt.image.BufferedImage buffered = toBuffered(awtImage);
                if (buffered == null) {
                    return null;
                }
                return new ClipboardPixels(
                    buffered.getWidth(), buffered.getHeight(), argbOf(buffered)
                );
            }
            return null;
        } catch (final Exception e) {
            LOGGER.warn("waypoint-image: clipboard read failed", e);
            return null;
        }
    }

    private static java.awt.image.BufferedImage toBuffered(final java.awt.Image image) {
        if (image == null) {
            return null;
        }
        final int width = image.getWidth(null);
        final int height = image.getHeight(null);
        if (width <= 0 || height <= 0) {
            return null;
        }
        final java.awt.image.BufferedImage buffered = new java.awt.image.BufferedImage(
            width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB
        );
        final java.awt.Graphics2D graphics = buffered.createGraphics();
        try {
            graphics.drawImage(image, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return buffered;
    }

    private static int[] argbOf(final java.awt.image.BufferedImage image) {
        final int[] argb = new int[image.getWidth() * image.getHeight()];
        image.getRGB(0, 0, image.getWidth(), image.getHeight(), argb, 0, image.getWidth());
        return argb;
    }

    /** Puts locally produced pixels straight into the texture cache (drafts). */
    public void seedDraftTexture(final String imageId, final int width, final int height, final int[] argb) {
        synchronized (completed) {
            completed.put(imageId, new QueuedTexture(imageId, width, height, argb));
        }
    }

    /** Releases evicted textures on the render thread and drains worker completions. */
    public void poll() {
        final List<QueuedTexture> ready = new ArrayList<>();
        synchronized (completed) {
            ready.addAll(completed.values());
            completed.clear();
        }
        for (final QueuedTexture queued : ready) {
            buildTexture(queued);
        }
        final long now = System.currentTimeMillis();
        for (final PendingUpload pending : pendingUploads.values()) {
            if (now - pending.startedEpochMs > PENDING_UPLOAD_TIMEOUT_MS) {
                completeUpload(pending.imageId, pending.onStatus, -1);
            }
        }
    }

    private boolean imagesSupported() {
        return negotiatedMinor.getAsInt()
            >= cn.net.rms.confluxmap.core.net.shared.SharedWaypointProto.PROTO_MINOR_WAYPOINT_DESCRIPTION;
    }

    private void readOrFetch(final long taskGeneration, final String imageId, final WaypointImageStore currentStore) {
        if (taskGeneration != generation.get()) {
            fetchTracker.complete(imageId);
            return;
        }
        if (currentStore != null) {
            final Optional<StoredImageBytes> local = readLocal(currentStore, imageId);
            if (local.isPresent()) {
                // Delivered: the id is restartable once the texture is evicted.
                fetchTracker.complete(imageId);
                queueTexture(imageId, local.get().argb, local.get().width, local.get().height);
                return;
            }
        }
        try {
            PlayNetworking.sendClient(CHANNEL, WaypointImageCodec.encodeC2S(
                new ImageFetchC2S(UUID.fromString(imageId))
            ));
        } catch (final SharedWaypointProtocolException | IllegalArgumentException e) {
            LOGGER.warn("waypoint-image: cannot request {}", imageId, e);
            fetchTracker.complete(imageId);
        }
    }

    private record StoredImageBytes(int width, int height, int[] argb) {
    }

    private Optional<StoredImageBytes> readLocal(final WaypointImageStore imageStore, final String imageId) {
        final UUID uuid;
        try {
            uuid = UUID.fromString(imageId);
        } catch (final IllegalArgumentException e) {
            return Optional.empty();
        }
        return imageStore.load(uuid).map(image -> {
            final int[] argb = decode(image.png(), image.width(), image.height());
            return argb == null ? null : new StoredImageBytes(image.width(), image.height(), argb);
        });
    }

    private void encodeAndUpload(
        final long taskGeneration,
        final UUID imageId,
        final ImageScale.Raster raster,
        final Path scratch,
        final IntConsumer onStatus
    ) {
        if (taskGeneration != generation.get() || scratch == null) {
            completeUpload(imageId, onStatus, -1);
            return;
        }
        final Optional<WaypointImageEncode.Encoded> encoded;
        try {
            encoded = WaypointImageEncode.encodeWithin(
                raster, scratch, WaypointImageEncode.MAX_EDGE_START, WaypointImageEncode.TARGET_BYTES
            );
        } catch (final IOException e) {
            LOGGER.warn("waypoint-image: encode failed for {}", imageId, e);
            completeUpload(imageId, onStatus, -1);
            return;
        }
        if (encoded.isEmpty()) {
            completeUpload(imageId, onStatus, WaypointImageProto.STATUS_TOO_LARGE);
            return;
        }
        final WaypointImageEncode.Encoded result = encoded.get();
        final List<byte[]> chunks = chunk(result.png());
        client.execute(() -> {
            if (taskGeneration != generation.get()) {
                completeUpload(imageId, onStatus, -1);
                return;
            }
            pendingUploads.put(imageId.toString(), new PendingUpload(imageId, onStatus, chunks));
            try {
                PlayNetworking.sendClient(CHANNEL, WaypointImageCodec.encodeC2S(new ImageUploadBeginC2S(
                    imageId, WaypointImageProto.FORMAT_PNG, result.width(), result.height(), result.png().length
                )));
            } catch (final SharedWaypointProtocolException e) {
                completeUpload(imageId, onStatus, -1);
            }
        });
    }

    /**
     * Ends one upload: non-OK outcomes are recorded (editors drop the draft
     * reference rather than publish a dangling id) and the callback fires on
     * the client main thread.
     */
    private void completeUpload(final UUID imageId, final IntConsumer onStatus, final int status) {
        pendingUploads.remove(imageId.toString());
        if (status != WaypointImageProto.STATUS_OK) {
            refusedUploads.add(imageId.toString());
        }
        client.execute(() -> onStatus.accept(status));
    }

    static List<byte[]> chunk(final byte[] payload) {
        final List<byte[]> chunks = new ArrayList<>();
        for (int from = 0; from < payload.length; from += UPLOAD_CHUNK_BYTES) {
            final int length = Math.min(UPLOAD_CHUNK_BYTES, payload.length - from);
            chunks.add(java.util.Arrays.copyOfRange(payload, from, from + length));
        }
        return chunks;
    }

    private void sendChunk(final PendingUpload pending, final int seq) {
        pending.sentChunks = seq + 1;
        try {
            PlayNetworking.sendClient(CHANNEL, WaypointImageCodec.encodeC2S(
                new ImageUploadChunkC2S(pending.imageId, seq, pending.chunks.get(seq))
            ));
        } catch (final SharedWaypointProtocolException e) {
            completeUpload(pending.imageId, pending.onStatus, -1);
        }
    }

    private void onReceive(final MinecraftClient currentClient, final byte[] payload) {
        final WaypointImageMessage.S2C message;
        try {
            message = WaypointImageCodec.decodeS2C(payload);
        } catch (final SharedWaypointProtocolException | RuntimeException e) {
            LOGGER.warn("waypoint-image: dropping malformed S2C payload ({})", e.getMessage());
            return;
        }
        if (message instanceof final ImageDataS2C data) {
            final String imageId = data.imageId().toString();
            final byte[] png = data.bytes();
            fetchTracker.complete(imageId);
            final long taskGeneration = generation.get();
            try {
                worker.execute(() -> {
                    if (taskGeneration != generation.get()) {
                        return;
                    }
                    // Real dimensions live in the PNG header; decode derives them.
                    try (NativeImage image = NativeImage.read(new ByteArrayInputStream(png))) {
                        queueTexture(imageId, pixels(image), image.getWidth(), image.getHeight());
                    } catch (final IOException e) {
                        LOGGER.warn("waypoint-image: undecodable fetched image", e);
                    }
                });
            } catch (final RejectedExecutionException ignored) {
                // Shutdown race; the next hover re-requests.
            }
        } else if (message instanceof final ImageMissingS2C missing) {
            fetchTracker.complete(missing.imageId().toString());
        } else if (message instanceof final ImageUploadResultS2C result) {
            final PendingUpload pending = pendingUploads.get(result.imageId().toString());
            if (pending == null) {
                return;
            }
            if (result.status() != WaypointImageProto.STATUS_OK) {
                // A refused upload ends the attempt; the UI drops the reference.
                completeUpload(result.imageId(), pending.onStatus, result.status());
                return;
            }
            if (pending.sentChunks == 0) {
                // BEGIN acknowledged: stream every chunk; the completing chunk
                // elicits the second OK that finishes the callback.
                for (int seq = 0; seq < pending.chunks.size(); seq++) {
                    sendChunk(pending, seq);
                }
            } else {
                completeUpload(result.imageId(), pending.onStatus, WaypointImageProto.STATUS_OK);
            }
        }
    }

    private void queueTexture(final String imageId, final int[] argb, final int width, final int height) {
        if (argb == null) {
            return;
        }
        synchronized (completed) {
            completed.put(imageId, new QueuedTexture(imageId, width, height, argb));
        }
    }

    private void buildTexture(final QueuedTexture queued) {
        final NativeImage image = new NativeImage(queued.width(), queued.height(), false);
        try {
            for (int y = 0; y < queued.height(); y++) {
                for (int x = 0; x < queued.width(); x++) {
                    NativeImages.setArgb(image, x, y, queued.argb()[y * queued.width() + x]);
                }
            }
        } catch (final RuntimeException e) {
            image.close();
            return;
        }
        //#if MC>=12105
        //$$ final NativeImageBackedTexture texture =
        //$$     new NativeImageBackedTexture(() -> "Conflux Map waypoint thumbnail", image);
        //#else
        final NativeImageBackedTexture texture = new NativeImageBackedTexture(image);
        //#endif
        texture.upload();
        synchronized (textures) {
            final NativeImageBackedTexture evicted = textures.put(queued.imageId(), texture);
            while (textures.size() > LRU_CAPACITY) {
                final String eldest = textures.keySet().iterator().next();
                final NativeImageBackedTexture removed = textures.remove(eldest);
                if (removed != null && removed != texture) {
                    removed.close();
                }
            }
            if (evicted != null && evicted != texture && !textures.containsValue(evicted)) {
                evicted.close();
            }
        }
    }

    private static int[] pixels(final NativeImage image) {
        final int width = image.getWidth();
        final int height = image.getHeight();
        final int[] argb = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                argb[y * width + x] = NativeImages.getArgb(image, x, y);
            }
        }
        return argb;
    }

    private static int[] decode(final byte[] png, final int width, final int height) {
        // The PNG's own header wins over the container metadata; the bytes are
        // the ground truth either way.
        try {
            final NativeImage image = NativeImage.read(new ByteArrayInputStream(png));
            try {
                return pixels(image);
            } finally {
                image.close();
            }
        } catch (final IOException | RuntimeException e) {
            LOGGER.warn("waypoint-image: undecodable image ({}x{})", width, height, e);
            return null;
        }
    }

    /**
     * In-flight fetch bookkeeping. Every exit path (delivered, failed,
     * session reset, generation kill) must complete its entry or the id can
     * never be requested again once its texture is evicted.
     */
    static final class FetchTracker {
        private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

        boolean start(final String imageId) {
            return inFlight.add(imageId);
        }

        void complete(final String imageId) {
            inFlight.remove(imageId);
        }

        void reset() {
            inFlight.clear();
        }

        boolean pending(final String imageId) {
            return inFlight.contains(imageId);
        }

        int size() {
            return inFlight.size();
        }
    }

    private static final class PendingUpload {
        private final UUID imageId;
        private final IntConsumer onStatus;
        private final List<byte[]> chunks;
        private int sentChunks;
        private final long startedEpochMs = System.currentTimeMillis();

        private PendingUpload(final UUID imageId, final IntConsumer onStatus, final List<byte[]> chunks) {
            this.imageId = imageId;
            this.onStatus = onStatus;
            this.chunks = chunks;
        }
    }
}
