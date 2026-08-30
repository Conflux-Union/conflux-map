package cn.net.rms.confluxmap.mc.snapshot;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.terrain.client.TerrainWorkerProcess;
import cn.net.rms.confluxmap.terrain.protocol.EncodedChunk;
import cn.net.rms.confluxmap.terrain.protocol.MaterialRequest;
import cn.net.rms.confluxmap.terrain.protocol.TerrainDelta;
import cn.net.rms.confluxmap.terrain.protocol.TerrainResult;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Session-aware owner of the forced terrain child process and its one permitted restart. */
final class McTerrainWorker implements AutoCloseable {
    private static final int MAX_REPLAY_CHUNKS = 8192;
    private static final long MAX_REPLAY_BYTES = 96L * 1024L * 1024L;

    private final McTerrainMaterialResolver materials;
    private final LinkedHashMap<Long, EncodedChunk> replay = new LinkedHashMap<>(64, 0.75f, true);
    private final LinkedHashSet<Long> staleReplay = new LinkedHashSet<>();
    private final LinkedHashSet<Integer> pendingMaterials = new LinkedHashSet<>();
    private TerrainWorkerProcess process;
    private long sessionToken;
    private long generation;
    private int pivotY;
    private boolean restarted;
    private boolean paused;
    private boolean active;
    private String fault;
    private Path workerJar;
    private Path extractedWorker;
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
        ensureProcess();
        if (process != null && nextSessionToken != 0L) {
            initializePausedSession();
        } else if (process != null) {
            try {
                process.pause();
            } catch (final IOException pauseFault) {
                handleFault(pauseFault.getMessage());
            }
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
        ensureProcess();
        updateView();
    }

    void pause() {
        if (!active) {
            return;
        }
        active = false;
        generation++;
        if (process == null || paused) {
            return;
        }
        try {
            process.pause();
        } catch (final IOException pauseFault) {
            handleFault(pauseFault.getMessage());
        }
    }

    boolean submit(final EncodedChunk chunk) {
        ensureProcess();
        if (paused || process == null) {
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
        try {
            process.submit(chunk);
            return true;
        } catch (final IOException sendFault) {
            handleFault(sendFault.getMessage());
            return false;
        }
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
        ensureProcess();
        if (paused || process == null || sessionToken == 0L) {
            return;
        }
        try {
            process.invalidate(sessionToken, chunkX, chunkZ);
        } catch (final IOException sendFault) {
            handleFault(sendFault.getMessage());
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
        ensureProcess();
        if (paused || process == null || sessionToken == 0L) {
            return;
        }
        try {
            process.submit(new TerrainDelta(
                sessionToken,
                revision,
                blockX >> 4,
                blockZ >> 4,
                blockX & 15,
                y,
                blockZ & 15,
                stateId
            ));
        } catch (final IOException sendFault) {
            handleFault(sendFault.getMessage());
        }
    }

    void resolveMaterialRequests() {
        ensureProcess();
        if (paused || process == null) {
            return;
        }
        MaterialRequest request;
        while ((request = process.pollMaterialRequest()) != null) {
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
        try {
            process.submitMaterials(materials.resolve(batch));
        } catch (final IOException sendFault) {
            pendingMaterials.addAll(batch);
            handleFault(sendFault.getMessage());
        }
    }

    TerrainResult pollResult() {
        ensureProcess();
        if (paused || process == null) {
            return null;
        }
        TerrainResult result;
        do {
            result = process.pollResult();
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

    private void ensureProcess() {
        if (paused) {
            return;
        }
        if (process == null) {
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
        if (!process.isHealthy()) {
            handleFault(process.fault());
        }
    }

    private void handleFault(final String detail) {
        closeProcess();
        if (restarted) {
            paused = true;
            fault = detail == null ? "terrain worker stopped" : detail;
            ConfluxMapMod.LOGGER.error("Live terrain paused: {}", fault);
            return;
        }
        restarted = true;
        fault = detail;
        launch();
        if (process == null) {
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
            try {
                process.submit(chunk);
            } catch (final IOException replayFault) {
                paused = true;
                fault = replayFault.getMessage();
                closeProcess();
                return;
            }
        }
    }

    private void launch() {
        try {
            process = TerrainWorkerProcess.launch(javaExecutable(), workerJar());
        } catch (final Exception launchFault) {
            process = null;
            fault = launchFault.getMessage();
            if (restarted) {
                paused = true;
            }
            ConfluxMapMod.LOGGER.error("Failed to start terrain worker", launchFault);
        }
    }

    private void updateView() {
        if (process == null || paused || sessionToken == 0L) {
            return;
        }
        try {
            process.updateView(
                sessionToken, generation, pivotY,
                minChunkX, maxChunkX, minChunkZ, maxChunkZ
            );
        } catch (final IOException updateFault) {
            handleFault(updateFault.getMessage());
        }
    }

    private void initializePausedSession() {
        if (process == null || paused || sessionToken == 0L) {
            return;
        }
        try {
            process.updateView(
                sessionToken, generation, pivotY,
                minChunkX, maxChunkX, minChunkZ, maxChunkZ
            );
            process.pause();
        } catch (final IOException updateFault) {
            handleFault(updateFault.getMessage());
        }
    }

    private static Path javaExecutable() {
        final boolean windows = System.getProperty("os.name", "")
            .toLowerCase(java.util.Locale.ROOT).contains("win");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
    }

    private Path workerJar() throws IOException {
        if (workerJar != null) {
            return workerJar;
        }
        final Path extracted = Files.createTempFile("confluxmap-terrain-worker-", ".jar");
        try (InputStream input = McTerrainWorker.class.getResourceAsStream(
            "/assets/confluxmap/worker/terrain-worker.bin"
        )) {
            if (input == null) {
                Files.deleteIfExists(extracted);
                throw new IOException("embedded terrain worker is unavailable");
            }
            Files.copy(input, extracted, StandardCopyOption.REPLACE_EXISTING);
        }
        return rememberExtracted(extracted);
    }

    private Path rememberExtracted(final Path extracted) {
        extracted.toFile().deleteOnExit();
        extractedWorker = extracted;
        workerJar = extracted;
        return workerJar;
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

    private void closeProcess() {
        if (process != null) {
            process.close();
            process = null;
        }
    }

    @Override
    public void close() {
        paused = true;
        closeProcess();
        if (extractedWorker != null) {
            try {
                Files.deleteIfExists(extractedWorker);
            } catch (final IOException cleanupFault) {
                ConfluxMapMod.LOGGER.warn(
                    "Could not remove temporary terrain worker {}", extractedWorker,
                    cleanupFault
                );
            }
            extractedWorker = null;
            workerJar = null;
        }
    }
}
