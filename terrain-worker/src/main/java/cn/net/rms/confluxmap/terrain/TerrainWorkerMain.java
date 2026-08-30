package cn.net.rms.confluxmap.terrain;

import cn.net.rms.confluxmap.terrain.protocol.CaveChunkResult;
import cn.net.rms.confluxmap.terrain.protocol.EncodedChunk;
import cn.net.rms.confluxmap.terrain.protocol.MaterialDescriptor;
import cn.net.rms.confluxmap.terrain.protocol.TerrainCodec;
import cn.net.rms.confluxmap.terrain.protocol.TerrainDelta;
import cn.net.rms.confluxmap.terrain.protocol.TerrainResult;
import cn.net.rms.confluxmap.terrain.protocol.TerrainWire;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class TerrainWorkerMain {
    private static final int MAX_CACHED_CHUNKS = 8192;
    private static final long MAX_CACHED_BYTES = Math.min(
        96L * 1024L * 1024L, Runtime.getRuntime().maxMemory() / 3L
    );
    private static final int COMPUTATION_THREADS = Math.min(
        4, Math.max(1, Runtime.getRuntime().availableProcessors() / 2)
    );
    private static final int MAX_PENDING_COMPUTATIONS = MAX_CACHED_CHUNKS * 2;

    private final DataInputStream input;
    private final DataOutputStream output;
    private final CaveFloorEngine engine = new CaveFloorEngine();
    private final Map<Integer, MaterialDescriptor> materials = new HashMap<>();
    private final LinkedHashMap<Long, CachedChunk> chunks = new LinkedHashMap<>(64, 0.75f, true);
    private final Set<Integer> requestedMaterials = new LinkedHashSet<>();
    private final Object stateLock = new Object();
    private final AtomicLong threadIds = new AtomicLong();
    private final ThreadPoolExecutor computation = new ThreadPoolExecutor(
        COMPUTATION_THREADS,
        COMPUTATION_THREADS,
        0L,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(MAX_PENDING_COMPUTATIONS),
        runnable -> {
            final Thread thread = new Thread(runnable, "Conflux terrain calculation");
            thread.setName(thread.getName() + " " + threadIds.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        },
        new ThreadPoolExecutor.DiscardPolicy()
    );
    private final AtomicLong epoch = new AtomicLong();
    private volatile TerrainCodec.View view;
    private volatile long cachedSessionToken = Long.MIN_VALUE;
    private long cachedBytes;

    private TerrainWorkerMain(final DataInputStream input, final DataOutputStream output) {
        this.input = input;
        this.output = output;
    }

    public static void main(final String[] args) {
        final TerrainWorkerMain worker = new TerrainWorkerMain(
            new DataInputStream(System.in), new DataOutputStream(System.out)
        );
        try {
            worker.run();
        } catch (final Throwable fault) {
            worker.fail(fault);
        }
    }

    private void run() throws Exception {
        final TerrainWire.Frame hello = TerrainWire.readFrame(input);
        if (hello == null || hello.type() != TerrainWire.HELLO) {
            throw new IOException("terrain worker requires HELLO");
        }
        TerrainCodec.requireHello(hello.payload());
        TerrainWire.writeFrame(output, TerrainWire.READY, TerrainCodec.hello());

        TerrainWire.Frame frame;
        while ((frame = TerrainWire.readFrame(input)) != null) {
            switch (frame.type()) {
                case TerrainWire.CHUNK -> acceptChunk(TerrainCodec.decodeChunk(frame.payload()));
                case TerrainWire.MATERIALS -> acceptMaterials(
                    TerrainCodec.decodeMaterials(frame.payload())
                );
                case TerrainWire.BLOCK_DELTA -> acceptDelta(
                    TerrainCodec.decodeDelta(frame.payload())
                );
                case TerrainWire.RESET -> reset(TerrainCodec.decodeView(frame.payload()));
                case TerrainWire.PAUSE -> pause();
                case TerrainWire.INVALIDATE_CHUNK -> invalidateChunk(
                    TerrainCodec.decodeChunkRef(frame.payload())
                );
                case TerrainWire.CLOSE -> {
                    computation.shutdownNow();
                    return;
                }
                default -> throw new IOException("unknown terrain message: " + frame.type());
            }
        }
    }

    private void reset(final TerrainCodec.View next) throws Exception {
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

    private void pause() {
        view = null;
        epoch.incrementAndGet();
        computation.getQueue().clear();
    }

    private void acceptChunk(final EncodedChunk encoded) throws Exception {
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
        final TerrainCodec.View target = view;
        if (target != null) {
            scheduleOne(chunk, target, epoch.get());
        } else {
            discoverMaterials(chunk, encoded.sessionToken());
        }
    }

    private void acceptMaterials(final Map<Integer, MaterialDescriptor> additions)
        throws Exception {
        synchronized (stateLock) {
            materials.putAll(additions);
            requestedMaterials.removeAll(additions.keySet());
        }
        final TerrainCodec.View target = view;
        if (target != null) {
            computation.getQueue().clear();
            scheduleAll(target, epoch.get());
        }
    }

    private void invalidateChunk(final TerrainCodec.ChunkRef ref) {
        if (ref.sessionToken() != cachedSessionToken) {
            return;
        }
        synchronized (stateLock) {
            final CachedChunk removed = chunks.remove(chunkKey(ref.chunkX(), ref.chunkZ()));
            if (removed != null) {
                cachedBytes -= removed.estimatedBytes();
                removed.invalidate();
            }
        }
    }

    private void acceptDelta(final TerrainDelta delta) throws Exception {
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
        final TerrainCodec.View target = view;
        requestMaterials(Set.of(delta.stateId()), delta.sessionToken());
        if (target != null) {
            scheduleOne(chunk, target, epoch.get());
        }
    }

    private void scheduleAll(final TerrainCodec.View target, final long targetEpoch) {
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
            computation.execute(() -> process(chunk, target, targetEpoch));
        }
    }

    private void scheduleOne(
        final CachedChunk chunk, final TerrainCodec.View target, final long targetEpoch
    ) {
        if (target != null) {
            computation.execute(() -> process(chunk, target, targetEpoch));
        }
    }

    private void process(
        final CachedChunk chunk, final TerrainCodec.View target, final long targetEpoch
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
            write(
                TerrainWire.RESULT,
                TerrainCodec.result(new TerrainResult(
                    target.sessionToken(), target.generation(), result
                ))
            );
        } catch (final MissingMaterialsException missing) {
            requestMaterials(missing.stateIds(), target.sessionToken());
        } catch (final IOException fault) {
            fail(fault);
        } catch (final Throwable fault) {
            fail(fault);
        }
    }

    private boolean current(final TerrainCodec.View target, final long targetEpoch) {
        final TerrainCodec.View current = view;
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
            } catch (final Throwable fault) {
                fail(fault);
            }
        });
    }

    private void requestMaterials(
        final Set<Integer> stateIds, final long sessionToken
    ) {
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
        try {
            write(TerrainWire.MATERIAL_REQUEST, TerrainCodec.materialRequest(fresh));
        } catch (final IOException fault) {
            fail(fault);
        }
    }

    private void trimCache(final TerrainCodec.View target) {
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
        final TerrainCodec.View target, final int chunkX, final int chunkZ
    ) {
        return (long) chunkX >= (long) target.minChunkX() - 1L
            && (long) chunkX <= (long) target.maxChunkX() + 1L
            && (long) chunkZ >= (long) target.minChunkZ() - 1L
            && (long) chunkZ <= (long) target.maxChunkZ() + 1L;
    }

    private void write(final byte type, final byte[] payload) throws IOException {
        synchronized (output) {
            TerrainWire.writeFrame(output, type, payload);
        }
    }

    private void fail(final Throwable fault) {
        try {
            write(
                TerrainWire.ERROR,
                TerrainCodec.error(fault.getMessage() == null
                    ? fault.getClass().getSimpleName() : fault.getMessage())
            );
        } catch (final IOException ignored) {
            // The parent will observe the process exit.
        }
        System.exit(1);
    }

    private static long chunkKey(final int x, final int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }
}
