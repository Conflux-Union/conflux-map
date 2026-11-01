package cn.net.rms.confluxmap.core.tile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import cn.net.rms.confluxmap.core.color.DaylightModel;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.SampleSource;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.model.TileKey;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.store.MapWorldService;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.util.Argb;
import org.junit.jupiter.api.Test;

/**
 * The top-down surface scan stores {@link ChunkSnapshot#NO_SURFACE} for captured void columns
 * (the End's empty space). These tests pin the compose-side contract of that sentinel against
 * synthetic snapshots shaped exactly like the capture writer's output.
 */
class TileServiceVoidColumnTest {
    private static final int CENTER_X = 1;
    private static final int CENTER_Z = 1;
    private static final int CENTER_INDEX = CENTER_Z * 256 + CENTER_X;
    private static final int LAND_BASE = 0xFF806040;
    private static final int NEIGHBOR_BASE = 0xFF50463A;

    @Test
    void voidColumnsComposeTransparentAndReadAsAbsentReliefNeighbors() throws InterruptedException {
        final MapExecutors executors = new MapExecutors();
        try {
            final int besideVoid = composeCenterPixel(executors, voidSurroundedSnapshot());
            final int besideLegacyVoid = composeCenterPixel(executors, legacyVoidSurroundedSnapshot());
            final int besideNothing = composeCenterPixel(executors, singleLandColumnSnapshot());
            final int besideSlope = composeCenterPixel(executors, litShoulderSnapshot());

            assertEquals(
                besideNothing, besideVoid,
                "NO_SURFACE void neighbors must shade exactly like neighbors with no data"
            );
            assertEquals(
                besideVoid, besideLegacyVoid,
                "a legacy cached void column (fabricated pivot-relative Y) must shade as absent too"
            );
            assertNotEquals(
                besideVoid, besideSlope,
                "real neighbor heights still drive relief, so a fabricated void height would show here"
            );
        } finally {
            executors.shutdown(1000L);
        }
    }

    @Test
    void theVoidColumnComposesInTheConfiguredColorWhileUnknownStaysTransparent()
        throws InterruptedException {
        final MapExecutors executors = new MapExecutors();
        try {
            final ChunkSnapshot snapshot = voidSurroundedSnapshot();
            assertEquals(
                ConfluxConfig.VoidColumnColor.DARK_INDIGO.argb(),
                composePixel(executors, snapshot, 0),
                "a captured void column paints the default preset"
            );
            assertEquals(
                Argb.TRANSPARENT,
                composePixel(executors, snapshot, 0, 0, ConfluxConfig.VoidColumnColor.OFF),
                "the OFF preset keeps the old fully transparent void"
            );
            assertEquals(
                Argb.TRANSPARENT,
                composePixel(executors, singleLandColumnSnapshot(), 0),
                "a never-scanned column stays transparent so void and unexplored stay distinct"
            );
        } finally {
            executors.shutdown(1000L);
        }
    }

    /**
     * The LOD contract behind opaque void presets: {@code average4Weighted} excludes only
     * transparent (unexplored) RGB, so an opaque void pixel must join the 2x2 downsample at
     * full weight, exactly like terrain.
     */
    @Test
    void opaqueVoidColorKeepsFullWeightInLodDownsampling() throws InterruptedException {
        final MapExecutors executors = new MapExecutors();
        try {
            final ChunkSnapshot snapshot = voidColorMixSnapshot();
            final int land0 = composePixel(
                executors, snapshot, 0, 0, ConfluxConfig.VoidColumnColor.XAERO_VIOLET
            );
            final int land1 = composePixel(
                executors, snapshot, 1, 0, ConfluxConfig.VoidColumnColor.XAERO_VIOLET
            );
            final int lod1Pixel = composePixel(
                executors, snapshot, 0, 1, ConfluxConfig.VoidColumnColor.XAERO_VIOLET
            );
            assertEquals(
                Argb.average4Weighted(
                    land0, land1,
                    ConfluxConfig.VoidColumnColor.XAERO_VIOLET.argb(), Argb.TRANSPARENT
                ),
                lod1Pixel,
                "the void quadrant must weight the LOD-1 average like terrain, not like unexplored"
            );
        } finally {
            executors.shutdown(1000L);
        }
    }

    /**
     * One chunk whose top-left 2x2 block mixes two land columns, one void column, and one
     * never-received column; everything else is void so those four pixels decide the LOD-1
     * average at tile pixel (0,0).
     */
    private static ChunkSnapshot voidColorMixSnapshot() {
        final short[] surfaceY = new short[ChunkSnapshot.COLUMNS];
        final int[] baseArgb = new int[ChunkSnapshot.COLUMNS];
        final byte[] kind = new byte[ChunkSnapshot.COLUMNS];
        for (int i = 0; i < ChunkSnapshot.COLUMNS; i++) {
            surfaceY[i] = ChunkSnapshot.NO_SURFACE;
            kind[i] = (byte) SurfaceKind.VOID.ordinal();
        }
        surfaceY[0] = 70;
        baseArgb[0] = LAND_BASE;
        kind[0] = (byte) SurfaceKind.LAND.ordinal();
        surfaceY[1] = 70;
        baseArgb[1] = LAND_BASE;
        kind[1] = (byte) SurfaceKind.LAND.ordinal();
        kind[17] = (byte) SurfaceKind.UNKNOWN.ordinal();
        return withTint(surfaceY, baseArgb, kind);
    }

    private static ChunkSnapshot withTint(
        final short[] surfaceY,
        final int[] baseArgb,
        final byte[] kind
    ) {
        final int[] tintArgb = new int[ChunkSnapshot.COLUMNS];
        java.util.Arrays.fill(tintArgb, 0xFFFFFFFF);
        return new ChunkSnapshot(
            0, 0, 1L, surfaceY, new String[ChunkSnapshot.COLUMNS],
            new byte[ChunkSnapshot.COLUMNS], baseArgb, tintArgb,
            new int[ChunkSnapshot.COLUMNS], kind, new byte[ChunkSnapshot.COLUMNS]
        );
    }

    /** Center land column; every neighbor is the void encoding the capture writer produces. */
    private static ChunkSnapshot voidSurroundedSnapshot() {
        final short[] surfaceY = new short[ChunkSnapshot.COLUMNS];
        final int[] baseArgb = new int[ChunkSnapshot.COLUMNS];
        final byte[] kind = new byte[ChunkSnapshot.COLUMNS];
        for (int i = 0; i < ChunkSnapshot.COLUMNS; i++) {
            surfaceY[i] = ChunkSnapshot.NO_SURFACE;
            kind[i] = (byte) SurfaceKind.VOID.ordinal();
        }
        return withCenterLandColumn(surfaceY, baseArgb, kind);
    }

    /**
     * Center land column; every neighbor is the void encoding the pre-fix capture writer and the
     * region disk cache wrote: kind VOID with a fabricated pivot-relative surface Y (the End's
     * world-top staging position on 1.17.1) and a transparent pixel.
     */
    private static ChunkSnapshot legacyVoidSurroundedSnapshot() {
        final short[] surfaceY = new short[ChunkSnapshot.COLUMNS];
        final int[] baseArgb = new int[ChunkSnapshot.COLUMNS];
        final byte[] kind = new byte[ChunkSnapshot.COLUMNS];
        for (int i = 0; i < ChunkSnapshot.COLUMNS; i++) {
            surfaceY[i] = 257;
            kind[i] = (byte) SurfaceKind.VOID.ordinal();
        }
        return withCenterLandColumn(surfaceY, baseArgb, kind);
    }

    /** Only the center column carries data; the rest are never-received region columns. */
    private static ChunkSnapshot singleLandColumnSnapshot() {
        final short[] surfaceY = new short[ChunkSnapshot.COLUMNS];
        final int[] baseArgb = new int[ChunkSnapshot.COLUMNS];
        final byte[] kind = new byte[ChunkSnapshot.COLUMNS];
        return withCenterLandColumn(surfaceY, baseArgb, kind);
    }

    /**
     * Center land column whose lit-side shoulder (west/south/south-west) sits 60 blocks higher,
     * so the directional relief multiplier provably differs from the flat 1.0 the void and
     * absent-neighbors fixtures produce.
     */
    private static ChunkSnapshot litShoulderSnapshot() {
        final short[] surfaceY = new short[ChunkSnapshot.COLUMNS];
        final int[] baseArgb = new int[ChunkSnapshot.COLUMNS];
        final byte[] kind = new byte[ChunkSnapshot.COLUMNS];
        for (int i = 0; i < ChunkSnapshot.COLUMNS; i++) {
            surfaceY[i] = 70;
            baseArgb[i] = NEIGHBOR_BASE;
            kind[i] = (byte) SurfaceKind.LAND.ordinal();
        }
        surfaceY[CENTER_Z * 16 + 0] = 130;
        surfaceY[2 * 16 + CENTER_X] = 130;
        surfaceY[2 * 16 + 0] = 130;
        return withCenterLandColumn(surfaceY, baseArgb, kind);
    }

    private static ChunkSnapshot withCenterLandColumn(
        final short[] surfaceY,
        final int[] baseArgb,
        final byte[] kind
    ) {
        final int[] tintArgb = new int[ChunkSnapshot.COLUMNS];
        java.util.Arrays.fill(tintArgb, 0xFFFFFFFF);
        surfaceY[CENTER_Z * 16 + CENTER_X] = 70;
        baseArgb[CENTER_Z * 16 + CENTER_X] = LAND_BASE;
        kind[CENTER_Z * 16 + CENTER_X] = (byte) SurfaceKind.LAND.ordinal();
        return new ChunkSnapshot(
            0, 0, 1L, surfaceY, new String[ChunkSnapshot.COLUMNS],
            new byte[ChunkSnapshot.COLUMNS], baseArgb, tintArgb,
            new int[ChunkSnapshot.COLUMNS], kind, new byte[ChunkSnapshot.COLUMNS]
        );
    }

    private static int composeCenterPixel(final MapExecutors executors, final ChunkSnapshot snapshot)
        throws InterruptedException {
        return composePixel(executors, snapshot, CENTER_INDEX);
    }

    private static int composePixel(
        final MapExecutors executors,
        final ChunkSnapshot snapshot,
        final int pixelIndex
    ) throws InterruptedException {
        return composePixel(executors, snapshot, pixelIndex, 0, null);
    }

    /** {@code voidColor} {@code null} keeps the config default. */
    private static int composePixel(
        final MapExecutors executors,
        final ChunkSnapshot snapshot,
        final int pixelIndex,
        final int lod,
        final ConfluxConfig.VoidColumnColor voidColor
    ) throws InterruptedException {
        final MapWorldService mapWorlds = new MapWorldService();
        final SessionGuard.Session session = new SessionGuard.Session(
            1L, new WorldIdentity("local", "world"), DimensionId.OVERWORLD
        );
        mapWorlds.switchSession(session);
        mapWorlds.current().put(MapLayer.SURFACE, snapshot, SampleSource.REAL_LIVE);
        final ConfluxConfig config = new ConfluxConfig();
        config.dynamicLighting = false;
        if (voidColor != null) {
            config.voidColumnColor = voidColor;
        }
        final TileService tiles = new TileService(mapWorlds, executors, config, new DaylightModel());
        final TileKey terrain = new TileKey(
            session.world(), session.dimension(), MapLayer.SURFACE.cacheId(), lod, 0, 0
        );
        tiles.requestTile(terrain);
        final long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            for (final TileUpdate update : tiles.drainUploads(64)) {
                if (update.key().equals(terrain)) {
                    return update.argbPixels()[pixelIndex];
                }
            }
            Thread.sleep(10L);
        }
        throw new AssertionError("terrain upload never arrived");
    }
}
