package cn.net.rms.confluxmap.core.cache;

import cn.net.rms.confluxmap.core.color.ShadingPipeline;
import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.SampleSource;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.store.RegionColumns;
import cn.net.rms.confluxmap.core.util.Argb;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.logging.log4j.Logger;

/** Builds a bounded north-up thumbnail directly from persisted {@code .cfr} region history. */
public final class RegionHistoryPreviewLoader {
    private static final Pattern REGION_FILE = Pattern.compile("^r\\.(-?\\d+)\\.(-?\\d+)\\.cfr$");
    private static final int PREVIEW_PADDING = 8;
    private static final double MIN_BLOCKS_PER_PIXEL = 0.25D;
    private static final int MAX_PIXELS = 1_048_576;
    static final int MAX_DECODED_REGIONS = 2_048;

    public record Bounds(long minX, long minZ, long maxX, long maxZ) {
        public Bounds {
            if (minX > maxX || minZ > maxZ) {
                throw new IllegalArgumentException("Preview bounds must be normalized");
            }
        }

        long blockWidth() {
            return maxX - minX + 1L;
        }

        long blockHeight() {
            return maxZ - minZ + 1L;
        }
    }

    public record Fit(double minWorldX, double minWorldZ, double blocksPerPixel) {
        public Fit {
            if (!Double.isFinite(minWorldX) || !Double.isFinite(minWorldZ)
                || !Double.isFinite(blocksPerPixel) || blocksPerPixel <= 0.0D) {
                throw new IllegalArgumentException("Invalid preview fit");
            }
        }

        public double pixelX(final double worldX) {
            return (worldX - minWorldX) / blocksPerPixel;
        }

        public double pixelZ(final double worldZ) {
            return (worldZ - minWorldZ) / blocksPerPixel;
        }
    }

    public record Result(
        int width,
        int height,
        int[] argb,
        Bounds exploredBounds,
        Fit fit,
        int exploredChunks,
        int regionFiles,
        int decodedRegions,
        boolean sampled
    ) {
        public Result {
            if (width <= 0 || height <= 0 || argb.length != width * height) {
                throw new IllegalArgumentException("Invalid preview raster dimensions");
            }
        }

        public boolean hasHistory() {
            return exploredBounds != null && fit != null && exploredChunks > 0;
        }
    }

    record RegionEntry(
        Path path,
        int rx,
        int rz,
        RegionFileCodec.RegionMetadata metadata,
        int exploredChunks,
        int minLocalChunkX,
        int minLocalChunkZ,
        int maxLocalChunkX,
        int maxLocalChunkZ
    ) {
        long minBlockX() {
            return (long) rx * RegionColumns.SIZE + (long) minLocalChunkX * 16L;
        }

        long minBlockZ() {
            return (long) rz * RegionColumns.SIZE + (long) minLocalChunkZ * 16L;
        }

        long maxBlockX() {
            return (long) rx * RegionColumns.SIZE + (long) (maxLocalChunkX + 1) * 16L - 1L;
        }

        long maxBlockZ() {
            return (long) rz * RegionColumns.SIZE + (long) (maxLocalChunkZ + 1) * 16L - 1L;
        }
    }

    private RegionHistoryPreviewLoader() {
    }

    public static Result load(
        final Path layerDirectory,
        final MapLayer.Type layerType,
        final int width,
        final int height,
        final BooleanSupplier cancelled,
        final Logger logger
    ) throws IOException {
        validateDimensions(width, height);
        if (!Files.isDirectory(layerDirectory)) {
            return empty(width, height, 0);
        }

        final List<RegionEntry> entries = scan(layerDirectory, layerType, cancelled, logger);
        if (entries.isEmpty()) {
            return empty(width, height, 0);
        }
        final Bounds bounds = bounds(entries);
        final Fit fit = fit(bounds, width, height);
        final List<RegionEntry> selected = select(entries, fit, width, height);
        final int[] pixels = new int[width * height];
        final int[] updateSeconds = new int[pixels.length];
        int decoded = 0;
        for (final RegionEntry entry : selected) {
            checkCancelled(cancelled);
            try (InputStream in = new BufferedInputStream(Files.newInputStream(entry.path()))) {
                final RegionFileCodec.RegionData data = RegionFileCodec.decode(
                    in, entry.rx(), entry.rz(), layerType.ordinal()
                );
                paint(data, fit, width, height, pixels, updateSeconds, cancelled);
                decoded++;
            } catch (final RegionFileCodec.RegionFileException | IOException error) {
                logger.debug("Skipping unreadable client-world preview region {} ({})", entry.path(), error.toString());
            }
        }
        final int exploredChunks = entries.stream().mapToInt(RegionEntry::exploredChunks).sum();
        return new Result(
            width, height, pixels, bounds, fit, exploredChunks, entries.size(), decoded,
            selected.size() < entries.size()
        );
    }

    static Fit fit(final Bounds bounds, final int width, final int height) {
        validateDimensions(width, height);
        final int contentWidth = Math.max(1, width - PREVIEW_PADDING * 2);
        final int contentHeight = Math.max(1, height - PREVIEW_PADDING * 2);
        final double scale = Math.max(
            MIN_BLOCKS_PER_PIXEL,
            Math.max(bounds.blockWidth() / (double) contentWidth, bounds.blockHeight() / (double) contentHeight)
        );
        final double centerX = bounds.minX() + bounds.blockWidth() / 2.0D;
        final double centerZ = bounds.minZ() + bounds.blockHeight() / 2.0D;
        return new Fit(centerX - width * scale / 2.0D, centerZ - height * scale / 2.0D, scale);
    }

    private static List<RegionEntry> scan(
        final Path directory,
        final MapLayer.Type layerType,
        final BooleanSupplier cancelled,
        final Logger logger
    ) throws IOException {
        final List<Path> files;
        try (Stream<Path> stream = Files.list(directory)) {
            files = stream.filter(Files::isRegularFile)
                .filter(path -> REGION_FILE.matcher(path.getFileName().toString()).matches())
                .sorted()
                .toList();
        }
        final List<RegionEntry> entries = new ArrayList<>(files.size());
        for (final Path file : files) {
            checkCancelled(cancelled);
            final Matcher matcher = REGION_FILE.matcher(file.getFileName().toString());
            if (!matcher.matches()) {
                continue;
            }
            try {
                final int rx = Integer.parseInt(matcher.group(1));
                final int rz = Integer.parseInt(matcher.group(2));
                try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
                    final RegionFileCodec.RegionMetadata metadata = RegionFileCodec.decodeMetadata(
                        in, rx, rz, layerType.ordinal()
                    );
                    final RegionEntry entry = entry(file, rx, rz, metadata);
                    if (entry != null) {
                        entries.add(entry);
                    }
                }
            } catch (final NumberFormatException | RegionFileCodec.RegionFileException | IOException error) {
                logger.debug("Skipping unreadable client-world preview metadata {} ({})", file, error.toString());
            }
        }
        return entries;
    }

    private static RegionEntry entry(
        final Path path,
        final int rx,
        final int rz,
        final RegionFileCodec.RegionMetadata metadata
    ) {
        int count = 0;
        int minX = RegionColumns.CHUNKS;
        int minZ = RegionColumns.CHUNKS;
        int maxX = -1;
        int maxZ = -1;
        for (int index = 0; index < metadata.chunkSourceOrdinal().length; index++) {
            if (SampleSource.byOrdinal(metadata.chunkSourceOrdinal()[index]) == SampleSource.UNKNOWN) {
                continue;
            }
            final int localX = index % RegionColumns.CHUNKS;
            final int localZ = index / RegionColumns.CHUNKS;
            minX = Math.min(minX, localX);
            minZ = Math.min(minZ, localZ);
            maxX = Math.max(maxX, localX);
            maxZ = Math.max(maxZ, localZ);
            count++;
        }
        return count == 0 ? null : new RegionEntry(path, rx, rz, metadata, count, minX, minZ, maxX, maxZ);
    }

    private static Bounds bounds(final List<RegionEntry> entries) {
        long minX = Long.MAX_VALUE;
        long minZ = Long.MAX_VALUE;
        long maxX = Long.MIN_VALUE;
        long maxZ = Long.MIN_VALUE;
        for (final RegionEntry entry : entries) {
            minX = Math.min(minX, entry.minBlockX());
            minZ = Math.min(minZ, entry.minBlockZ());
            maxX = Math.max(maxX, entry.maxBlockX());
            maxZ = Math.max(maxZ, entry.maxBlockZ());
        }
        return new Bounds(minX, minZ, maxX, maxZ);
    }

    /**
     * Very large histories are sampled by screen-space region cell before body inflation. Metadata
     * for every file still contributes to the fit, so the thumbnail always keeps the full explored
     * extent while bounding decompression and CPU work.
     */
    static List<RegionEntry> select(
        final List<RegionEntry> entries,
        final Fit fit,
        final int width,
        final int height
    ) {
        if (entries.size() <= MAX_DECODED_REGIONS) {
            return entries;
        }
        final Map<Long, RegionEntry> newestByCell = new LinkedHashMap<>();
        for (final RegionEntry entry : entries) {
            final int x = clamp((int) Math.floor(fit.pixelX((entry.minBlockX() + entry.maxBlockX()) / 2.0D)), 0, width - 1);
            final int z = clamp((int) Math.floor(fit.pixelZ((entry.minBlockZ() + entry.maxBlockZ()) / 2.0D)), 0, height - 1);
            final long key = (long) z * width + x;
            newestByCell.merge(key, entry, (left, right) ->
                left.metadata().lastWriteEpochMs() >= right.metadata().lastWriteEpochMs() ? left : right
            );
        }
        final List<RegionEntry> selected = new ArrayList<>(newestByCell.values());
        selected.sort(Comparator.comparingLong(entry -> -entry.metadata().lastWriteEpochMs()));
        if (selected.size() > MAX_DECODED_REGIONS) {
            return new ArrayList<>(selected.subList(0, MAX_DECODED_REGIONS));
        }
        final Set<RegionEntry> selectedEntries = new HashSet<>(selected);
        for (final RegionEntry entry : entries) {
            if (selected.size() == MAX_DECODED_REGIONS) {
                break;
            }
            if (selectedEntries.add(entry)) {
                selected.add(entry);
            }
        }
        return selected;
    }

    private static void paint(
        final RegionFileCodec.RegionData data,
        final Fit fit,
        final int width,
        final int height,
        final int[] pixels,
        final int[] updateSeconds,
        final BooleanSupplier cancelled
    ) {
        for (int chunkIndex = 0; chunkIndex < data.chunkSourceOrdinal().length; chunkIndex++) {
            checkCancelled(cancelled);
            if (SampleSource.byOrdinal(data.chunkSourceOrdinal()[chunkIndex]) == SampleSource.UNKNOWN) {
                continue;
            }
            final int chunkX = chunkIndex % RegionColumns.CHUNKS;
            final int chunkZ = chunkIndex / RegionColumns.CHUNKS;
            paintChunk(
                data, chunkX, chunkZ, data.chunkUpdateEpochSeconds()[chunkIndex],
                fit, width, height, pixels, updateSeconds
            );
        }
    }

    private static void paintChunk(
        final RegionFileCodec.RegionData data,
        final int chunkX,
        final int chunkZ,
        final int chunkUpdateSeconds,
        final Fit fit,
        final int width,
        final int height,
        final int[] pixels,
        final int[] updateSeconds
    ) {
        final long minBlockX = (long) data.rx() * RegionColumns.SIZE + (long) chunkX * 16L;
        final long minBlockZ = (long) data.rz() * RegionColumns.SIZE + (long) chunkZ * 16L;
        final long maxBlockXExclusive = minBlockX + 16L;
        final long maxBlockZExclusive = minBlockZ + 16L;
        final int minPixelX = clamp((int) Math.floor(fit.pixelX(minBlockX)), 0, width - 1);
        final int minPixelZ = clamp((int) Math.floor(fit.pixelZ(minBlockZ)), 0, height - 1);
        final int maxPixelX = clamp((int) Math.ceil(fit.pixelX(maxBlockXExclusive)) - 1, 0, width - 1);
        final int maxPixelZ = clamp((int) Math.ceil(fit.pixelZ(maxBlockZExclusive)) - 1, 0, height - 1);
        if (maxPixelX < minPixelX || maxPixelZ < minPixelZ) {
            return;
        }

        final long regionOriginX = (long) data.rx() * RegionColumns.SIZE;
        final long regionOriginZ = (long) data.rz() * RegionColumns.SIZE;
        for (int pixelZ = minPixelZ; pixelZ <= maxPixelZ; pixelZ++) {
            final long worldZ = clamp(
                (long) Math.floor(fit.minWorldZ() + (pixelZ + 0.5D) * fit.blocksPerPixel()),
                minBlockZ, maxBlockZExclusive - 1L
            );
            final int localZ = (int) (worldZ - regionOriginZ);
            for (int pixelX = minPixelX; pixelX <= maxPixelX; pixelX++) {
                final long worldX = clamp(
                    (long) Math.floor(fit.minWorldX() + (pixelX + 0.5D) * fit.blocksPerPixel()),
                    minBlockX, maxBlockXExclusive - 1L
                );
                final int localX = (int) (worldX - regionOriginX);
                final int target = pixelZ * width + pixelX;
                if (pixels[target] != Argb.TRANSPARENT
                    && Integer.compareUnsigned(chunkUpdateSeconds, updateSeconds[target]) < 0) {
                    continue;
                }
                final int color = color(data, localX, localZ);
                if (color != Argb.TRANSPARENT) {
                    pixels[target] = color;
                    updateSeconds[target] = chunkUpdateSeconds;
                }
            }
        }
    }

    private static int color(final RegionFileCodec.RegionData data, final int localX, final int localZ) {
        final int index = localZ * RegionColumns.SIZE + localX;
        final SurfaceKind kind = SurfaceKind.byOrdinal(data.kind()[index]);
        if (kind == SurfaceKind.UNKNOWN || kind == SurfaceKind.VOID) {
            return Argb.TRANSPARENT;
        }
        Integer neighborHeight = null;
        if (localX > 0 && localZ + 1 < RegionColumns.SIZE) {
            final int neighbor = (localZ + 1) * RegionColumns.SIZE + localX - 1;
            if (data.surfaceY()[neighbor] != ChunkSnapshot.NO_SURFACE) {
                neighborHeight = (int) data.surfaceY()[neighbor];
            }
        }
        final double shade = ShadingPipeline.combinedShade(
            true, true, data.surfaceY()[index], ShadingPipeline.REFERENCE_HEIGHT, neighborHeight
        );
        final int base = ShadingPipeline.applyShade(
            Argb.multiply(data.baseArgb()[index], data.biomeTint()[index]), shade
        );
        final int overlay = data.overlayArgb()[index] == Argb.TRANSPARENT
            ? Argb.TRANSPARENT
            : ShadingPipeline.applyShade(data.overlayArgb()[index], shade);
        return kind == SurfaceKind.WATER || kind == SurfaceKind.ICE
            ? ShadingPipeline.compositeOver(base, overlay)
            : ShadingPipeline.compositeOver(overlay, base);
    }

    private static Result empty(final int width, final int height, final int regionFiles) {
        return new Result(width, height, new int[width * height], null, null, 0, regionFiles, 0, false);
    }

    private static void validateDimensions(final int width, final int height) {
        if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS) {
            throw new IllegalArgumentException("Preview raster exceeds its resource budget");
        }
    }

    private static void checkCancelled(final BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Client-world history preview cancelled");
        }
    }

    private static int clamp(final int value, final int min, final int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long clamp(final long value, final long min, final long max) {
        return Math.max(min, Math.min(max, value));
    }
}
