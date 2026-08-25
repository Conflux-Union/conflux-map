package cn.net.rms.confluxmap.core.task;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Chunks whose map data is stale. Filled from packet hooks and drained on the
 * main thread with a per-tick budget, nearest to the player first so the area
 * around the viewport updates before distant terrain. Main thread only.
 *
 * <p>A drain also asks whether each candidate is worth sampling yet (see {@link
 * Readiness}). Chunks that are loaded but still missing neighbours are held back
 * rather than sampled against data the client does not have - but only against
 * competing work: budget left over once every sampleable chunk has been taken goes
 * to the held ones, and a hold expires outright after {@link #MAX_DEFERRALS} drains.
 * At the edge of the server's send distance the missing neighbours never arrive, and
 * a map that refuses to draw its own outermost ring would be worse than one drawn
 * from a slightly narrower biome blend.
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

    /** Drains so far - the clock the {@link #MAX_DEFERRALS} hold is measured against. */
    private int drains;

    private static long key(final int chunkX, final int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    /** Marking an already-dirty chunk keeps the drain it was first marked on, so re-marks cannot starve it. */
    public void mark(final int chunkX, final int chunkZ) {
        dirty.putIfAbsent(key(chunkX, chunkZ), drains);
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
     * <p>Budget still unspent once the scan has taken every sampleable chunk it reached goes
     * to the held ones, nearest first. Holding them past that point would leave the map's
     * outermost ring blank while the capture pipeline sits idle - the ring is exactly the part
     * whose neighbourhood never completes, so waiting for it means waiting forever. What such
     * a chunk bakes is already clamped to the loaded part of its neighbourhood, and an arriving
     * neighbour re-marks it for a full-quality resample.
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
        final List<Long> held = new ArrayList<>();
        for (final long key : keys) {
            if (result.size() >= budget) {
                break;
            }
            final int chunkX = (int) (key >> 32);
            final int chunkZ = (int) key;
            final Readiness state = readiness.of(chunkX, chunkZ);
            if (state == Readiness.MISSING) {
                dirty.remove(key);
            } else if (state == Readiness.WAITING && drain - dirty.get(key) < MAX_DEFERRALS) {
                held.add(key);
            } else {
                dirty.remove(key);
                result.add(new long[]{chunkX, chunkZ});
            }
        }
        for (final long key : held) {
            if (result.size() >= budget) {
                break;
            }
            dirty.remove(key);
            result.add(new long[]{(int) (key >> 32), (int) key});
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
        /** Not loaded at all - drop it and wait for the arrival hook to mark it again. */
        MISSING
    }
}
