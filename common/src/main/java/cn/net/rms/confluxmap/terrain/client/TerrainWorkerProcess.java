package cn.net.rms.confluxmap.terrain.client;

import cn.net.rms.confluxmap.terrain.protocol.EncodedChunk;
import cn.net.rms.confluxmap.terrain.protocol.MaterialDescriptor;
import cn.net.rms.confluxmap.terrain.protocol.MaterialRequest;
import cn.net.rms.confluxmap.terrain.protocol.TerrainCodec;
import cn.net.rms.confluxmap.terrain.protocol.TerrainDelta;
import cn.net.rms.confluxmap.terrain.protocol.TerrainResult;
import cn.net.rms.confluxmap.terrain.protocol.TerrainWire;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Non-blocking parent-side connection to one terrain worker JVM. */
public final class TerrainWorkerProcess implements AutoCloseable {
    private static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_PENDING_RESULTS = 8192;
    private static final Outbound POISON = new Outbound((byte) -1, null);

    private final Process process;
    private final DataInputStream input;
    private final DataOutputStream output;
    private final BlockingQueue<Outbound> outgoing = new LinkedBlockingQueue<>(16384);
    private final Object materialLock = new Object();
    private final LinkedHashSet<Integer> materialRequests = new LinkedHashSet<>();
    private final Object resultLock = new Object();
    private final LinkedHashMap<Long, TerrainResult> results = new LinkedHashMap<>();
    private final AtomicReference<String> fault = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread writer;
    private final Thread reader;
    private final Thread stderr;
    private long expectedSessionToken = Long.MIN_VALUE;
    private long expectedGeneration = Long.MIN_VALUE;

    private TerrainWorkerProcess(final Process process) throws Exception {
        this.process = process;
        input = new DataInputStream(process.getInputStream());
        output = new DataOutputStream(process.getOutputStream());
        handshake();
        writer = daemon("Conflux terrain worker writer", this::writeLoop);
        reader = daemon("Conflux terrain worker reader", this::readLoop);
        stderr = daemon("Conflux terrain worker stderr", this::stderrLoop);
        writer.start();
        reader.start();
        stderr.start();
    }

    public static TerrainWorkerProcess launch(
        final Path javaExecutable, final Path workerJar
    ) throws Exception {
        return launch(javaExecutable, workerJar, 256);
    }

    static TerrainWorkerProcess launch(
        final Path javaExecutable, final Path workerJar, final int maximumHeapMiB
    ) throws Exception {
        Objects.requireNonNull(javaExecutable, "javaExecutable");
        Objects.requireNonNull(workerJar, "workerJar");
        if (!Files.isRegularFile(workerJar)) {
            throw new IOException("terrain worker jar is unavailable: " + workerJar);
        }
        if (maximumHeapMiB < 16) {
            throw new IllegalArgumentException("terrain worker heap must be at least 16 MiB");
        }
        final int initialHeapMiB = Math.min(32, maximumHeapMiB);
        final Process process = new ProcessBuilder(
            javaExecutable.toString(), "-Xms" + initialHeapMiB + "m",
            "-Xmx" + maximumHeapMiB + "m", "-jar", workerJar.toString()
        ).start();
        try {
            return new TerrainWorkerProcess(process);
        } catch (final Throwable fault) {
            process.destroyForcibly();
            throw fault;
        }
    }

    public void updateView(
        final long sessionToken, final long generation, final int pivotY
    ) throws IOException {
        expectResults(sessionToken, generation);
        enqueue(TerrainWire.RESET, TerrainCodec.view(sessionToken, generation, pivotY));
    }

    public void updateView(
        final long sessionToken,
        final long generation,
        final int pivotY,
        final int minChunkX,
        final int maxChunkX,
        final int minChunkZ,
        final int maxChunkZ
    ) throws IOException {
        expectResults(sessionToken, generation);
        enqueue(TerrainWire.RESET, TerrainCodec.view(
            sessionToken, generation, pivotY,
            minChunkX, maxChunkX, minChunkZ, maxChunkZ
        ));
    }

    public void submit(final EncodedChunk chunk) throws IOException {
        enqueueDeferred(TerrainWire.CHUNK, () -> TerrainCodec.chunk(chunk));
    }

    public void submit(final TerrainDelta delta) throws IOException {
        enqueue(TerrainWire.BLOCK_DELTA, TerrainCodec.delta(delta));
    }

    public void invalidate(
        final long sessionToken, final int chunkX, final int chunkZ
    ) throws IOException {
        enqueue(
            TerrainWire.INVALIDATE_CHUNK,
            TerrainCodec.chunkRef(sessionToken, chunkX, chunkZ)
        );
    }

    public void pause() throws IOException {
        discardResults();
        enqueue(TerrainWire.PAUSE, new byte[0]);
    }

    public void submitMaterials(final Map<Integer, MaterialDescriptor> materials)
        throws IOException {
        if (!materials.isEmpty()) {
            enqueue(TerrainWire.MATERIALS, TerrainCodec.materials(materials));
        }
    }

    public MaterialRequest pollMaterialRequest() {
        synchronized (materialLock) {
            return removeMaterialRequest();
        }
    }

    public MaterialRequest awaitMaterialRequest(final Duration timeout) throws InterruptedException {
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
        return !closed.get() && process.isAlive() && fault.get() == null;
    }

    public String fault() {
        return fault.get();
    }

    private void handshake() throws Exception {
        writeNow(TerrainWire.HELLO, TerrainCodec.hello());
        final CompletableFuture<TerrainWire.Frame> response = CompletableFuture.supplyAsync(() -> {
            try {
                return TerrainWire.readFrame(input);
            } catch (final IOException fault) {
                throw new java.util.concurrent.CompletionException(fault);
            }
        });
        final TerrainWire.Frame frame = response.get(
            HANDSHAKE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS
        );
        if (frame == null || frame.type() != TerrainWire.READY) {
            throw new IOException("terrain worker did not become ready");
        }
        TerrainCodec.requireHello(frame.payload());
    }

    private void enqueue(final byte type, final byte[] payload) throws IOException {
        enqueueDeferred(type, () -> payload);
    }

    private void enqueueDeferred(final byte type, final Payload payload) throws IOException {
        if (!isHealthy()) {
            throw new IOException(fault.get() == null ? "terrain worker is not running" : fault.get());
        }
        if (!outgoing.offer(new Outbound(type, payload))) {
            throw new IOException("terrain worker queue is full");
        }
    }

    private void writeLoop() {
        try {
            while (true) {
                final Outbound message = outgoing.take();
                if (message == POISON) {
                    return;
                }
                writeNow(message.type(), message.payload().encode());
            }
        } catch (final Throwable writeFault) {
            recordFault(writeFault);
        }
    }

    private void readLoop() {
        try {
            TerrainWire.Frame frame;
            while ((frame = TerrainWire.readFrame(input)) != null) {
                switch (frame.type()) {
                    case TerrainWire.MATERIAL_REQUEST -> acceptMaterialRequest(
                        TerrainCodec.decodeMaterialRequest(frame.payload())
                    );
                    case TerrainWire.RESULT -> acceptResult(
                        TerrainCodec.decodeResult(frame.payload())
                    );
                    case TerrainWire.ERROR -> recordFault(TerrainCodec.decodeError(frame.payload()));
                    default -> throw new IOException("unexpected worker response: " + frame.type());
                }
            }
            if (!closed.get()) {
                recordFault("terrain worker closed its output");
            }
        } catch (final Throwable readFault) {
            if (!closed.get()) {
                recordFault(readFault);
            }
        }
    }

    private void stderrLoop() {
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(
            process.getErrorStream(), StandardCharsets.UTF_8
        ))) {
            String line;
            while ((line = lines.readLine()) != null) {
                if (!line.isBlank()) {
                    fault.compareAndSet(null, line);
                }
            }
        } catch (final IOException ignored) {
            // The protocol stream or process exit supplies the actionable fault.
        }
    }

    private void recordFault(final Throwable cause) {
        recordFault(cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
    }

    private void recordFault(final String message) {
        fault.compareAndSet(null, message);
        synchronized (materialLock) {
            materialLock.notifyAll();
        }
        synchronized (resultLock) {
            resultLock.notifyAll();
        }
    }

    private void acceptMaterialRequest(final MaterialRequest request) {
        synchronized (materialLock) {
            materialRequests.addAll(request.stateIds());
            materialLock.notifyAll();
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
            if (existing != null
                && existing.result().revision() > result.result().revision()) {
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

    private TerrainResult removeFirstResult() {
        final Iterator<Map.Entry<Long, TerrainResult>> iterator = results.entrySet().iterator();
        if (!iterator.hasNext()) {
            return null;
        }
        final TerrainResult result = iterator.next().getValue();
        iterator.remove();
        return result;
    }

    private static long chunkKey(final int chunkX, final int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            writeNow(TerrainWire.CLOSE, new byte[0]);
        } catch (final IOException ignored) {
            // The process may already have failed.
        }
        outgoing.offer(POISON);
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroy();
                if (!process.waitFor(1, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            }
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        synchronized (resultLock) {
            resultLock.notifyAll();
        }
        synchronized (materialLock) {
            materialLock.notifyAll();
        }
    }

    private static Thread daemon(final String name, final Runnable target) {
        final Thread thread = new Thread(target, name);
        thread.setDaemon(true);
        return thread;
    }

    private void writeNow(final byte type, final byte[] payload) throws IOException {
        synchronized (output) {
            TerrainWire.writeFrame(output, type, payload);
        }
    }

    @FunctionalInterface
    private interface Payload {
        byte[] encode() throws IOException;
    }

    private record Outbound(byte type, Payload payload) {
    }
}
