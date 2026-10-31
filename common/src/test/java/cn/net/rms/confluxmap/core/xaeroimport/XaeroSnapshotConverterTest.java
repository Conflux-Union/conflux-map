package cn.net.rms.confluxmap.core.xaeroimport;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Column-bookkeeping coverage for {@link XaeroSnapshotConverter} with a scripted baker. */
class XaeroSnapshotConverterTest {
    private static final XaeroColumnBaker BAKER = (column, worldX, worldZ, waterDepth, biomeId, ceiling) -> {
        if (!column.isGrassSurface() && column.surface() != null
            && column.surface().id().equals("minecraft:air")) {
            return XaeroColumnBaker.Baked.voidColumn();
        }
        if (column.hasWater()) {
            return new XaeroColumnBaker.Baked(
                0x110000FF, 0x220000FF, 0xFFFFFFFF, 0x330000FF, 0x440000FF,
                SurfaceKind.WATER, (short) (column.height() + waterDepth)
            );
        }
        return new XaeroColumnBaker.Baked(
            0x55000066, 0x66000077, 0x77000088, 0, 0, SurfaceKind.LAND, (short) column.height()
        );
    };

    private static XaeroColumn[] columns(final XaeroColumn... prefix) {
        final XaeroColumn[] all = new XaeroColumn[256];
        for (int i = 0; i < all.length; i++) {
            all[i] = prefix[i % prefix.length];
        }
        return all;
    }

    @Test
    void waterColumnsCarrySurfaceDepthAndTopLight() {
        final XaeroColumn water = new XaeroColumn(
            XaeroBlockState.of("minecraft:sand"), 60, 63, 0, "minecraft:ocean",
            List.of(new XaeroOverlay(null, 9, 3)));
        final XaeroTileData tile = new XaeroTileData(1, XaeroTileData.SURFACE_CAVE_START, 30, columns(water));
        final ChunkSnapshot snapshot = XaeroSnapshotConverter.toSnapshot(
            tile, 8, -3, 42L, new MapLayer(MapLayer.Type.SURFACE, 0), BAKER);

        // Column (x=1, z=2) = index 33; every column is the water column.
        assertEquals(63, snapshot.surfaceY[2 * 16 + 1]);
        assertEquals(3, snapshot.fluidDepth[2 * 16 + 1]);
        assertEquals(SurfaceKind.WATER.ordinal(), snapshot.kind[2 * 16 + 1]);
        assertEquals(0x110000FF, snapshot.baseArgb[2 * 16 + 1]);
        assertEquals(0x330000FF, snapshot.overlayArgb[2 * 16 + 1]);
        assertEquals(9, snapshot.light[2 * 16 + 1]);
        assertEquals("minecraft:ocean", snapshot.biomeId[2 * 16 + 1]);
        assertEquals(8, snapshot.chunkX);
        assertEquals(-3, snapshot.chunkZ);
        assertEquals(42L, snapshot.sessionToken);
    }

    @Test
    void voidColumnsCarryNoSurface() {
        final XaeroColumn air = new XaeroColumn(
            XaeroBlockState.of("minecraft:air"), -2048, XaeroColumn.NO_TOP_HEIGHT, 0, null, List.of());
        final XaeroTileData tile = new XaeroTileData(1, XaeroTileData.SURFACE_CAVE_START, 30, columns(air));
        final ChunkSnapshot snapshot = XaeroSnapshotConverter.toSnapshot(
            tile, 0, 0, 1L, new MapLayer(MapLayer.Type.SURFACE, 0), BAKER);

        assertEquals(ChunkSnapshot.NO_SURFACE, snapshot.surfaceY[0]);
        assertEquals(SurfaceKind.VOID.ordinal(), snapshot.kind[0]);
        assertEquals(0, snapshot.baseArgb[0]);
    }

    @Test
    void landColumnsKeepTintSeparateAndIgnoreDepth() {
        final XaeroColumn land = new XaeroColumn(
            XaeroBlockState.of("minecraft:stone"), 80, XaeroColumn.NO_TOP_HEIGHT, 5,
            "minecraft:plains", List.of());
        final XaeroTileData tile = new XaeroTileData(1, XaeroTileData.SURFACE_CAVE_START, 30, columns(land));
        final ChunkSnapshot snapshot = XaeroSnapshotConverter.toSnapshot(
            tile, 0, 0, 1L, new MapLayer(MapLayer.Type.SURFACE, 0), BAKER);

        assertEquals(80, snapshot.surfaceY[0]);
        assertEquals(0, snapshot.fluidDepth[0]);
        assertEquals(SurfaceKind.LAND.ordinal(), snapshot.kind[0]);
        assertEquals(5, snapshot.light[0]);
        // Imported snapshots carry no comparable world revision.
        assertEquals(Long.MIN_VALUE, snapshot.sourceRevision);
    }

    @Test
    void waterDepthClampsToByteRange() {
        final XaeroColumn deep = new XaeroColumn(
            XaeroBlockState.of("minecraft:gravel"), -54, 7, 0, "minecraft:deep_ocean",
            List.of(new XaeroOverlay(null, 0, 15)));
        final XaeroTileData tile = new XaeroTileData(1, XaeroTileData.SURFACE_CAVE_START, 30, columns(deep));
        final ChunkSnapshot snapshot = XaeroSnapshotConverter.toSnapshot(
            tile, 0, 0, 1L, new MapLayer(MapLayer.Type.SURFACE, 0), BAKER);

        assertEquals(61, snapshot.fluidDepth[0]);
        assertEquals(7, snapshot.surfaceY[0]);
    }
}
