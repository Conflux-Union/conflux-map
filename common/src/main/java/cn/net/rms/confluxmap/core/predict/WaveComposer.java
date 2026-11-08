package cn.net.rms.confluxmap.core.predict;

import java.util.Arrays;
import java.util.concurrent.Phaser;
import java.util.function.BooleanSupplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Executes one admitted wave of same-lod/layer Overworld tiles through {@link LodSampling}'s
 * staged skeleton. One worker thread per member calls {@link #runMember(int)}; stages advance
 * across a {@link Phaser} with a fixed party count, so a member only ever waits for wave-mates
 * that admission already reserved composition slots for - no timeout machinery anywhere. Every
 * barrier first asks {@code expired} (the service's generation and session token) and dissolves
 * the whole wave on mismatch before any further dispatch; a member whose stage throws dissolves
 * the wave so its mates observe termination at their next barrier instead of waiting. Batchable
 * stages ({@link LodSampling.OverworldStage#H_LATTICE}, {@link
 * LodSampling.OverworldStage#ANCHOR_BIOMES}) dispatch once through {@link BatchDispatch} when
 * construction provides the seam, per tile otherwise - a null seam (CPU backend, or an engine
 * the admission gate found unavailable or refusal-latched) keeps every stage per tile on every
 * member; a batch bitmap failure or whole-batch refusal re-runs that stage for the affected
 * members through the plain per-tile path. The leader
 * re-validates the wave's identity (shared generation via {@code expired}, and its context's
 * seed/mc via {@link BatchDispatch#batchContextMatches}) immediately before every batch call and
 * dissolves the wave on mismatch - a batch would borrow the wrong generator, so there is no
 * per-tile fallback for that case.
 */
final class WaveComposer {
    private static final Logger LOGGER = LogManager.getLogger("ConfluxMap/PredictionTiles");

    /** Spec cap on one wave's size. */
    static final int WAVE_CAP = 8;

    /**
     * Batch dispatch seam matching the shim's entries: {@code 0} = attempted (set the
     * {@code failMask} bit of every failed tile), nonzero = refused, dispatch per tile instead.
     * The voronoi-sha probe of the native entry stays inside the implementation, which owns its
     * seed.
     */
    interface BatchDispatch {
        int overviewHeightsCoarseBatch(
            int[] blockX, int[] blockZ, int[] w, int[] h, int[] stride, int n,
            int[] outTerrainY, int[] failMask
        );

        int surfaceBiomesBatch(
            int[] blockX, int[] blockZ, int[] w, int[] h, int[] stride, int[] terrainY, int n,
            int[] outBiomeIds, int[] failMask
        );

        /**
         * Whether the calling thread's batch context still serves {@code (seed, mcVersion)} - the
         * wave's admission identity. The real implementation guarantees this by construction: it
         * resolves the calling thread's context for its own fixed identity inside the batch
         * entries. Fakes model a mutable context so the coordinator's pre-dispatch re-validation
         * is observable.
         */
        boolean batchContextMatches(long seed, int mcVersion);
    }

    /** Member 0 leads every batch stage; it is a full party like any other member. */
    private static final int LEADER = 0;

    private final BooleanSupplier expired;
    private final BaselineSampler sampler;
    private final BatchDispatch batch;
    /** The admission identity the leader re-validates against before every batch call. */
    private final long seed;
    private final int mcVersion;
    /**
     * Per-member accounting wrappers over the shared sampler (debug timing only); null when
     * timing is off. One wrapper per member keeps {@link CountingSampler}'s single-worker
     * contract: a member's wrapper is touched on its own thread and, between barriers, by the
     * leader applying batch credits or per-tile repairs for it.
     */
    private final CountingSampler[] accounting;
    private final LodSampling.OverworldStageState[] members;
    private final Phaser barriers;
    /** Members whose tile is already decided (stage failure or precheck bypass); they still attend barriers. */
    private final boolean[] dropped;
    private volatile boolean dissolved;
    /** Result of the retained single-shot path for a degenerate one-member wave. */
    private BaselineGrid legacyGrid;

    WaveComposer(
        final int waveSize,
        final BooleanSupplier expired,
        final BaselineSampler sampler,
        final long seed,
        final int mcVersion,
        final boolean timing,
        final int lod,
        final int[] tileOriginX,
        final int[] tileOriginZ,
        final BatchDispatch batch
    ) {
        this.expired = expired;
        this.sampler = sampler;
        this.seed = seed;
        this.mcVersion = mcVersion;
        this.batch = batch;
        this.accounting = timing ? new CountingSampler[waveSize] : null;
        if (accounting != null) {
            for (int index = 0; index < waveSize; index++) {
                accounting[index] = new CountingSampler(sampler);
            }
        }
        this.barriers = new Phaser(waveSize);
        this.dropped = new boolean[waveSize];
        this.members = new LodSampling.OverworldStageState[waveSize];
        for (int index = 0; index < waveSize; index++) {
            members[index] = new LodSampling.OverworldStageState(
                lod, tileOriginX[index], tileOriginZ[index],
                LodSampling.newOverworldGrid(lod, tileOriginX[index], tileOriginZ[index])
            );
        }
    }

    /**
     * Worker entry: blocks through all stages for member {@code index}. Returns false when the
     * wave dissolved (expired or a member failed) or the member's own tile was dropped, true
     * when {@link #grid(int)} holds the fully staged result.
     */
    boolean runMember(final int index) {
        if (members.length == 1) {
            return runSingleMember();
        }
        try {
            for (final LodSampling.OverworldStage stage : LodSampling.OverworldStage.values()) {
                if (!reunite()) {
                    return false;
                }
                if (batch != null && stage == LodSampling.OverworldStage.H_LATTICE) {
                    if (index == LEADER) {
                        dispatchBatchStage(stage);
                    }
                } else if (batch != null && stage == LodSampling.OverworldStage.ANCHOR_BIOMES) {
                    if (index == LEADER) {
                        dispatchBatchStage(stage);
                    }
                } else if (!dropped[index]
                    && !LodSampling.runOverworldStage(stage, members[index], samplerFor(index))) {
                    dropped[index] = true;
                }
            }
            return !dropped[index];
        } catch (final Throwable fault) {
            LOGGER.warn(
                "wave member {} (tile {},{} seed {} mc {} lod {}) failed in a stage; wave dissolved",
                index,
                members[index].tileOriginX, members[index].tileOriginZ,
                seed, mcVersion, members[index].lod,
                fault
            );
            dissolve();
            return false;
        }
    }

    /** The member's accounted sampled cells when the wave was built with timing on; 0 otherwise. */
    long sampledPoints(final int index) {
        return accounting != null ? accounting[index].points() : 0L;
    }

    /**
     * The member's accounted per-stage nanos when the wave was built with timing on; null
     * otherwise. Quiescent once {@link #runMember} returned: the last writes for a member (its
     * own stages, or the leader's batch credits/repairs for it) all precede a barrier it
     * advanced past.
     */
    long[] stageNanos(final int index) {
        return accounting != null ? accounting[index].stageNanos() : null;
    }

    /** The member's accounting wrapper when timing is on; the shared sampler otherwise. */
    private BaselineSampler samplerFor(final int index) {
        return accounting != null ? accounting[index] : sampler;
    }

    /** The member's composed grid once {@link #runMember} returned true. */
    BaselineGrid grid(final int index) {
        return index == LEADER && legacyGrid != null ? legacyGrid : members[index].grid;
    }

    /** Marks a member's tile decided before its stages (a precheck bypass); it still attends barriers. */
    void bypassMember(final int index) {
        dropped[index] = true;
    }

    /** Dissolves the wave from outside the stage loop (a member task rejected or failed around it). */
    void dissolve() {
        dissolved = true;
        barriers.forceTermination();
    }

    boolean terminated() {
        return barriers.isTerminated();
    }

    /** One stage barrier: reunites all members, then drops the wave whole when the session moved on. */
    private boolean reunite() {
        if (dissolved) {
            return false;
        }
        if (barriers.arriveAndAwaitAdvance() < 0) {
            return false;
        }
        if (dissolved || expired.getAsBoolean()) {
            dissolve();
            return false;
        }
        return true;
    }

    private boolean runSingleMember() {
        final LodSampling.OverworldStageState member = members[LEADER];
        try {
            legacyGrid = LodSampling.sample(
                samplerFor(LEADER), false, member.lod, member.tileOriginX, member.tileOriginZ
            );
        } catch (final Throwable fault) {
            LOGGER.warn(
                "single-member wave (tile {},{} seed {} mc {} lod {}) failed; tile dropped",
                member.tileOriginX, member.tileOriginZ, seed, mcVersion, member.lod, fault
            );
            dissolve();
        }
        return legacyGrid != null;
    }

    /**
     * Leader-only batch stage: builds the parallel arrays from the members that still need the
     * stage, dispatches once, and applies the results (re-running failed tiles per tile). An
     * expiry observed after dispatch discards the results at distribution instead of applying
     * them.
     */
    private void dispatchBatchStage(final LodSampling.OverworldStage stage) {
        if (expired.getAsBoolean() || dissolved) {
            dissolve();
            return;
        }
        final int size = members.length;
        final LodSampling.StageQuery[] queries = new LodSampling.StageQuery[size];
        final int[] eligible = new int[size];
        int count = 0;
        for (int index = 0; index < size; index++) {
            if (dropped[index]) {
                continue;
            }
            final LodSampling.StageQuery query = stage == LodSampling.OverworldStage.H_LATTICE
                ? LodSampling.planLatticeQuery(members[index], sampler)
                : LodSampling.planAnchorBiomeQuery(members[index]);
            if (query != null) {
                eligible[count] = index;
                queries[count] = query;
                count++;
            }
        }
        if (count == 0) {
            return;
        }
        final int[] blockX = new int[count];
        final int[] blockZ = new int[count];
        final int[] w = new int[count];
        final int[] h = new int[count];
        final int[] stride = new int[count];
        int totalCells = 0;
        for (int k = 0; k < count; k++) {
            blockX[k] = queries[k].blockX;
            blockZ[k] = queries[k].blockZ;
            w[k] = queries[k].w;
            h[k] = queries[k].h;
            stride[k] = queries[k].stride;
            totalCells += w[k] * h[k];
        }
        final int[] packedTerrain = new int[totalCells];
        final int[] out = new int[totalCells];
        if (stage == LodSampling.OverworldStage.ANCHOR_BIOMES) {
            int offset = 0;
            for (int k = 0; k < count; k++) {
                System.arraycopy(queries[k].terrainY, 0, packedTerrain, offset, queries[k].w * queries[k].h);
                offset += queries[k].w * queries[k].h;
            }
        }
        final int[] failMask = new int[1];
        if (!batch.batchContextMatches(seed, mcVersion)) {
            // The leader's context drifted from admission: batch results would mix generators,
            // so the wave drops whole instead of falling back to per-tile dispatch.
            dissolve();
            return;
        }
        final long batchStartNanos = accounting != null ? System.nanoTime() : 0L;
        final int attempted = stage == LodSampling.OverworldStage.H_LATTICE
            ? batch.overviewHeightsCoarseBatch(blockX, blockZ, w, h, stride, count, out, failMask)
            : batch.surfaceBiomesBatch(blockX, blockZ, w, h, stride, packedTerrain, count, out, failMask);
        final long batchNanos = accounting != null ? System.nanoTime() - batchStartNanos : 0L;
        if (expired.getAsBoolean() || dissolved) {
            dissolve();
            return;
        }
        if (attempted != 0) {
            for (int k = 0; k < count; k++) {
                runPerTileStage(stage, eligible[k]);
            }
            return;
        }
        int offset = 0;
        for (int k = 0; k < count; k++) {
            final int cells = queries[k].w * queries[k].h;
            final int member = eligible[k];
            if ((failMask[0] >>> k & 1) != 0) {
                runPerTileStage(stage, member);
            } else if (stage == LodSampling.OverworldStage.H_LATTICE) {
                LodSampling.applyLatticeQuery(
                    members[member], sampler, Arrays.copyOfRange(out, offset, offset + cells)
                );
                if (accounting != null) {
                    // Dense-plane members batched alongside lattice ones land in the H bucket.
                    accounting[member].credit(
                        cells, batchNanos / count,
                        members[member].lattice != null ? SampleStage.ANCH : SampleStage.H
                    );
                }
            } else {
                LodSampling.applyAnchorBiomeQuery(
                    members[member], Arrays.copyOfRange(out, offset, offset + cells)
                );
                if (accounting != null) {
                    accounting[member].credit(cells, batchNanos / count, SampleStage.B);
                }
            }
            offset += cells;
        }
    }

    private void runPerTileStage(final LodSampling.OverworldStage stage, final int member) {
        if (!LodSampling.runOverworldStage(stage, members[member], samplerFor(member))) {
            dropped[member] = true;
        }
    }
}
