package cn.net.rms.confluxmap.core.backfill;

import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import cn.net.rms.confluxmap.core.model.MapLayer;
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
        final MapLayer layer,
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
            out.add(toSnapshot(chunk, chunkX, chunkZ, sessionToken, layer, baker, region.sourceMcaMtimeMs()));
        }
        return out;
    }

    private static ChunkSnapshot toSnapshot(
        final SummaryCodec.SampledChunk chunk,
        final int chunkX,
        final int chunkZ,
        final long sessionToken,
        final MapLayer layer,
        final McaColumnBaker baker,
        final long sourceRevision
    ) {
        final short[] surfaceY = new short[ChunkSnapshot.COLUMNS];
        final String[] biomeId = new String[ChunkSnapshot.COLUMNS];
        final byte[] fluidDepth = new byte[ChunkSnapshot.COLUMNS];
        final int[] baseArgb = new int[ChunkSnapshot.COLUMNS];
        final int[] xaeroBaseArgb = new int[ChunkSnapshot.COLUMNS];
        final int[] tintArgb = new int[ChunkSnapshot.COLUMNS];
        final int[] overlayArgb = new int[ChunkSnapshot.COLUMNS];
        final int[] xaeroOverlayArgb = new int[ChunkSnapshot.COLUMNS];
        final byte[] kind = new byte[ChunkSnapshot.COLUMNS];
        final byte[] light = new byte[ChunkSnapshot.COLUMNS];

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                final int index = z * 16 + x;
                final SummaryCodec.Column column = chunk.column(x, z);
                final SurfaceKind surfaceKind = SurfaceKind.byOrdinal(column.kind());
                kind[index] = (byte) surfaceKind.ordinal();
                biomeId[index] = biomeName(column.biomeId());
                light[index] = (byte) column.blockLight();
                if (surfaceKind == SurfaceKind.VOID) {
                    surfaceY[index] = ChunkSnapshot.NO_SURFACE;
                    continue;
                }
                surfaceY[index] = (short) column.surfaceY();
                fluidDepth[index] = (byte) column.fluidDepth();
                final McaColumnBaker.Baked baked =
                    baker.bake(column, chunkX * 16 + x, chunkZ * 16 + z, column.biomeId());
                baseArgb[index] = baked.baseArgb();
                xaeroBaseArgb[index] = baked.xaeroBaseArgb();
                tintArgb[index] = baked.tintArgb();
                overlayArgb[index] = baked.overlayArgb();
                xaeroOverlayArgb[index] = baked.xaeroOverlayArgb();
            }
        }
        return new ChunkSnapshot(
            chunkX, chunkZ, sessionToken, sourceRevision,
            surfaceY, biomeId, fluidDepth,
            baseArgb, xaeroBaseArgb, tintArgb, overlayArgb, xaeroOverlayArgb, kind, light
        );
    }

    /** Numeric cubiomes biome ids map back to names; unknown ids keep the column unlabelled. */
    private static String biomeName(final int cubiomesBiomeId) {
        return CubiomesBiomeIds.nameForId(cubiomesBiomeId)
            .map(name -> "minecraft:" + name)
            .orElse(null);
    }
}
