package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.core.net.SummaryCodec;
import cn.net.rms.confluxmap.core.util.ChunkRegionSlice;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** In-memory summaries for chunks whose authoritative state is the live {@code WorldChunk}. */
public final class LiveChunkSummaryCache {
    private final Map<Key, SummaryCodec.Chunk> chunks = new ConcurrentHashMap<>();
    private final Map<RegionKey, Long> regionEpochs = new ConcurrentHashMap<>();
    private final AtomicLong nextEpoch = new AtomicLong();

    public boolean put(
        final String dimension,
        final int chunkX,
        final int chunkZ,
        final SummaryCodec.Chunk summary
    ) {
        if (summary == null) {
            return false;
        }
        final Key key = new Key(dimension, chunkX, chunkZ);
        final boolean[] changed = {false};
        chunks.compute(key, (ignored, previous) -> {
            final SummaryCodec.Chunk next = revised(previous, summary);
            if (next != previous) {
                touchRegion(key.dimension(), chunkX, chunkZ);
                changed[0] = true;
            }
            return next;
        });
        return changed[0];
    }

    public SummaryCodec.Chunk get(final String dimension, final int chunkX, final int chunkZ) {
        return chunks.get(new Key(dimension, chunkX, chunkZ));
    }

    public boolean remove(final String dimension, final int chunkX, final int chunkZ) {
        if (chunks.remove(new Key(dimension, chunkX, chunkZ)) != null) {
            touchRegion(dimension, chunkX, chunkZ);
            return true;
        }
        return false;
    }

    public void clear() {
        chunks.clear();
        regionEpochs.clear();
    }

    /** Monotonic live-summary version for one region; unchanged regions keep the same value. */
    public long regionEpoch(final String dimension, final int regionX, final int regionZ) {
        return regionEpochs.getOrDefault(new RegionKey(dimension, regionX, regionZ), 0L);
    }

    /** Returns {@code region} with every currently-live chunk slot replaced by its live summary. */
    public SummaryCodec.Region overlay(final String dimension, final SummaryCodec.Region region) {
        final SummaryCodec.Chunk[] merged = region.chunks().clone();
        boolean changed = false;
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                final SummaryCodec.Chunk live = get(
                    dimension,
                    region.rx() * 16 + localX,
                    region.rz() * 16 + localZ
                );
                if (live != null) {
                    merged[localZ * 16 + localX] = live;
                    changed = true;
                }
            }
        }
        return changed
            ? new SummaryCodec.Region(region.rx(), region.rz(), region.sourceMcaMtimeMs(), merged)
            : region;
    }

    public SummaryCodec.SampledRegion overlay(
        final String dimension, final SummaryCodec.SampledRegion region
    ) {
        return overlay(
            dimension, region,
            new ChunkRegionSlice(region.rx(), region.rz(), 0, 0, 15, 15)
        );
    }

    /** Overlays only the live chunks a cropped page can read. */
    public SummaryCodec.SampledRegion overlay(
        final String dimension,
        final SummaryCodec.SampledRegion region,
        final ChunkRegionSlice slice
    ) {
        if (slice == null || slice.regionX() != region.rx() || slice.regionZ() != region.rz()) {
            throw new IllegalArgumentException("sampled overlay slice does not match region");
        }
        final SummaryCodec.SampledChunk[] merged = region.chunks().clone();
        boolean changed = false;
        for (int localZ = slice.minLocalChunkZ(); localZ <= slice.maxLocalChunkZ(); localZ++) {
            for (int localX = slice.minLocalChunkX(); localX <= slice.maxLocalChunkX(); localX++) {
                final SummaryCodec.Chunk live = get(
                    dimension,
                    region.rx() * 16 + localX,
                    region.rz() * 16 + localZ
                );
                if (live != null) {
                    merged[localZ * 16 + localX] = SummaryCodec.sample(
                        live, region.sampleStride()
                    );
                    changed = true;
                }
            }
        }
        return changed
            ? new SummaryCodec.SampledRegion(
                region.rx(), region.rz(), region.sourceMcaMtimeMs(),
                region.sampleStride(), merged
            )
            : region;
    }

    private static SummaryCodec.Chunk revised(
        final SummaryCodec.Chunk previous,
        final SummaryCodec.Chunk candidate
    ) {
        if (previous == null) {
            return candidate;
        }
        if (previous.generated() == candidate.generated()
            && Arrays.equals(previous.columns(), candidate.columns())) {
            return previous;
        }
        final long nextRevision;
        if (candidate.revision() > previous.revision()) {
            nextRevision = candidate.revision();
        } else if (previous.revision() == Long.MAX_VALUE) {
            nextRevision = Long.MAX_VALUE;
        } else {
            nextRevision = previous.revision() + 1L;
        }
        return new SummaryCodec.Chunk(candidate.generated(), nextRevision, candidate.columns());
    }

    private record Key(String dimension, int chunkX, int chunkZ) {
        private Key {
            dimension = dimension == null ? "unknown" : dimension;
        }
    }

    private void touchRegion(final String dimension, final int chunkX, final int chunkZ) {
        regionEpochs.put(
            new RegionKey(dimension, Math.floorDiv(chunkX, 16), Math.floorDiv(chunkZ, 16)),
            nextEpoch.incrementAndGet()
        );
    }

    private record RegionKey(String dimension, int regionX, int regionZ) {
        private RegionKey {
            dimension = dimension == null ? "unknown" : dimension;
        }
    }
}
