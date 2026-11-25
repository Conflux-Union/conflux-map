package cn.net.rms.confluxmap.core.backfill;

import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import cn.net.rms.confluxmap.core.model.ChunkSnapshotArrays;
import cn.net.rms.confluxmap.core.model.SampleSource;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.net.SummaryCodec;
import cn.net.rms.confluxmap.core.predict.CubiomesBiomeIds;
import java.util.ArrayList;
import java.util.List;

/**
 * Assembles scanned Anvil summary columns into {@link ChunkSnapshot}s tagged
 * {@link SampleSource#REAL_SCAN}. Color semantics live in {@link McaColumnBaker};
 * this class owns the column bookkeeping: array orientation, void semantics,
 * and the {@code sourceRevision} contract — the MCA file's mtime, so the disk
 * cache can detect stale scans and sync arbitration can rank the chunk.
 */
public final class McaSnapshotConverter {
    private McaSnapshotConverter() {
    }

    /**
     * Converts one scanned summary region into snapshots for its generated
     * chunks. Only the LOD0 stride (all 256 columns per chunk) is accepted —
     * backfill always scans at full column density and lets tile composition
     * downsample.
     */
    public static List<ChunkSnapshot> toSnapshots(
        final SummaryCodec.SampledRegion region,
        final long sessionToken,
        final McaColumnBaker baker
    ) {
        if (region.sampleStride() != 1) {
            throw new IllegalArgumentException("backfill scans must use the full column stride");
        }
        final List<ChunkSnapshot> out = new ArrayList<>();
        final SummaryCodec.SampledChunk[] chunks = region.chunks();
        for (int index = 0; index < chunks.length; index++) {
            final SummaryCodec.SampledChunk chunk = chunks[index];
            if (!chunk.generated()) {
                continue;
            }
            final int chunkX = region.rx() * 16 + index % 16;
            final int chunkZ = region.rz() * 16 + index / 16;
            out.add(toSnapshot(chunk, chunkX, chunkZ, sessionToken, baker, region.sourceMcaMtimeMs()));
        }
        return out;
    }

    private static ChunkSnapshot toSnapshot(
        final SummaryCodec.SampledChunk chunk,
        final int chunkX,
        final int chunkZ,
        final long sessionToken,
        final McaColumnBaker baker,
        final long sourceRevision
    ) {
        final ChunkSnapshotArrays arrays = new ChunkSnapshotArrays();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                final int index = z * 16 + x;
                final SummaryCodec.Column column = chunk.column(x, z);
                final SurfaceKind surfaceKind = SurfaceKind.byOrdinal(column.kind());
                arrays.kind[index] = (byte) surfaceKind.ordinal();
                arrays.biomeId[index] = biomeName(column.biomeId());
                arrays.light[index] = (byte) column.blockLight();
                if (surfaceKind == SurfaceKind.VOID) {
                    arrays.surfaceY[index] = ChunkSnapshot.NO_SURFACE;
                    continue;
                }
                arrays.surfaceY[index] = (short) column.surfaceY();
                arrays.fluidDepth[index] = (byte) column.fluidDepth();
                final McaColumnBaker.Baked baked =
                    baker.bake(column, chunkX * 16 + x, chunkZ * 16 + z, column.biomeId());
                arrays.baseArgb[index] = baked.baseArgb();
                arrays.xaeroBaseArgb[index] = baked.xaeroBaseArgb();
                arrays.tintArgb[index] = baked.tintArgb();
                arrays.overlayArgb[index] = baked.overlayArgb();
                arrays.xaeroOverlayArgb[index] = baked.xaeroOverlayArgb();
            }
        }
        return arrays.toSnapshot(chunkX, chunkZ, sessionToken, sourceRevision);
    }

    /** Numeric cubiomes biome ids map back to names; unknown ids keep the column unlabelled. */
    private static String biomeName(final int cubiomesBiomeId) {
        return CubiomesBiomeIds.nameForId(cubiomesBiomeId)
            .map(name -> "minecraft:" + name)
            .orElse(null);
    }
}
