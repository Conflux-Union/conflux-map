package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.core.net.ProtoException;
import cn.net.rms.confluxmap.core.net.SummaryCodec;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Map;

/** Persistent region-summary cache; corrupt entries are quarantined and treated as cold. */
public final class SummaryDiskCache {
    private final Path root;

    public SummaryDiskCache(final Path worldFolder) {
        this.root = worldFolder.resolve("confluxmap").resolve("summary");
    }

    public synchronized SummaryCodec.Region load(final String dimension, final int regionX, final int regionZ) {
        final Path path = pathFor(dimension, regionX, regionZ);
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            final SummaryCodec.Region region = SummaryCodec.decode(Files.readAllBytes(path));
            if (region.rx() != regionX || region.rz() != regionZ) {
                throw new ProtoException("summary coordinates do not match file name");
            }
            return region;
        } catch (IOException | ProtoException e) {
            quarantine(path);
            return null;
        }
    }

    /** Loads a full or partial summary only when it describes the current region file version. */
    public synchronized SummaryCodec.Region loadCurrent(
        final String dimension,
        final int regionX,
        final int regionZ,
        final long sourceMcaMtimeMs
    ) {
        if (sourceMcaMtimeMs <= 0L) {
            return null;
        }
        final SummaryCodec.Region region = load(dimension, regionX, regionZ);
        return region != null && absoluteMtime(region.sourceMcaMtimeMs()) == sourceMcaMtimeMs
            ? region
            : null;
    }

    /**
     * Loads only the centered columns consumed by the requested LOD when the cache still matches
     * the source region. This avoids materializing every fine column for a coarse tile.
     */
    public synchronized SummaryCodec.SampledRegion loadCurrentSampled(
        final String dimension,
        final int regionX,
        final int regionZ,
        final long sourceMcaMtimeMs,
        final int lod
    ) {
        if (sourceMcaMtimeMs <= 0L) {
            return null;
        }
        if (lod < 0 || lod > 4) {
            throw new IllegalArgumentException("unsupported summary LOD " + lod);
        }
        final Path path = pathFor(dimension, regionX, regionZ);
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try (InputStream in = new BufferedInputStream(Files.newInputStream(path))) {
            final SummaryCodec.SampledRegion region = SummaryCodec.decodeSampled(in, 1 << lod);
            if (region.rx() != regionX || region.rz() != regionZ) {
                throw new ProtoException("summary coordinates do not match file name");
            }
            return absoluteMtime(region.sourceMcaMtimeMs()) == sourceMcaMtimeMs ? region : null;
        } catch (IOException | ProtoException e) {
            quarantine(path);
            return null;
        }
    }

    /**
     * Loads only a region's chunk-generation flags, reading the fixed-size header and stopping
     * before the deflated column body. Used by coarse presence answers, which span far too many
     * regions to decode in full.
     *
     * <p>A partial summary written by {@link #saveLiveChunks} answers here too, and reports only
     * the slots captured so far. Under-reporting presence is the same tolerance the coarse path
     * already accepts for a stale summary; over-reporting is what it must never do.
     */
    public synchronized SummaryCodec.Generated loadGenerated(final String dimension, final int regionX, final int regionZ) {
        final Path path = pathFor(dimension, regionX, regionZ);
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try (InputStream in = new BufferedInputStream(Files.newInputStream(path))) {
            final SummaryCodec.Generated generated = SummaryCodec.decodeGenerated(in);
            if (generated.rx() != regionX || generated.rz() != regionZ) {
                throw new ProtoException("summary coordinates do not match file name");
            }
            return generated;
        } catch (IOException | ProtoException e) {
            quarantine(path);
            return null;
        }
    }

    public synchronized void save(final String dimension, final SummaryCodec.Region region) throws IOException {
        final Path path = pathFor(dimension, region.rx(), region.rz());
        Files.createDirectories(path.getParent());
        final Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.write(tmp, SummaryCodec.encode(region));
        try {
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Persists one live chunk without pretending the other 255 slots were checked.
     *
     * <p>A negative source mtime marks a partial region. Slots from a previous snapshot are kept
     * only while they describe the same underlying {@code .mca} file version; a changed mtime
     * conservatively drops them. {@link RegionSummaryService} fills empty partial slots from NBT.
     */
    public synchronized void saveLiveChunk(
        final String dimension,
        final int chunkX,
        final int chunkZ,
        final long sourceMcaMtimeMs,
        final SummaryCodec.Chunk chunk
    ) throws IOException {
        if (chunk == null) {
            return;
        }
        final int regionX = Math.floorDiv(chunkX, 16);
        final int regionZ = Math.floorDiv(chunkZ, 16);
        final int localX = Math.floorMod(chunkX, 16);
        final int localZ = Math.floorMod(chunkZ, 16);
        saveLiveChunks(
            dimension,
            regionX,
            regionZ,
            sourceMcaMtimeMs,
            Map.of(localZ * 16 + localX, chunk)
        );
    }

    /** Atomically merges several live chunk slots belonging to one level-0 region. */
    public synchronized void saveLiveChunks(
        final String dimension,
        final int regionX,
        final int regionZ,
        final long sourceMcaMtimeMs,
        final Map<Integer, SummaryCodec.Chunk> updates
    ) throws IOException {
        if (sourceMcaMtimeMs <= 0L || updates == null || updates.isEmpty()) {
            return;
        }
        final SummaryCodec.Region existing = load(dimension, regionX, regionZ);
        final SummaryCodec.Chunk[] chunks = new SummaryCodec.Chunk[SummaryCodec.CHUNKS];
        final long existingMtime = existing == null ? 0L : absoluteMtime(existing.sourceMcaMtimeMs());
        if (existing != null && existingMtime == sourceMcaMtimeMs) {
            System.arraycopy(existing.chunks(), 0, chunks, 0, chunks.length);
        } else {
            Arrays.fill(chunks, SummaryCodec.Chunk.empty());
        }
        for (final Map.Entry<Integer, SummaryCodec.Chunk> update : updates.entrySet()) {
            final int index = update.getKey();
            final SummaryCodec.Chunk chunk = update.getValue();
            if (index >= 0 && index < chunks.length && chunk != null && chunk.generated()) {
                chunks[index] = chunk;
            }
        }
        save(dimension, new SummaryCodec.Region(regionX, regionZ, -sourceMcaMtimeMs, chunks));
    }

    public boolean isStale(final String dimension, final int regionX, final int regionZ, final long sourceMcaMtimeMs) {
        final SummaryCodec.Region region = load(dimension, regionX, regionZ);
        return sourceMcaMtimeMs <= 0L || region == null
            || region.sourceMcaMtimeMs() <= 0L
            || sourceMcaMtimeMs != region.sourceMcaMtimeMs();
    }

    private static long absoluteMtime(final long value) {
        return value == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(value);
    }

    private Path pathFor(final String dimension, final int x, final int z) {
        return root.resolve(sanitize(dimension)).resolve("r." + x + "." + z + ".cfs");
    }

    private static String sanitize(final String value) {
        final String safe = value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.startsWith(".") ? "_" + safe.replaceFirst("^\\.+", "") : safe;
    }

    private static void quarantine(final Path path) {
        try {
            Files.move(path, path.resolveSibling(path.getFileName() + ".bad"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // Cache corruption must not affect world loading.
        }
    }
}
