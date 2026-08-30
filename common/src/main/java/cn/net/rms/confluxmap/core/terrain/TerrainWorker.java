package cn.net.rms.confluxmap.core.terrain;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Non-blocking in-process terrain calculation module.
 *
 * <p>A single control thread preserves command ordering while a bounded calculation pool performs
 * chunk decoding and floor selection. Callers communicate only with detached terrain values and
 * never expose Minecraft state to worker threads.</p>
 */
public final class TerrainWorker implements AutoCloseable {
    private static final int MAX_CACHED_CHUNKS = 8192;
    private static final long MAX_CACHED_BYTES = 96L * 1024L * 1024L;
    private static final int MAX_PENDING_COMMANDS = MAX_CACHED_CHUNKS * 2;
    private static final int MAX_PENDING_COMPUTATIONS = MAX_CACHED_CHUNKS * 2;
    private static final int MAX_PENDING_RESULTS = MAX_CACHED_CHUNKS;
    private static final int COMPUTATION_THREADS = Math.min(
        4, Math.max(1, Runtime.getRuntime().availableProcessors() / 2)
    );
    private static final Runnable POISON = () -> { };

    private final BlockingQueue<Runnable> commands = new ArrayBlockingQueue<>(
        MAX_PENDING_COMMANDS
    );
    private final CaveFloorEngine engine = new CaveFloorEngine();
    private final Map<Integer, MaterialDescriptor> materials = new HashMap<>();
    private final LinkedHashMap<Long, CachedChunk> chunks = new LinkedHashMap<>(
        64, 0.75f, true
    );
    private final Set<Integer> requestedMaterials = new LinkedHashSet<>();
    private final Object stateLock = new Object();
    private final Object materialLock = new Object();
    private final LinkedHashSet<Integer> materialRequests = new LinkedHashSet<>();
    private final Object resultLock = new Object();
    private final LinkedHashMap<Long, TerrainResult> results = new LinkedHashMap<>();
    private final AtomicLong threadIds = new AtomicLong();
    private final ThreadPoolExecutor computation = new ThreadPoolExecutor(
        COMPUTATION_THREADS,
        COMPUTATION_THREADS,
        0L,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(MAX_PENDING_COMPUTATIONS),
        runnable -> daemon(
            "Conflux terrain calculation " + threadIds.incrementAndGet(), runnable
        ),
        new ThreadPoolExecutor.DiscardPolicy()
    );
    private final AtomicLong epoch = new AtomicLong();
    private final AtomicReference<String> fault = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread control = daemon("Conflux terrain control", this::controlLoop);

    private volatile TerrainView view;
    private volatile long cachedSessionToken = Long.MIN_VALUE;
    private long cachedBytes;
    private long expectedSessionToken = Long.MIN_VALUE;
    private long expectedGeneration = Long.MIN_VALUE;

    public TerrainWorker() {
        control.start();
    }

    public boolean updateView(final TerrainView next) {
        expectResults(next.sessionToken(), next.generation());
        return enqueue(() -> reset(next));
    }

    public boolean submit(final EncodedChunk chunk) {
        return enqueue(() -> acceptChunk(chunk));
    }

    public boolean submit(final TerrainDelta delta) {
        return enqueue(() -> acceptDelta(delta));
    }

    public boolean invalidate(
        final long sessionToken, final int chunkX, final int chunkZ
    ) {
        return enqueue(() -> invalidateChunk(sessionToken, chunkX, chunkZ));
    }

    public boolean pause() {
        discardResults();
        return enqueue(this::applyPause);
    }

    public boolean submitMaterials(final Map<Integer, MaterialDescriptor> additions) {
        if (additions.isEmpty()) {
            return true;
        }
        final Map<Integer, MaterialDescriptor> snapshot = Map.copyOf(additions);
        return enqueue(() -> acceptMaterials(snapshot));
    }

    public MaterialRequest pollMaterialRequest() {
        synchronized (materialLock) {
            return removeMaterialRequest();
        }
    }

    public MaterialRequest awaitMaterialRequest(final Duration timeout)
        throws InterruptedException {
        final long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (materialLock) {
            MaterialRequest request;
            while ((request = removeMaterialRequest()) == null) {
                final long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    return null;
                }
                TimeUnit.NANOSECONDS.timedWait(materialLock, remaining);
            }
            return request;
        }
    }

    public TerrainResult pollResult() {
        synchronized (resultLock) {
            return removeFirstResult();
        }
    }

    public TerrainResult awaitResult(final Duration timeout) throws InterruptedException {
        final long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (resultLock) {
            TerrainResult result;
            while ((result = removeFirstResult()) == null) {
                final long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    return null;
                }
                TimeUnit.NANOSECONDS.timedWait(resultLock, remaining);
            }
            return result;
        }
    }

    public boolean isHealthy() {
        return !closed.get() && fault.get() == null && control.isAlive();
    }

    public String fault() {
        return fault.get();
    }

    private boolean enqueue(final Runnable command) {
        if (!isHealthy()) {
            return false;
        }
        if (commands.offer(command)) {
            return true;
        }
        fail("terrain worker queue is full");
        return false;
    }

    private void controlLoop() {
        try {
            while (true) {
                final Runnable command = commands.take();
                if (command == POISON) {
                    return;
                }
                command.run();
            }
        } catch (final InterruptedException interrupted) {
            if (!closed.get() && fault.get() == null) {
                Thread.currentThread().interrupt();
                fail("terrain worker control thread was interrupted");
            }
        } catch (final Throwable cause) {
            fail(cause);
        }
    }

    private void reset(final TerrainView next) {
        synchronized (stateLock) {
            if (cachedSessionToken != next.sessionToken()) {
                chunks.clear();
                cachedBytes = 0L;
                materials.clear();
                requestedMaterials.clear();
                cachedSessionToken = next.sessionToken();
            }
            view = next;
        }
        final long nextEpoch = epoch.incrementAndGet();
        computation.getQueue().clear();
        scheduleAll(next, nextEpoch);
    }

    private void applyPause() {
        view = null;
        epoch.incrementAndGet();
        computation.getQueue().clear();
    }

    private void acceptChunk(final EncodedChunk encoded) {
        if (encoded.sessionToken() != cachedSessionToken) {
            return;
        }
        final CachedChunk chunk;
        synchronized (stateLock) {
            final long key = chunkKey(encoded.chunkX(), encoded.chunkZ());
            final CachedChunk existing = chunks.get(key);
            if (existing == null) {
                final CachedChunk fresh = new CachedChunk(encoded);
                chunks.put(key, fresh);
                cachedBytes += fresh.estimatedBytes();
                chunk = fresh;
            } else {
                cachedBytes -= existing.estimatedBytes();
                existing.replace(encoded);
                cachedBytes += existing.estimatedBytes();
                chunk = existing;
            }
            trimCache(view);
        }
        final TerrainView target = view;
        if (target != null) {
            scheduleOne(chunk, target, epoch.get());
        } else {
            discoverMaterials(chunk, encoded.sessionToken());
        }
    }

    private void acceptMaterials(final Map<Integer, MaterialDescriptor> additions) {
        synchronized (stateLock) {
            materials.putAll(additions);
            requestedMaterials.removeAll(additions.keySet());
        }
        final TerrainView target = view;
        if (target != null) {
            computation.getQueue().clear();
            scheduleAll(target, epoch.get());
        }
    }

    private void invalidateChunk(
        final long sessionToken, final int chunkX, final int chunkZ
    ) {
        if (sessionToken != cachedSessionToken) {
            return;
        }
        synchronized (stateLock) {
            final CachedChunk removed = chunks.remove(chunkKey(chunkX, chunkZ));
            if (removed != null) {
                cachedBytes -= removed.estimatedBytes();
                removed.invalidate();
            }
        }
    }

    private void acceptDelta(final TerrainDelta delta) {
        if (delta.sessionToken() != cachedSessionToken) {
            return;
        }
        final CachedChunk chunk;
        synchronized (stateLock) {
            chunk = chunks.get(chunkKey(delta.chunkX(), delta.chunkZ()));
            if (chunk == null) {
                return;
            }
            cachedBytes -= chunk.estimatedBytes();
            if (!chunk.update(delta)) {
                cachedBytes += chunk.estimatedBytes();
                return;
            }
            cachedBytes += chunk.estimatedBytes();
            trimCache(view);
        }
        final TerrainView target = view;
        requestMaterials(Set.of(delta.stateId()), delta.sessionToken());
        if (target != null) {
            scheduleOne(chunk, target, epoch.get());
        }
    }

    private void scheduleAll(final TerrainView target, final long targetEpoch) {
        if (target == null) {
            return;
        }
        final List<CachedChunk> snapshot;
        synchronized (stateLock) {
            snapshot = new ArrayList<>(chunks.values());
        }
        final long centerX = ((long) target.minChunkX() + target.maxChunkX()) / 2L;
        final long centerZ = ((long) target.minChunkZ() + target.maxChunkZ()) / 2L;
        snapshot.sort((left, right) -> {
            final boolean leftVisible = target.contains(left.chunkX(), left.chunkZ());
            final boolean rightVisible = target.contains(right.chunkX(), right.chunkZ());
            if (leftVisible != rightVisible) {
                return leftVisible ? -1 : 1;
            }
            final long leftDistance = Math.max(
                Math.abs(left.chunkX() - centerX), Math.abs(left.chunkZ() - centerZ)
            );
            final long rightDistance = Math.max(
                Math.abs(right.chunkX() - centerX), Math.abs(right.chunkZ() - centerZ)
            );
            return Long.compare(leftDistance, rightDistance);
        });
        for (final CachedChunk chunk : snapshot) {
            computation.execute(() -> calculate(chunk, target, targetEpoch));
        }
    }

    private void scheduleOne(
        final CachedChunk chunk, final TerrainView target, final long targetEpoch
    ) {
        if (target != null) {
            computation.execute(() -> calculate(chunk, target, targetEpoch));
        }
    }

    private void calculate(
        final CachedChunk chunk, final TerrainView target, final long targetEpoch
    ) {
        if (!current(target, targetEpoch)) {
            return;
        }
        try {
            final Map<Integer, MaterialDescriptor> materialSnapshot;
            synchronized (stateLock) {
                materialSnapshot = Map.copyOf(materials);
            }
            final ChunkVolume decoded = chunk.decode();
            if (decoded == null) {
                return;
            }
            final CaveChunkResult result = engine.select(
                decoded, target.pivotY(), materialSnapshot
            );
            if (!current(target, targetEpoch) || !chunk.valid()
                || result.revision() != chunk.revision()) {
                return;
            }
            acceptResult(new TerrainResult(
                target.sessionToken(), target.generation(), result
            ));
        } catch (final MissingMaterialsException missing) {
            requestMaterials(missing.stateIds(), target.sessionToken());
        } catch (final IOException cause) {
            fail(cause);
        } catch (final Throwable cause) {
            fail(cause);
        }
    }

    private boolean current(final TerrainView target, final long targetEpoch) {
        final TerrainView current = view;
        return epoch.get() == targetEpoch && current != null
            && current.sessionToken() == target.sessionToken()
            && current.generation() == target.generation();
    }

    private void discoverMaterials(final CachedChunk chunk, final long sessionToken) {
        computation.execute(() -> {
            try {
                if (sessionToken != cachedSessionToken || !chunk.valid()) {
                    return;
                }
                final ChunkVolume decoded = chunk.decode();
                if (decoded == null) {
                    return;
                }
                final Set<Integer> stateIds = new LinkedHashSet<>();
                for (final int stateId : decoded.stateIds()) {
                    stateIds.add(stateId);
                }
                requestMaterials(stateIds, sessionToken);
            } catch (final Throwable cause) {
                fail(cause);
            }
        });
    }

    private void requestMaterials(final Set<Integer> stateIds, final long sessionToken) {
        final Set<Integer> fresh = new LinkedHashSet<>(stateIds);
        synchronized (stateLock) {
            if (cachedSessionToken != sessionToken) {
                return;
            }
            fresh.removeAll(materials.keySet());
            fresh.removeAll(requestedMaterials);
            requestedMaterials.addAll(fresh);
        }
        if (fresh.isEmpty()) {
            return;
        }
        synchronized (materialLock) {
            materialRequests.addAll(fresh);
            materialLock.notifyAll();
        }
    }

    private void trimCache(final TerrainView target) {
        while (chunks.size() > MAX_CACHED_CHUNKS || cachedBytes > MAX_CACHED_BYTES) {
            Long victim = null;
            if (target != null) {
                for (final Map.Entry<Long, CachedChunk> entry : chunks.entrySet()) {
                    if (!withinGuard(target, entry.getValue().chunkX(), entry.getValue().chunkZ())) {
                        victim = entry.getKey();
                        break;
                    }
                }
            }
            if (victim == null) {
                final Iterator<Long> iterator = chunks.keySet().iterator();
                if (!iterator.hasNext()) {
                    cachedBytes = 0L;
                    return;
                }
                victim = iterator.next();
            }
            final CachedChunk removed = chunks.remove(victim);
            if (removed != null) {
                cachedBytes -= removed.estimatedBytes();
                removed.invalidate();
            }
        }
    }

    private static boolean withinGuard(
        final TerrainView target, final int chunkX, final int chunkZ
    ) {
        return (long) chunkX >= (long) target.minChunkX() - 1L
            && (long) chunkX <= (long) target.maxChunkX() + 1L
            && (long) chunkZ >= (long) target.minChunkZ() - 1L
            && (long) chunkZ <= (long) target.maxChunkZ() + 1L;
    }

    private void expectResults(final long sessionToken, final long generation) {
        synchronized (resultLock) {
            expectedSessionToken = sessionToken;
            expectedGeneration = generation;
            results.clear();
        }
    }

    private void discardResults() {
        synchronized (resultLock) {
            expectedSessionToken = Long.MIN_VALUE;
            expectedGeneration = Long.MIN_VALUE;
            results.clear();
        }
    }

    private void acceptResult(final TerrainResult result) {
        synchronized (resultLock) {
            if (result.sessionToken() != expectedSessionToken
                || result.generation() != expectedGeneration) {
                return;
            }
            final long key = chunkKey(result.result().chunkX(), result.result().chunkZ());
            final TerrainResult existing = results.get(key);
            if (existing != null && existing.result().revision() > result.result().revision()) {
                return;
            }
            if (!results.containsKey(key) && results.size() >= MAX_PENDING_RESULTS) {
                final Iterator<Long> iterator = results.keySet().iterator();
                if (iterator.hasNext()) {
                    iterator.next();
                    iterator.remove();
                }
            }
            results.put(key, result);
            resultLock.notifyAll();
        }
    }

    private MaterialRequest removeMaterialRequest() {
        if (materialRequests.isEmpty()) {
            return null;
        }
        final MaterialRequest request = new MaterialRequest(materialRequests);
        materialRequests.clear();
        return request;
    }

    private TerrainResult removeFirstResult() {
        final Iterator<Map.Entry<Long, TerrainResult>> iterator = results.entrySet().iterator();
        if (!iterator.hasNext()) {
            return null;
        }
        final TerrainResult result = iterator.next().getValue();
        iterator.remove();
        return result;
    }

    private void fail(final Throwable cause) {
        fail(cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
    }

    private void fail(final String message) {
        fault.compareAndSet(null, message);
        view = null;
        epoch.incrementAndGet();
        computation.shutdownNow();
        commands.clear();
        if (Thread.currentThread() != control) {
            control.interrupt();
        }
        synchronized (materialLock) {
            materialLock.notifyAll();
        }
        synchronized (resultLock) {
            resultLock.notifyAll();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        commands.clear();
        commands.offer(POISON);
        view = null;
        epoch.incrementAndGet();
        computation.shutdownNow();
        try {
            control.join(TimeUnit.SECONDS.toMillis(2));
            computation.awaitTermination(2, TimeUnit.SECONDS);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        synchronized (materialLock) {
            materialLock.notifyAll();
        }
        synchronized (resultLock) {
            resultLock.notifyAll();
        }
    }

    private static long chunkKey(final int x, final int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static Thread daemon(final String name, final Runnable target) {
        final Thread thread = new Thread(target, name);
        thread.setDaemon(true);
        return thread;
    }
}
