package cn.net.rms.confluxmap.mc.snapshot;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.core.terrain.EncodedChunk;
import cn.net.rms.confluxmap.core.terrain.MaterialRequest;
import cn.net.rms.confluxmap.core.terrain.TerrainDelta;
import cn.net.rms.confluxmap.core.terrain.TerrainResult;
import cn.net.rms.confluxmap.core.terrain.TerrainView;
import cn.net.rms.confluxmap.core.terrain.TerrainWorker;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Session-aware owner of the in-process terrain worker and its one permitted restart. */
final class McTerrainWorker implements AutoCloseable {
    private static final int MAX_REPLAY_CHUNKS = 8192;
    private static final long MAX_REPLAY_BYTES = 96L * 1024L * 1024L;

    private final McTerrainMaterialResolver materials;
    private final LinkedHashMap<Long, EncodedChunk> replay = new LinkedHashMap<>(64, 0.75f, true);
    private final LinkedHashSet<Long> staleReplay = new LinkedHashSet<>();
    private final LinkedHashSet<Integer> pendingMaterials = new LinkedHashSet<>();
    private TerrainWorker worker;
    private long sessionToken;
    private long generation;
    private int pivotY;
    private boolean restarted;
    private boolean paused;
    private boolean active;
    private String fault;
    private long replayBytes;
    private int minChunkX = Integer.MIN_VALUE;
    private int maxChunkX = Integer.MAX_VALUE;
    private int minChunkZ = Integer.MIN_VALUE;
    private int maxChunkZ = Integer.MAX_VALUE;

    McTerrainWorker(final McTerrainMaterialResolver materials) {
        this.materials = materials;
        launch();
    }

    void reset(final long nextSessionToken, final int nextPivotY) {
        sessionToken = nextSessionToken;
        pivotY = nextPivotY;
        generation++;
        replay.clear();
        staleReplay.clear();
        replayBytes = 0L;
        pendingMaterials.clear();
        restarted = false;
        paused = false;
        active = false;
        fault = null;
        ensureWorker();
        if (worker != null && nextSessionToken != 0L) {
            initializePausedSession();
        } else if (worker != null && !worker.pause()) {
            handleFault(worker.fault());
        }
    }

    void updateViewport(final cn.net.rms.confluxmap.core.util.ChunkViewport viewport) {
        final int nextMinX = viewport == null ? Integer.MIN_VALUE : viewport.minChunkX();
        final int nextMaxX = viewport == null ? Integer.MAX_VALUE : viewport.maxChunkX();
        final int nextMinZ = viewport == null ? Integer.MIN_VALUE : viewport.minChunkZ();
        final int nextMaxZ = viewport == null ? Integer.MAX_VALUE : viewport.maxChunkZ();
        if (minChunkX == nextMinX && maxChunkX == nextMaxX
            && minChunkZ == nextMinZ && maxChunkZ == nextMaxZ) {
            return;
        }
        minChunkX = nextMinX;
        maxChunkX = nextMaxX;
        minChunkZ = nextMinZ;
        maxChunkZ = nextMaxZ;
        trimReplay();
        if (active) {
            updateView();
        }
    }

    void updatePivot(final int nextPivotY) {
        if (active && pivotY == nextPivotY) {
            return;
        }
        pivotY = nextPivotY;
        if (active) {
            generation++;
        } else {
            active = true;
        }
        ensureWorker();
        updateView();
    }

    void pause() {
        if (!active) {
            return;
        }
        active = false;
        generation++;
        if (worker == null || paused) {
            return;
        }
        if (!worker.pause()) {
            handleFault(worker.fault());
        }
    }

    boolean submit(final EncodedChunk chunk) {
        ensureWorker();
        if (paused || worker == null) {
            return false;
        }
        final long chunkKey = key(chunk.chunkX(), chunk.chunkZ());
        final EncodedChunk previous = replay.put(chunkKey, chunk);
        if (previous != null) {
            replayBytes -= previous.estimatedBytes();
        }
        replayBytes += chunk.estimatedBytes();
        staleReplay.remove(chunkKey);
        trimReplay();
        if (worker.submit(chunk)) {
            return true;
        }
        handleFault(worker.fault());
        return false;
    }

    boolean prime(final EncodedChunk chunk) {
        return submit(chunk);
    }

    boolean hasFreshChunk(final int chunkX, final int chunkZ) {
        return replay.containsKey(key(chunkX, chunkZ));
    }

    void invalidate(final int chunkX, final int chunkZ) {
        final long chunkKey = key(chunkX, chunkZ);
        final EncodedChunk removed = replay.remove(chunkKey);
        staleReplay.remove(chunkKey);
        if (removed != null) {
            replayBytes -= removed.estimatedBytes();
        }
        ensureWorker();
        if (paused || worker == null || sessionToken == 0L) {
            return;
        }
        if (!worker.invalidate(sessionToken, chunkX, chunkZ)) {
            handleFault(worker.fault());
        }
    }

    void submitDelta(
        final long revision,
        final int blockX,
        final int y,
        final int blockZ,
        final int stateId
    ) {
        final long chunkKey = key(blockX >> 4, blockZ >> 4);
        if (replay.containsKey(chunkKey)) {
            staleReplay.add(chunkKey);
        }
        ensureWorker();
        if (paused || worker == null || sessionToken == 0L) {
            return;
        }
        if (!worker.submit(new TerrainDelta(
                sessionToken,
                revision,
                blockX >> 4,
                blockZ >> 4,
                blockX & 15,
                y,
                blockZ & 15,
                stateId
            ))) {
            handleFault(worker.fault());
        }
    }

    void resolveMaterialRequests() {
        ensureWorker();
        if (paused || worker == null) {
            return;
        }
        MaterialRequest request;
        while ((request = worker.pollMaterialRequest()) != null) {
            pendingMaterials.addAll(request.stateIds());
        }
        if (pendingMaterials.isEmpty()) {
            return;
        }
        final Set<Integer> batch = new LinkedHashSet<>();
        final java.util.Iterator<Integer> iterator = pendingMaterials.iterator();
        while (iterator.hasNext() && batch.size() < 64) {
            batch.add(iterator.next());
            iterator.remove();
        }
        if (!worker.submitMaterials(materials.resolve(batch))) {
            pendingMaterials.addAll(batch);
            handleFault(worker.fault());
        }
    }

    TerrainResult pollResult() {
        ensureWorker();
        if (paused || worker == null) {
            return null;
        }
        TerrainResult result;
        do {
            result = worker.pollResult();
            if (result == null) {
                return null;
            }
        } while (result.sessionToken() != sessionToken || result.generation() != generation);
        return result;
    }

    long generation() {
        return generation;
    }

    boolean paused() {
        return paused;
    }

    String fault() {
        return fault;
    }

    private void ensureWorker() {
        if (paused) {
            return;
        }
        if (worker == null) {
            if (fault != null) {
                if (restarted) {
                    paused = true;
                    return;
                }
                restarted = true;
            }
            launch();
            return;
        }
        if (!worker.isHealthy()) {
            handleFault(worker.fault());
        }
    }

    private void handleFault(final String detail) {
        closeWorker();
        if (restarted) {
            paused = true;
            fault = detail == null ? "terrain worker stopped" : detail;
            ConfluxMapMod.LOGGER.error("Live terrain paused: {}", fault);
            return;
        }
        restarted = true;
        fault = detail;
        launch();
        if (worker == null) {
            paused = true;
            return;
        }
        if (active) {
            updateView();
        } else if (sessionToken != 0L) {
            initializePausedSession();
        }
        for (final long staleKey : staleReplay) {
            final EncodedChunk removed = replay.remove(staleKey);
            if (removed != null) {
                replayBytes -= removed.estimatedBytes();
            }
        }
        staleReplay.clear();
        for (final EncodedChunk chunk : replay.values()) {
            if (!worker.submit(chunk)) {
                paused = true;
                fault = worker.fault();
                closeWorker();
                return;
            }
        }
    }

    private void launch() {
        try {
            worker = new TerrainWorker();
        } catch (final RuntimeException launchFault) {
            worker = null;
            fault = launchFault.getMessage();
            if (restarted) {
                paused = true;
            }
            ConfluxMapMod.LOGGER.error("Failed to start terrain worker", launchFault);
        }
    }

    private void updateView() {
        if (worker == null || paused || sessionToken == 0L) {
            return;
        }
        if (!worker.updateView(new TerrainView(
            sessionToken, generation, pivotY,
            minChunkX, maxChunkX, minChunkZ, maxChunkZ
        ))) {
            handleFault(worker.fault());
        }
    }

    private void initializePausedSession() {
        if (worker == null || paused || sessionToken == 0L) {
            return;
        }
        if (!worker.updateView(new TerrainView(
            sessionToken, generation, pivotY,
            minChunkX, maxChunkX, minChunkZ, maxChunkZ
        ))) {
            handleFault(worker.fault());
            return;
        }
        if (!worker.pause()) {
            handleFault(worker.fault());
        }
    }

    private static long key(final int x, final int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private void trimReplay() {
        while (replay.size() > MAX_REPLAY_CHUNKS || replayBytes > MAX_REPLAY_BYTES) {
            Long victim = null;
            for (final Map.Entry<Long, EncodedChunk> entry : replay.entrySet()) {
                final EncodedChunk chunk = entry.getValue();
                if (!withinViewportGuard(chunk.chunkX(), chunk.chunkZ())) {
                    victim = entry.getKey();
                    break;
                }
            }
            if (victim == null) {
                final java.util.Iterator<Long> iterator = replay.keySet().iterator();
                if (!iterator.hasNext()) {
                    replayBytes = 0L;
                    return;
                }
                victim = iterator.next();
            }
            final EncodedChunk removed = replay.remove(victim);
            if (removed != null) {
                replayBytes -= removed.estimatedBytes();
            }
            staleReplay.remove(victim);
        }
    }

    private boolean withinViewportGuard(final int chunkX, final int chunkZ) {
        return (long) chunkX >= (long) minChunkX - 1L
            && (long) chunkX <= (long) maxChunkX + 1L
            && (long) chunkZ >= (long) minChunkZ - 1L
            && (long) chunkZ <= (long) maxChunkZ + 1L;
    }

    private void closeWorker() {
        if (worker != null) {
            worker.close();
            worker = null;
        }
    }

    @Override
    public void close() {
        paused = true;
        closeWorker();
    }
}
