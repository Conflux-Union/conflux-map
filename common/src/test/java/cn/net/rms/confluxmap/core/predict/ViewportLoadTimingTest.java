package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.TileKey;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import org.junit.jupiter.api.Test;

/** {@link ViewportLoadTiming}'s load window: open on dispatch, accumulate, close on upload drain. */
class ViewportLoadTimingTest {
    private static final DimensionId DIM = DimensionId.OVERWORLD;
    private static final DimensionId NETHER = DimensionId.NETHER;
    private static final WorldIdentity WORLD = WorldIdentity.singleplayer("viewport-load-timing-test");
    private static final String LAYER = "surface!pred";

    private static ViewportLoadTiming.Rect rect(final int minX, final int maxX, final int minZ, final int maxZ) {
        return new ViewportLoadTiming.Rect(DIM, 2, minX, maxX, minZ, maxZ);
    }

    private static TileKey tile(final int x, final int z) {
        return tile(DIM, x, z);
    }

    private static TileKey tile(final DimensionId dim, final int x, final int z) {
        return new TileKey(WORLD, dim, LAYER, 2, x, z);
    }

    private static long[] stages(final long... perStage) {
        final long[] stageNanos = new long[SampleStage.COUNT];
        System.arraycopy(perStage, 0, stageNanos, 0, perStage.length);
        return stageNanos;
    }

    private static long[] overworldStages(final long... perStage) {
        final long[] stageNanos = new long[LodSampling.OverworldStage.values().length];
        System.arraycopy(perStage, 0, stageNanos, 0, perStage.length);
        return stageNanos;
    }

    @Test
    void overworldStageNanosAccumulateAcrossSubmittedTilesOnly() {
        final ViewportLoadTiming timing = new ViewportLoadTiming();
        timing.dispatched(rect(0, 1, 0, 1), tile(0, 0), 100L, 1_000L);

        final long[] first = overworldStages(30L, 40L, 300L, 0L, 500L, 10L);
        assertNull(timing.finished(tile(0, 0), 200L, stages(150L), first, 66_000L, true, false, 1_200L));
        final long[] second = overworldStages(0L, 0L, 100L, 0L, 0L, 40L);
        assertNull(timing.finished(tile(1, 1), 300L, null, second, 66_564L, true, true, 1_900L));
        // A stale (upload-less) composition must not leak its stage walls into the breakdown.
        assertNull(timing.finished(tile(5, 5), 400L, null, overworldStages(9_999L), 1_000L, false, false, 1_950L));

        assertNull(timing.uploaded(tile(0, 0), 10L, false, 2_000L));
        final ViewportLoadTiming.Summary done = timing.uploaded(tile(1, 1), 20L, true, 2_500L);

        final long[] stages = done.overworldStageNanos();
        assertEquals(LodSampling.OverworldStage.values().length, stages.length);
        assertEquals(30L, stages[LodSampling.OverworldStage.H_LATTICE.ordinal()]);
        assertEquals(40L, stages[LodSampling.OverworldStage.ANCHOR_BIOMES.ordinal()]);
        assertEquals(300L + 100L, stages[LodSampling.OverworldStage.BAND.ordinal()]);
        assertEquals(0L, stages[LodSampling.OverworldStage.EXACT_COLUMNS.ordinal()]);
        assertEquals(500L, stages[LodSampling.OverworldStage.CELLS.ordinal()]);
        assertEquals(10L + 40L, stages[LodSampling.OverworldStage.COMPOSE.ordinal()]);
    }

    @Test
    void windowOpensOnDispatchAndCompletesOnlyAfterEveryUploadDrains() {
        final ViewportLoadTiming timing = new ViewportLoadTiming();
        assertFalse(timing.active());
        assertNull(timing.windowRect());

        assertNull(timing.dispatched(rect(0, 1, 0, 1), tile(0, 0), 100L, 1_000L));
        assertTrue(timing.active());
        assertEquals(rect(0, 1, 0, 1), timing.windowRect());

        // Not idle yet: the second tile is still pending, so the first finish keeps it open.
        assertNull(timing.finished(tile(0, 0), 200L, stages(150L, 0L, 50L), null, 66_000L, true, false, 1_200L));
        // Generation drained, but the two uploads are still outstanding.
        assertNull(timing.finished(tile(1, 1), 300L, stages(200L, 0L, 100L), null, 66_564L, true, true, 1_900L));

        // The first completion stamps the first-visible marker; the window stays open.
        assertNull(timing.uploaded(tile(0, 0), 10L, false, 2_000L));
        assertEquals(1_000L, timing.firstUploadWallNanos());
        assertEquals(tile(0, 0), timing.firstUploadedTile());

        final ViewportLoadTiming.Summary done =
            timing.uploaded(tile(1, 1), 20L, true, 2_500L);
        assertFalse(timing.active());

        assertEquals(DIM, done.dimension());
        assertEquals(2, done.lod());
        assertEquals(0, done.minTileX());
        assertEquals(1, done.maxTileX());
        assertEquals(0, done.minTileZ());
        assertEquals(1, done.maxTileZ());
        assertTrue(done.complete());
        assertEquals(2, done.tiles());
        assertEquals(2, done.attempts());
        assertEquals(66_000L + 66_564L, done.points());
        assertEquals(200L + 300L, done.composeNanos());
        assertEquals(100L, done.schedNanos());
        assertEquals((2_000L - 1_200L) + (2_500L - 1_900L), done.uploadWaitNanos());
        assertEquals(10L + 20L, done.uploadNanos());
        assertEquals(1_000L, done.firstUploadWallNanos());
        assertEquals(2_500L - 1_000L, done.wallNanos());
        // Breakdown sum consistency: stage buckets are element-wise sums of the tiles' arrays.
        assertEquals(150L + 200L, done.stageNanos()[SampleStage.H.ordinal()]);
        assertEquals(50L + 100L, done.stageNanos()[SampleStage.B.ordinal()]);
        assertEquals(SampleStage.total(done.stageNanos()), 150L + 50L + 200L + 100L);
    }

    @Test
    void tilesOutsideTheWindowNeitherAccumulateNorCloseIt() {
        final ViewportLoadTiming timing = new ViewportLoadTiming();
        timing.dispatched(rect(0, 1, 0, 1), tile(0, 0), 0L, 1_000L);

        // Ring/background finish after the visible tile: no accumulation...
        assertNull(timing.finished(tile(5, 5), 400L, null, null, 1_000L, true, false, 1_100L));
        // ...and only the finish-plus-upload of its own last tile closes the window.
        assertNull(timing.finished(tile(0, 0), 100L, null, null, 10L, true, true, 1_500L));
        final ViewportLoadTiming.Summary done =
            timing.uploaded(tile(0, 0), 5L, true, 1_600L);
        assertEquals(1, done.tiles());
        assertEquals(10L, done.points());
        assertEquals(100L, done.composeNanos());
    }

    @Test
    void differentDimensionOrLodIsOutsideTheWindow() {
        final ViewportLoadTiming timing = new ViewportLoadTiming();
        timing.dispatched(rect(0, 1, 0, 1), tile(0, 0), 0L, 1_000L);

        assertNull(timing.finished(tile(NETHER, 0, 0), 500L, null, null, 70_000L, true, false, 1_050L));
        assertNull(timing.finished(new TileKey(WORLD, DIM, LAYER, 3, 0, 0), 500L, null, null, 70_000L, true, false, 1_060L));

        assertNull(timing.finished(tile(0, 0), 100L, null, null, 10L, true, true, 1_500L));
        final ViewportLoadTiming.Summary done =
            timing.uploaded(tile(0, 0), 5L, true, 1_600L);
        assertEquals(1, done.tiles());
        assertEquals(10L, done.points());
    }

    @Test
    void repeatedDispatchOnTheSameViewportKeepsTheWindowAndItsStats() {
        final ViewportLoadTiming timing = new ViewportLoadTiming();
        timing.dispatched(rect(0, 1, 0, 1), tile(0, 0), 40L, 1_000L);
        assertNull(timing.finished(tile(0, 0), 100L, null, null, 10L, true, false, 1_100L));

        assertNull(timing.dispatched(rect(0, 1, 0, 1), tile(1, 1), 60L, 1_200L));
        assertNull(timing.finished(tile(1, 1), 50L, null, null, 5L, true, true, 1_400L));
        assertNull(timing.uploaded(tile(0, 0), 5L, true, 1_450L));
        final ViewportLoadTiming.Summary done =
            timing.uploaded(tile(1, 1), 5L, true, 1_600L);
        assertEquals(2, done.tiles());
        assertEquals(15L, done.points());
        assertEquals(40L + 60L, done.schedNanos());
    }

    @Test
    void viewportMoveSupersedesTheOpenWindowAsIncomplete() {
        final ViewportLoadTiming timing = new ViewportLoadTiming();
        timing.dispatched(rect(0, 1, 0, 1), tile(0, 0), 0L, 1_000L);
        assertNull(timing.finished(tile(0, 0), 100L, null, null, 10L, true, false, 1_100L));

        final ViewportLoadTiming.Summary superseded =
            timing.dispatched(rect(4, 5, 4, 5), tile(4, 4), 0L, 3_000L);
        assertFalse(superseded.complete());
        // One composition landed, but its upload never did inside this window.
        assertEquals(0, superseded.tiles());
        assertEquals(1, superseded.attempts());
        assertEquals(3_000L - 1_000L, superseded.wallNanos());

        // The replacement window starts from zero and completes normally.
        assertNull(timing.finished(tile(4, 4), 30L, null, null, 3L, true, true, 3_500L));
        final ViewportLoadTiming.Summary done = timing.uploaded(tile(4, 4), 5L, true, 3_600L);
        assertTrue(done.complete());
        assertEquals(1, done.tiles());
        assertEquals(4, done.minTileX());
        assertEquals(3_600L - 3_000L, done.wallNanos());
    }

    @Test
    void windowsWithoutFinishedTilesCloseSilently() {
        final ViewportLoadTiming timing = new ViewportLoadTiming();
        timing.dispatched(rect(0, 1, 0, 1), tile(0, 0), 0L, 1_000L);

        // Open window abandoned by a viewport move before any tile finished.
        assertNull(timing.dispatched(rect(4, 5, 4, 5), tile(4, 4), 0L, 3_000L));
        assertTrue(timing.active());
        // ...and the periodic idle close on the replacement also logs nothing when idle
        // arrives before any tile could accumulate (stale compositions produce no stats).
        assertNull(timing.closeIfIdle(true, 3_100L));
        assertFalse(timing.active());
    }

    @Test
    void staleCompositionsNeitherAccumulateNorBlockCompletion() {
        final ViewportLoadTiming timing = new ViewportLoadTiming();
        timing.dispatched(rect(0, 1, 0, 1), tile(0, 0), 0L, 1_000L);
        // A superseded composition (session reload mid-compose): it finishes inside the rect
        // but queued no upload, so it only counts as an attempt.
        assertNull(timing.finished(tile(0, 0), 700L, stages(600L), null, 90_000L, false, false, 1_700L));
        assertNull(timing.finished(tile(1, 1), 100L, null, null, 10L, true, true, 1_900L));
        final ViewportLoadTiming.Summary done =
            timing.uploaded(tile(1, 1), 5L, true, 2_000L);
        assertEquals(1, done.tiles());
        assertEquals(2, done.attempts());
        assertEquals(10L, done.points());
        assertEquals(100L, done.composeNanos());
        // The stale attempt's sampling must not leak into the stage breakdown.
        assertEquals(0L, done.stageNanos()[SampleStage.H.ordinal()]);
        assertEquals(2_000L - 1_000L, done.wallNanos());
    }

    @Test
    void uploadsForUnknownKeysAreIgnored() {
        final ViewportLoadTiming timing = new ViewportLoadTiming();
        timing.dispatched(rect(0, 1, 0, 1), tile(0, 0), 0L, 1_000L);
        assertNull(timing.finished(tile(0, 0), 100L, null, null, 10L, true, false, 1_500L));
        assertNull(timing.finished(tile(1, 1), 100L, null, null, 10L, true, true, 1_550L));
        // A foreign key changes nothing and closes nothing...
        assertNull(timing.uploaded(tile(5, 5), 5L, true, 1_600L));
        // ...a completion while ring work is still pending keeps the window open...
        assertNull(timing.uploaded(tile(0, 0), 5L, false, 1_650L));
        // ...and the last genuine completion closes it exactly once.
        final ViewportLoadTiming.Summary done = timing.uploaded(tile(1, 1), 5L, true, 1_700L);
        assertSame(DIM, done.dimension());
        assertEquals(2, done.tiles());
        // The foreign-key report must not have counted toward the upload total.
        assertEquals(10L, done.uploadNanos());
        assertFalse(timing.active());
    }
}
