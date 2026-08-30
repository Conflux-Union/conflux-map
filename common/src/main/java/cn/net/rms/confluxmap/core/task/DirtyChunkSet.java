package cn.net.rms.confluxmap.core.task;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Chunks whose map data is stale. Filled from packet hooks and drained on the
 * main thread with a per-tick budget, nearest to the player first so the area
 * around the viewport updates before distant terrain. Main thread only.
 *
 * <p>A drain also asks whether each candidate is worth sampling yet (see {@link
 * Readiness}). Chunks that are loaded but still missing neighbours are held back
 * rather than sampled against data the client does not have. A generic {@link
 * Readiness#WAITING} hold expires after {@link #MAX_DEFERRALS} drains so an unknown edge
 * cannot stay blank forever. A chunk that reaches the deadline is sampled once with the
 * caller's edge-safe fallback and remembered in {@link #degraded}; later marks cannot produce
 * more fallback snapshots, but the chunk remains eligible for one final full-quality capture
 * after its expected neighbours arrive.
 */
public final class DirtyChunkSet {
    /**
     * How many drains a chunk may be held back waiting for its neighbours before it is
     * sampled at full priority. One drain per client tick, so this is about a second -
     * long enough that normal chunk streaming completes a neighbourhood first, short
     * enough to be invisible at the map's edge.
     *
     * <p>Measured from the drain the chunk was marked on, not from the drains that happened
     * to look at it. The permanently incomplete ring is also the farthest thing from the
     * player, so it sorts last and a nearer chunk arriving each tick can keep the budget
     * spent before the scan ever reaches it; a hold that only aged on inspection would
     * never expire and that ring would stay off the map for as long as chunks keep arriving.
     */
    static final int MAX_DEFERRALS = 20;

    /** Chunk key to the value of {@link #drains} when it was marked. */
    private final Map<Long, Integer> dirty = new HashMap<>();
    /** Chunks already returned once while still waiting for neighbours. */
    private final Set<Long> degraded = new HashSet<>();

    /** Drains so far - the clock the {@link #MAX_DEFERRALS} hold is measured against. */
    private int drains;

    private static long key(final int chunkX, final int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    /** Marking an already-dirty chunk keeps the drain it was first marked on, so re-marks cannot starve it. */
    public void mark(final int chunkX, final int chunkZ) {
        dirty.putIfAbsent(key(chunkX, chunkZ), drains);
    }

    /** Removes work already completed by a higher-priority queue. */
    public void discard(final int chunkX, final int chunkZ) {
        final long key = key(chunkX, chunkZ);
        dirty.remove(key);
        degraded.remove(key);
    }

    /**
     * Marks ({@code chunkX}, {@code chunkZ}) plus every chunk of its surrounding 3x3 square
     * that {@code loaded} accepts. Used when a chunk's arrival also invalidates what its
     * already-captured neighbours sampled across the shared border; neighbours the predicate
     * rejects are left alone, since a chunk that is not loaded yet cannot be snapshotted and
     * would only burn a drain slot before marking itself on arrival.
     */
    public void markWithLoadedNeighbors(final int chunkX, final int chunkZ, final ChunkPredicate loaded) {
        mark(chunkX, chunkZ);
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if ((dx != 0 || dz != 0) && loaded.test(chunkX + dx, chunkZ + dz)) {
                    mark(chunkX + dx, chunkZ + dz);
                }
            }
        }
    }

    public int size() {
        return dirty.size();
    }

    public void clear() {
        dirty.clear();
        degraded.clear();
    }

    /** Keeps only dirty chunks accepted by {@code predicate}. */
    public void retain(final ChunkPredicate predicate) {
        dirty.keySet().removeIf(chunk ->
            !predicate.test((int) (chunk >> 32), (int) chunk.longValue())
        );
        degraded.removeIf(chunk ->
            !predicate.test((int) (chunk >> 32), (int) chunk.longValue())
        );
    }

    /**
     * Removes and returns up to {@code budget} chunks that {@code readiness} accepts, closest
     * to ({@code centerChunkX}, {@code centerChunkZ}) first.
     *
     * <p>Candidates the readiness check rejects do not consume budget: {@link
     * Readiness#MISSING} ones are dropped (they mark themselves again when they arrive) and
     * {@link Readiness#WAITING} ones are held for a later drain, so the budget goes to
     * chunks that can be sampled now.
     *
     * <p>Waiting chunks are not allowed to consume otherwise-idle budget until their hold expires.
     * Normal streaming usually completes the neighbourhood during that hold, avoiding a partial
     * snapshot followed immediately by one or more replacement snapshots. The bounded hold still
     * guarantees that the permanently incomplete outer ring eventually appears.
     */
    public List<long[]> drainNearest(
        final int budget,
        final int centerChunkX,
        final int centerChunkZ,
        final ChunkReadiness readiness
    ) {
        if (dirty.isEmpty() || budget <= 0) {
            return List.of();
        }
        final int drain = ++drains;
        final List<Long> keys = new ArrayList<>(dirty.keySet());
        keys.sort((a, b) -> Long.compare(
            distanceSq(a, centerChunkX, centerChunkZ),
            distanceSq(b, centerChunkX, centerChunkZ)
        ));
        final List<long[]> result = new ArrayList<>(Math.min(budget, keys.size()));
        for (final long key : keys) {
            if (result.size() >= budget) {
                break;
            }
            final int chunkX = (int) (key >> 32);
            final int chunkZ = (int) key;
            final Readiness state = readiness.of(chunkX, chunkZ);
            if (state == Readiness.MISSING) {
                dirty.remove(key);
                degraded.remove(key);
            } else if (state == Readiness.READY) {
                dirty.remove(key);
                degraded.remove(key);
                result.add(new long[]{chunkX, chunkZ});
            } else if (degraded.contains(key)
                || drain - dirty.get(key) < MAX_DEFERRALS) {
                continue;
            } else {
                dirty.remove(key);
                degraded.add(key);
                result.add(new long[]{chunkX, chunkZ});
            }
        }
        return result;
    }

    private static long distanceSq(final long key, final int centerX, final int centerZ) {
        final long dx = (key >> 32) - centerX;
        final long dz = (int) key - centerZ;
        return dx * dx + dz * dz;
    }

    /** Tests a chunk position, for {@link #markWithLoadedNeighbors}. */
    @FunctionalInterface
    public interface ChunkPredicate {
        boolean test(int chunkX, int chunkZ);
    }

    /** Whether a dirty chunk can be sampled right now, for {@link #drainNearest}. */
    @FunctionalInterface
    public interface ChunkReadiness {
        Readiness of(int chunkX, int chunkZ);
    }

    /** Outcome of a {@link ChunkReadiness} check. */
    public enum Readiness {
        /** Sample it now. */
        READY,
        /** Sampling it now would read data the client does not have yet; try again later. */
        WAITING,
        /** An expected streaming neighbor is missing; allow at most one degraded fallback. */
        AWAITING_NEIGHBORS,
        /** Not loaded at all - drop it and wait for the arrival hook to mark it again. */
        MISSING
    }
}
