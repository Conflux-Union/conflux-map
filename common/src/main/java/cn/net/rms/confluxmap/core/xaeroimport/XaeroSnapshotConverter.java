package cn.net.rms.confluxmap.core.xaeroimport;

import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
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
                final XaeroColumn column = tile.column(x, z);
                biomeId[index] = column.biomeId();
                light[index] = (byte) lightAt(column);
                final boolean water = column.hasWater();
                final int depth = water && column.hasTopHeight()
                    ? Math.min(column.translucentDepth(), 127)
                    : 0;
                final XaeroColumnBaker.Baked baked = baker.bake(
                    column, chunkX * 16 + x, chunkZ * 16 + z, depth, column.biomeId(), ceilingLayer
                );
                kind[index] = (byte) baked.kind().ordinal();
                if (baked.kind() == SurfaceKind.VOID) {
                    surfaceY[index] = ChunkSnapshot.NO_SURFACE;
                    continue;
                }
                surfaceY[index] = baked.surfaceY();
                fluidDepth[index] = (byte) depth;
                baseArgb[index] = baked.baseArgb();
                xaeroBaseArgb[index] = baked.xaeroBaseArgb();
                tintArgb[index] = baked.tintArgb();
                overlayArgb[index] = baked.overlayArgb();
                xaeroOverlayArgb[index] = baked.xaeroOverlayArgb();
            }
        }
        // Imported data has no comparable world revision: sync arbitration (which compares
        // revisions) must treat these chunks as revision-unknown and keep winning over them.
        return new ChunkSnapshot(
            chunkX, chunkZ, sessionToken, Long.MIN_VALUE,
            surfaceY, biomeId, fluidDepth,
            baseArgb, xaeroBaseArgb, tintArgb, overlayArgb, xaeroOverlayArgb, kind, light
        );
    }

    /** Xaero stores the topmost overlay's light as the pixel's top light. */
    private static int lightAt(final XaeroColumn column) {
        return column.overlays().isEmpty() ? column.light() : column.overlays().get(0).light();
    }
}
