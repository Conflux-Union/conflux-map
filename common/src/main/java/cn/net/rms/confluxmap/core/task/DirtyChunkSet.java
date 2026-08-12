package cn.net.rms.confluxmap.core.task;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Chunks whose map data is stale. Filled from packet hooks and drained on the
 * main thread with a per-tick budget, nearest to the player first so the area
 * around the viewport updates before distant terrain. Main thread only.
 */
public final class DirtyChunkSet {
    private final Set<Long> dirty = new HashSet<>();

    private static long key(final int chunkX, final int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    public void mark(final int chunkX, final int chunkZ) {
        dirty.add(key(chunkX, chunkZ));
    }

    public int size() {
        return dirty.size();
    }

    public void clear() {
        dirty.clear();
    }

    /**
     * Remove and return up to {@code budget} chunks, closest to
     * ({@code centerChunkX}, {@code centerChunkZ}) first.
     */
    public List<long[]> drainNearest(final int budget, final int centerChunkX, final int centerChunkZ) {
        return drainNearestMatching(budget, centerChunkX, centerChunkZ, (chunkX, chunkZ) -> true);
    }

    /**
     * Remove and return up to {@code budget} eligible chunks, nearest to the player first.
     * Ineligible entries remain dirty. This matters for client capture: a viewport seed may name
     * chunks that the server has not delivered yet, and removing those coordinates would turn a
     * temporary loading gap into a permanent hole in the map.
     */
    public List<long[]> drainNearestMatching(
        final int budget,
        final int centerChunkX,
        final int centerChunkZ,
        final ChunkEligibility eligibility
    ) {
        if (dirty.isEmpty() || budget <= 0) {
            return List.of();
        }
        final List<Long> keys = dirty.stream()
            .filter(key -> eligibility.test((int) (key >> 32), (int) (long) key))
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        keys.sort((a, b) -> Long.compare(
            distanceSq(a, centerChunkX, centerChunkZ),
            distanceSq(b, centerChunkX, centerChunkZ)
        ));
        final int take = Math.min(budget, keys.size());
        final List<long[]> result = new ArrayList<>(take);
        for (int i = 0; i < take; i++) {
            final long key = keys.get(i);
            dirty.remove(key);
            result.add(new long[]{key >> 32, (int) key});
        }
        return result;
    }

    @FunctionalInterface
    public interface ChunkEligibility {
        boolean test(int chunkX, int chunkZ);
    }

    private static long distanceSq(final long key, final int centerX, final int centerZ) {
        final long dx = (key >> 32) - centerX;
        final long dz = (int) key - centerZ;
        return dx * dx + dz * dz;
    }
}
