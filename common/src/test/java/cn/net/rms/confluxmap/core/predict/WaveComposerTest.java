package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * {@link WaveComposer}'s barrier protocol at the fake-sampler layer: a wave advances every member
 * through all six overworld stages, expires whole at a barrier before further dispatch, survives
 * a member's stage exception without wedging its wave-mates, and degenerates to the retained
 * single-shot path. The fake's terrain and biome values are pure functions of block coordinates
 * and its batch entries fill from the same functions, so a wave's staged result is comparable
 * member-for-member against {@link LodSampling#sample}.
 */
class WaveComposerTest {
    private static final int LOD = 2;
    /** Below {@code OVERVIEW_ANCHOR_MIN_LOD}: every member takes the dense overview arm. */
    private static final int DENSE_LOD = 0;
    /** Opaque identity constant the tests pass to the wave; only equality is ever observed. */
    private static final int MC_VERSION = RecordingWaveSampler.MC_VERSION;

    @Test
    void waveRunsAllStagesForAllMembers() throws InterruptedException {
        final RecordingWaveSampler sampler = new RecordingWaveSampler();
        final WaveComposer wave = wave(sampler, 4, () -> false);
        final List<Thread> members = new ArrayList<>();
        final boolean[] completed = runAllMembers(wave, 4, members);
        joinAllMembers(members);

        for (int i = 0; i < 4; i++) {
            assertTrue(completed[i], "member " + i + " must finish every stage");
            assertTrue(fullyComposed(wave.grid(i)), "member " + i + " must reach the compose stage");
        }
        assertEquals(
            List.of("heights:4", "biomes:4"),
            sampler.batchCalls,
            "each batchable stage must dispatch exactly one batch for the whole wave"
        );
    }

    @Test
    void expiryDissolvesWaveBeforeEnqueue() throws InterruptedException {
        final RecordingWaveSampler sampler = new RecordingWaveSampler();
        final AtomicBoolean expired = new AtomicBoolean();
        final CountDownLatch heightsBatchEntered = new CountDownLatch(1);
        final CountDownLatch flipAcknowledged = new CountDownLatch(1);
        sampler.insideHeightsBatch = () -> {
            heightsBatchEntered.countDown();
            try {
                flipAcknowledged.await(5, TimeUnit.SECONDS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        final WaveComposer wave = wave(sampler, 4, expired::get);
        final List<Thread> members = new ArrayList<>();
        final boolean[] completed = runAllMembers(wave, 4, members);

        assertTrue(heightsBatchEntered.await(5, TimeUnit.SECONDS), "the leader must reach the heights batch");
        expired.set(true);
        flipAcknowledged.countDown();
        joinAllMembers(members);

        for (int i = 0; i < 4; i++) {
            assertFalse(completed[i], "an expired wave must report every member dropped");
        }
        assertTrue(wave.terminated(), "dissolution must terminate the barrier phaser");
        assertEquals(
            List.of("heights:4"),
            sampler.batchCalls,
            "the in-flight batch may finish, but no batch may be enqueued after the flip"
        );
    }

    @Test
    void memberDeathDissolvesWave() throws InterruptedException {
        final RecordingWaveSampler sampler = new RecordingWaveSampler();
        sampler.throwOnPerTileHeights = true;
        final WaveComposer wave = wave(sampler, 4, () -> false);
        final List<Thread> members = new ArrayList<>();
        final boolean[] completed = runAllMembers(wave, 4, members);
        joinAllMembers(members);
        for (int i = 0; i < 4; i++) {
            assertFalse(completed[i], "a wave losing a member mid-stage must drop every member");
        }
        assertTrue(wave.terminated(), "the failing member's handler must terminate the phaser");
    }

    @Test
    void singleMemberWaveUsesLegacyPath() {
        final RecordingWaveSampler sampler = new RecordingWaveSampler();
        final WaveComposer wave = new WaveComposer(
            1, () -> false, sampler, sampler.seed, MC_VERSION, false, LOD, new int[] {4096}, new int[] {-8192},
            sampler
        );

        assertTrue(wave.runMember(0), "the single-shot path must succeed against the fake sampler");
        assertTrue(sampler.batchCalls.isEmpty(), "a degenerate wave must not dispatch batches");
        assertFalse(sampler.perTileCalls.isEmpty(), "the single-shot path must sample per tile");
        final BaselineGrid reference = LodSampling.sample(sampler, false, LOD, 4096, -8192);
        assertArrayEquals(reference.terrainY, wave.grid(0).terrainY);
        assertArrayEquals(reference.biomeId, wave.grid(0).biomeId);
        assertArrayEquals(reference.baseSurfaceY, wave.grid(0).baseSurfaceY);
    }

    /**
     * The all-dense batch arm (members below {@code OVERVIEW_ANCHOR_MIN_LOD} build no anchor
     * lattice): the H_LATTICE dispatch carries each member's whole overview plane, the
     * anchor-sweep stage queries nothing, and the staged result still equals the per-tile
     * single-shot reference.
     */
    @Test
    void denseOverviewWaveBatchesWholePlanes() throws InterruptedException {
        final RecordingWaveSampler sampler = new RecordingWaveSampler();
        final int[] originX = {4096, 8192, 12288, 16384};
        final int[] originZ = {-8192, 4096, 0, 12288};
        final WaveComposer wave = new WaveComposer(
            4, () -> false, sampler, sampler.seed, MC_VERSION, false, DENSE_LOD, originX, originZ,
            sampler
        );
        final List<Thread> members = new ArrayList<>();
        final boolean[] completed = runAllMembers(wave, 4, members);
        joinAllMembers(members);

        assertEquals(
            List.of("heights:4"),
            sampler.batchCalls,
            "the dense arm must batch the overview planes and have no anchor sweep to batch"
        );
        for (int i = 0; i < 4; i++) {
            assertTrue(completed[i], "member " + i + " must complete");
            final BaselineGrid reference = LodSampling.sample(sampler, false, DENSE_LOD, originX[i], originZ[i]);
            assertArrayEquals(reference.terrainY, wave.grid(i).terrainY, "member " + i + " terrain diverged");
            assertArrayEquals(reference.biomeId, wave.grid(i).biomeId, "member " + i + " biomes diverged");
            assertArrayEquals(reference.baseSurfaceY, wave.grid(i).baseSurfaceY, "member " + i + " surface diverged");
            assertArrayEquals(reference.surfaceFlags, wave.grid(i).surfaceFlags, "member " + i + " flags diverged");
        }
    }

    /**
     * The staged skeleton's central parity claim: a batch-dispatched wave yields grids
     * bit-identical to the retained per-tile single-shot path.
     */
    @Test
    void stagedWaveMatchesPerTileSampling() throws InterruptedException {
        final RecordingWaveSampler sampler = new RecordingWaveSampler();
        final int[] originX = {4096, 8192, 12288, 16384};
        final int[] originZ = {-8192, 4096, 0, 12288};
        final WaveComposer wave = new WaveComposer(
            4, () -> false, sampler, sampler.seed, MC_VERSION, false, LOD, originX, originZ,
            sampler
        );
        final List<Thread> members = new ArrayList<>();
        final boolean[] completed = runAllMembers(wave, 4, members);
        joinAllMembers(members);

        for (int i = 0; i < 4; i++) {
            assertTrue(completed[i], "member " + i + " must complete");
            final BaselineGrid reference = LodSampling.sample(sampler, false, LOD, originX[i], originZ[i]);
            assertArrayEquals(reference.terrainY, wave.grid(i).terrainY, "member " + i + " terrain diverged");
            assertArrayEquals(reference.biomeId, wave.grid(i).biomeId, "member " + i + " biomes diverged");
            assertArrayEquals(reference.baseSurfaceY, wave.grid(i).baseSurfaceY, "member " + i + " surface diverged");
            assertArrayEquals(reference.surfaceFlags, wave.grid(i).surfaceFlags, "member " + i + " flags diverged");
        }
    }

    /**
     * Per-member six-stage wall accounting: batched stages credit every member an even share of
     * the batch wall, per-tile stages land in their own buckets, timing-off waves expose null,
     * and a degenerate timing wave fills the buckets through the retained legacy path.
     */
    @Test
    void overworldStageNanosFillPerMemberBuckets() throws InterruptedException {
        final RecordingWaveSampler sampler = new RecordingWaveSampler();
        final int[] originX = {4096, 8192, 12288, 16384};
        final int[] originZ = {-8192, 4096, 0, 12288};
        final WaveComposer wave = new WaveComposer(
            4, () -> false, sampler, sampler.seed, MC_VERSION, true, LOD, originX, originZ,
            sampler
        );
        final List<Thread> members = new ArrayList<>();
        final boolean[] completed = runAllMembers(wave, 4, members);
        joinAllMembers(members);

        for (int i = 0; i < 4; i++) {
            assertTrue(completed[i], "member " + i + " must complete");
            final long[] stages = wave.overworldStageNanos(i);
            assertNotNull(stages, "a timing wave must expose member stage walls");
            assertEquals(LodSampling.OVERWORLD_STAGE_COUNT, stages.length);
            assertTrue(stages[LodSampling.OverworldStage.H_LATTICE.ordinal()] > 0L,
                "the batched lattice stage must credit member " + i);
            assertTrue(stages[LodSampling.OverworldStage.ANCHOR_BIOMES.ordinal()] > 0L,
                "the batched anchor stage must credit member " + i);
            assertTrue(stages[LodSampling.OverworldStage.BAND.ordinal()] > 0L,
                "the per-tile band stage must credit member " + i);
            assertTrue(stages[LodSampling.OverworldStage.CELLS.ordinal()] > 0L,
                "the per-tile cells stage must credit member " + i);
            assertTrue(stages[LodSampling.OverworldStage.COMPOSE.ordinal()] > 0L,
                "the compose stage must credit member " + i);
        }
    }

    @Test
    void timingOffExposesNullAndSingleMemberWaveFillsLegacyBuckets()
        throws InterruptedException {
        final RecordingWaveSampler sampler = new RecordingWaveSampler();
        final WaveComposer untimed = new WaveComposer(
            2, () -> false, sampler, sampler.seed, MC_VERSION, false, LOD,
            new int[] {4096, 8192}, new int[] {-8192, 4096}, sampler
        );
        final List<Thread> members = new ArrayList<>();
        runAllMembers(untimed, 2, members);
        joinAllMembers(members);
        assertNull(untimed.overworldStageNanos(0), "timing off must leave the stage vector null");

        final WaveComposer single = new WaveComposer(
            1, () -> false, sampler, sampler.seed, MC_VERSION, true, LOD,
            new int[] {4096}, new int[] {-8192}, sampler
        );
        assertTrue(single.runMember(0), "the timing single wave must succeed");
        final long[] stages = single.overworldStageNanos(0);
        assertNotNull(stages, "a timing single wave must fill the legacy stage vector");
        assertEquals(LodSampling.OVERWORLD_STAGE_COUNT, stages.length);
        assertTrue(stages[LodSampling.OverworldStage.H_LATTICE.ordinal()] > 0L);
        assertTrue(stages[LodSampling.OverworldStage.BAND.ordinal()] > 0L);
        assertTrue(stages[LodSampling.OverworldStage.CELLS.ordinal()] > 0L);
        assertTrue(stages[LodSampling.OverworldStage.COMPOSE.ordinal()] > 0L);
    }

    /**
     * A heights-batch bitmap failure must not lose the affected members: exactly the failed-bit
     * members re-run the stage per tile and every member's output still equals the all-per-tile
     * reference.
     */
    @Test
    void batchBitmapFailureTriggersCpuRepair() throws InterruptedException {
        final RecordingWaveSampler sampler = new RecordingWaveSampler();
        sampler.heightsFailBits = (1 << 1) | (1 << 3);
        final int[] originX = {4096, 8192, 12288, 16384};
        final int[] originZ = {-8192, 4096, 0, 12288};
        final WaveComposer wave = new WaveComposer(
            4, () -> false, sampler, sampler.seed, MC_VERSION, false, LOD, originX, originZ,
            sampler
        );
        final List<Thread> members = new ArrayList<>();
        final boolean[] completed = runAllMembers(wave, 4, members);
        joinAllMembers(members);

        assertEquals(
            2,
            Collections.frequency(sampler.perTileCalls, "overviewHeightsCoarse"),
            "exactly the failed-bit members must re-run the stage per tile"
        );
        for (int i = 0; i < 4; i++) {
            assertTrue(completed[i], "member " + i + " must complete");
            final BaselineGrid reference = LodSampling.sample(sampler, false, LOD, originX[i], originZ[i]);
            assertArrayEquals(reference.terrainY, wave.grid(i).terrainY, "member " + i + " terrain diverged");
            assertArrayEquals(reference.biomeId, wave.grid(i).biomeId, "member " + i + " biomes diverged");
            assertArrayEquals(reference.baseSurfaceY, wave.grid(i).baseSurfaceY, "member " + i + " surface diverged");
        }
    }

    /**
     * A wave admitted without the batch seam (the CPU-backend / engine-unavailable gate passes
     * {@code null}) must run the batchable stages per tile on every member's own thread: zero
     * batch-seam invocations, the heaviest stage spread across distinct threads, and grids equal
     * to the per-tile reference - the CPU-neutrality topology, not a leader-serialized repair.
     */
    @Test
    void gatedSeamRunsBatchableStagesPerTileOnEveryMemberThread() throws InterruptedException {
        final RecordingWaveSampler sampler = new RecordingWaveSampler();
        final int[] originX = {4096, 8192, 12288, 16384};
        final int[] originZ = {-8192, 4096, 0, 12288};
        final WaveComposer wave = new WaveComposer(
            4, () -> false, sampler, sampler.seed, MC_VERSION, false, LOD, originX, originZ, null
        );
        final List<Thread> members = new ArrayList<>();
        final boolean[] completed = runAllMembers(wave, 4, members);
        joinAllMembers(members);

        assertTrue(sampler.batchCalls.isEmpty(), "a gated wave must never invoke the batch seam");
        assertEquals(
            4,
            Collections.frequency(sampler.perTileCalls, "overviewHeightsCoarse"),
            "each member must run the heaviest stage through the per-tile entry"
        );
        assertEquals(
            4,
            new HashSet<>(sampler.coarseHeightsThreads).size(),
            "the heaviest stage must run on each member's own thread, not funnel through one"
        );
        for (int i = 0; i < 4; i++) {
            assertTrue(completed[i], "member " + i + " must complete");
            final BaselineGrid reference = LodSampling.sample(
                new RecordingWaveSampler(), false, LOD, originX[i], originZ[i]
            );
            assertArrayEquals(reference.terrainY, wave.grid(i).terrainY, "member " + i + " terrain diverged");
            assertArrayEquals(reference.biomeId, wave.grid(i).biomeId, "member " + i + " biomes diverged");
            assertArrayEquals(reference.baseSurfaceY, wave.grid(i).baseSurfaceY, "member " + i + " surface diverged");
        }
    }

    /** A whole-batch refusal degenerates the stage to per-tile dispatch for every member. */
    @Test
    void batchRefusalFallsBackPerTile() throws InterruptedException {        final RecordingWaveSampler sampler = new RecordingWaveSampler();
        sampler.refuseBatches = true;
        final int[] originX = {4096, 8192, 12288, 16384};
        final int[] originZ = {-8192, 4096, 0, 12288};
        final WaveComposer wave = new WaveComposer(
            4, () -> false, sampler, sampler.seed, MC_VERSION, false, LOD, originX, originZ,
            sampler
        );
        final List<Thread> members = new ArrayList<>();
        final boolean[] completed = runAllMembers(wave, 4, members);
        joinAllMembers(members);

        assertEquals(
            4,
            Collections.frequency(sampler.perTileCalls, "overviewHeightsCoarse"),
            "a refused batch must send every member through the per-tile stage"
        );
        assertEquals(2, sampler.batchCalls.size(), "both stages must attempt their batch before refusing");
        for (int i = 0; i < 4; i++) {
            assertTrue(completed[i], "member " + i + " must complete");
            final BaselineGrid reference = LodSampling.sample(sampler, false, LOD, originX[i], originZ[i]);
            assertArrayEquals(reference.terrainY, wave.grid(i).terrainY, "member " + i + " terrain diverged");
            assertArrayEquals(reference.biomeId, wave.grid(i).biomeId, "member " + i + " biomes diverged");
            assertArrayEquals(reference.baseSurfaceY, wave.grid(i).baseSurfaceY, "member " + i + " surface diverged");
        }
    }

    /**
     * The wave leader must re-validate the wave's identity immediately before every batch call:
     * a context whose seed drifted from the admission values dissolves the wave with the batch
     * never invoked.
     */
    @Test
    void leaderGenerationRevalidatedAtDispatch() throws InterruptedException {
        final RecordingWaveSampler sampler = new RecordingWaveSampler();
        sampler.mutateSeedOnContextProbe = true;
        final WaveComposer wave = wave(sampler, 4, () -> false);
        final List<Thread> members = new ArrayList<>();
        final boolean[] completed = runAllMembers(wave, 4, members);
        joinAllMembers(members);

        for (int i = 0; i < 4; i++) {
            assertFalse(completed[i], "an identity-mismatched wave must drop every member");
        }
        assertTrue(wave.terminated(), "the leader's re-validation must terminate the phaser");
        assertTrue(
            sampler.batchCalls.isEmpty(),
            "a context that no longer matches admission must never reach the batch call"
        );
    }

    /**
     * With debug timing on, every wave member must account the sampling it consumed - its own
     * per-tile stage calls plus its share of the shared batch dispatches - the way a legacy
     * single-shot composition does.
     */
    @Test
    void waveMembersAccountSamplingWhenTimingEnabled() throws InterruptedException {
        final RecordingWaveSampler sampler = new RecordingWaveSampler();
        final int[] originX = {4096, 8192, 12288, 16384};
        final int[] originZ = {-8192, 4096, 0, 12288};
        final WaveComposer wave = new WaveComposer(
            4, () -> false, sampler, sampler.seed, MC_VERSION, true, LOD, originX, originZ,
            sampler
        );
        final List<Thread> members = new ArrayList<>();
        final boolean[] completed = runAllMembers(wave, 4, members);
        joinAllMembers(members);

        for (int i = 0; i < 4; i++) {
            assertTrue(completed[i], "member " + i + " must complete");
            assertTrue(wave.sampledPoints(i) > 0, "member " + i + " must account its sampled cells");
            assertNotNull(wave.stageNanos(i), "member " + i + " must account its stage times");
        }
    }

    private static WaveComposer wave(
        final RecordingWaveSampler sampler, final int waveSize, final BooleanSupplier expired
    ) {
        final int[] originX = {4096, 8192, 12288, 16384, 20480, 24576, 28672, 32768};
        final int[] originZ = {-8192, 4096, 0, 12288, 4096, -8192, 12288, 0};
        return new WaveComposer(waveSize, expired, sampler, sampler.seed, MC_VERSION, false, LOD, originX, originZ, sampler);
    }

    private static boolean[] runAllMembers(final WaveComposer wave, final int waveSize, final List<Thread> members) {
        final boolean[] completed = new boolean[waveSize];
        for (int i = 0; i < waveSize; i++) {
            final int index = i;
            final Thread member = new Thread(() -> completed[index] = wave.runMember(index), "wave-member-" + index);
            members.add(member);
            member.start();
        }
        return completed;
    }

    /** The only clock in this suite: every member must return on its own within the join window. */
    private static void joinAllMembers(final List<Thread> members) throws InterruptedException {
        for (final Thread member : members) {
            member.join(5000L);
            assertFalse(member.isAlive(), "wave member " + member.getName() + " never returned");
        }
    }

    private static boolean fullyComposed(final BaselineGrid grid) {
        for (final int surface : grid.baseSurfaceY) {
            if (surface == BaselineGrid.NO_SURFACE) {
                return false;
            }
        }
        return true;
    }

}
