package cn.net.rms.confluxmap.core.xaeroimport;

import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import cn.net.rms.confluxmap.core.model.ChunkSnapshotArrays;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.SurfaceKind;

/**
 * Assembles decoded external-map tiles into {@link ChunkSnapshot}s. Color and
 * kind semantics live in {@link XaeroColumnBaker}; this class owns the column
 * bookkeeping: array orientation, void/absent semantics, the recovered fluid
 * depth, and the light value (the topmost overlay's light for water columns,
 * matching what capture samples above the surface).
 */
public final class XaeroSnapshotConverter {
    private XaeroSnapshotConverter() {
    }

    public static ChunkSnapshot toSnapshot(
        final XaeroTileData tile,
        final int chunkX,
        final int chunkZ,
        final long sessionToken,
        final MapLayer layer,
        final XaeroColumnBaker baker
    ) {
        final boolean ceilingLayer = layer.type() == MapLayer.Type.NETHER_CEILING;
        final ChunkSnapshotArrays arrays = new ChunkSnapshotArrays();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                final int index = z * 16 + x;
                final XaeroColumn column = tile.column(x, z);
                arrays.biomeId[index] = column.biomeId();
                arrays.light[index] = (byte) lightAt(column);
                final boolean water = column.hasWater();
                final int depth = water && column.hasTopHeight()
                    ? Math.min(column.translucentDepth(), 127)
                    : 0;
                final XaeroColumnBaker.Baked baked = baker.bake(
                    column, chunkX * 16 + x, chunkZ * 16 + z, depth, column.biomeId(), ceilingLayer
                );
                arrays.kind[index] = (byte) baked.kind().ordinal();
                if (baked.kind() == SurfaceKind.VOID) {
                    arrays.surfaceY[index] = ChunkSnapshot.NO_SURFACE;
                    continue;
                }
                arrays.surfaceY[index] = baked.surfaceY();
                arrays.fluidDepth[index] = (byte) depth;
                arrays.baseArgb[index] = baked.baseArgb();
                arrays.xaeroBaseArgb[index] = baked.xaeroBaseArgb();
                arrays.tintArgb[index] = baked.tintArgb();
                arrays.overlayArgb[index] = baked.overlayArgb();
                arrays.xaeroOverlayArgb[index] = baked.xaeroOverlayArgb();
            }
        }
        // Imported data has no comparable world revision: sync arbitration (which compares
        // revisions) must treat these chunks as revision-unknown and keep winning over them.
        return arrays.toSnapshot(chunkX, chunkZ, sessionToken, Long.MIN_VALUE);
    }

    /** Xaero stores the topmost overlay's light as the pixel's top light. */
    private static int lightAt(final XaeroColumn column) {
        return column.overlays().isEmpty() ? column.light() : column.overlays().get(0).light();
    }
}
