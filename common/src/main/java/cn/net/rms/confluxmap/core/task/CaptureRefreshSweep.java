package cn.net.rms.confluxmap.core.task;

import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.util.ChunkViewport;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Keeps one coherent visible-layer refresh in progress while newer pivots coalesce.
 * The active viewport may move, but a pivot change never restarts already completed chunks.
 */
public final class CaptureRefreshSweep {
    private final DirtyChunkSet pending = new DirtyChunkSet();
    private final Set<Long> completed = new HashSet<>();

    private MapLayer activeLayer;
    private int activePivotY;
    private ChunkViewport activeViewport;
    private MapLayer targetLayer;
    private int targetPivotY;
    private ChunkViewport targetViewport;

    public void updateTarget(
        final MapLayer layer, final int pivotY, final ChunkViewport viewport
    ) {
        if (layer == null || viewport == null) {
            reset();
            return;
        }
        targetLayer = layer;
        targetPivotY = pivotY;
        targetViewport = viewport;
        if (activeLayer == null || !activeLayer.equals(layer)) {
            beginTarget();
        } else {
            reconcileViewport(viewport);
        }
    }

    public Batch drainNearest(
        final int budget,
        final int centerChunkX,
        final int centerChunkZ,
        final DirtyChunkSet.ChunkReadiness readiness
    ) {
        if (activeLayer == null || budget <= 0) {
            return Batch.empty();
        }
        if (pending.size() == 0 && targetChanged()) {
            beginTarget();
        }
        final List<long[]> chunks = pending.drainNearest(
            budget, centerChunkX, centerChunkZ, readiness
        );
        for (final long[] chunk : chunks) {
            completed.add(key((int) chunk[0], (int) chunk[1]));
        }
        return new Batch(activeLayer, activePivotY, chunks);
    }

    public boolean hasPending() {
        return pending.size() > 0;
    }

    public void markDirty(final int chunkX, final int chunkZ) {
        if (activeViewport == null || !activeViewport.contains(chunkX, chunkZ)) {
            return;
        }
        completed.remove(key(chunkX, chunkZ));
        pending.mark(chunkX, chunkZ);
    }

    public void markWithLoadedNeighbors(
        final int chunkX,
        final int chunkZ,
        final DirtyChunkSet.ChunkPredicate loaded
    ) {
        markDirty(chunkX, chunkZ);
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if ((dx != 0 || dz != 0) && loaded.test(chunkX + dx, chunkZ + dz)) {
                    markDirty(chunkX + dx, chunkZ + dz);
                }
            }
        }
    }

    public void reset() {
        pending.clear();
        completed.clear();
        activeLayer = null;
        activeViewport = null;
        targetLayer = null;
        targetViewport = null;
    }

    private void beginTarget() {
        pending.clear();
        completed.clear();
        activeLayer = targetLayer;
        activePivotY = targetPivotY;
        activeViewport = targetViewport;
        markViewport(activeViewport);
    }

    private boolean targetChanged() {
        return targetLayer != null
            && (!targetLayer.equals(activeLayer) || targetPivotY != activePivotY);
    }

    private void reconcileViewport(final ChunkViewport viewport) {
        if (viewport.equals(activeViewport)) {
            return;
        }
        activeViewport = viewport;
        pending.retain(viewport::contains);
        completed.removeIf(chunk -> !viewport.contains(chunkX(chunk), chunkZ(chunk)));
        markViewport(viewport);
    }

    private void markViewport(final ChunkViewport viewport) {
        for (int chunkZ = viewport.minChunkZ(); chunkZ <= viewport.maxChunkZ(); chunkZ++) {
            for (int chunkX = viewport.minChunkX(); chunkX <= viewport.maxChunkX(); chunkX++) {
                if (!completed.contains(key(chunkX, chunkZ))) {
                    pending.mark(chunkX, chunkZ);
                }
            }
        }
    }

    private static long key(final int chunkX, final int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    private static int chunkX(final long key) {
        return (int) (key >> 32);
    }

    private static int chunkZ(final long key) {
        return (int) key;
    }

    public record Batch(MapLayer layer, int pivotY, List<long[]> chunks) {
        public Batch {
            chunks = List.copyOf(chunks);
        }

        private static Batch empty() {
            return new Batch(null, 0, List.of());
        }
    }
}
