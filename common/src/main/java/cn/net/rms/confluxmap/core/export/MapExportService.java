package cn.net.rms.confluxmap.core.export;

import cn.net.rms.confluxmap.core.task.SessionGuard;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** Owns the single bounded background PNG export and its temporary-file lifecycle. */
public final class MapExportService implements AutoCloseable {
    private static final long DISK_MARGIN_BYTES = 64L * 1024L * 1024L;
    private static final long MIN_HEAP_HEADROOM_BYTES = 128L * 1024L * 1024L;
    private static final long SLOW_TICK_NANOS = 500_000_000L;
    private static final int MAX_CONSECUTIVE_SLOW_TICKS = 5;
    private static final StopReason USER_CANCELLED = new StopReason(true, null);
    private static final StopReason SLOW_CLIENT_TICKS = new StopReason(
        false,
        "Map export stopped after five consecutive client tick intervals exceeded 500 ms"
    );
    private static final StopReason SESSION_CHANGED = new StopReason(
        false, "Map session changed during export"
    );
    private static final StopReason SERVICE_CLOSED = new StopReason(
        false, "Map export service closed"
    );

    private final Path exportDir;
    private final SessionGuard sessions;
    private final Function<MapExportRequest, MapExportTileSource> sources;
    private final ExecutorService executor;
    private final AtomicReference<StopReason> stopReason = new AtomicReference<>();

    private volatile MapExportStatus status = MapExportStatus.idle();
    private volatile Path activeSpool;
    private volatile Path activePart;
    private volatile boolean closed;
    private volatile long lastTickNanos;
    private volatile int consecutiveSlowTicks;

    public MapExportService(
        final Path exportDir,
        final SessionGuard sessions,
        final Function<MapExportRequest, MapExportTileSource> sources
    ) {
        this.exportDir = Objects.requireNonNull(exportDir, "exportDir");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.sources = Objects.requireNonNull(sources, "sources");
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "ConfluxMap-Export");
            thread.setDaemon(true);
            return thread;
        });
    }

    public synchronized void start(final MapExportRequest request) {
        Objects.requireNonNull(request, "request");
        if (closed) {
            throw new IllegalStateException("Map export service is closed");
        }
        if (status.active()) {
            throw new IllegalStateException("A map export is already active");
        }
        stopReason.set(null);
        consecutiveSlowTicks = 0;
        lastTickNanos = 0L;
        status = new MapExportStatus(MapExportStatus.State.RASTERIZING, 0L, 0L, null, null);
        executor.execute(() -> runExport(request));
    }

    public MapExportStatus status() {
        return status;
    }

    public void cancel() {
        requestStop(USER_CANCELLED);
    }

    /** Client-thread watchdog; sustained half-second tick gaps stop the export with an error. */
    public void tick() {
        if (!status.active()) {
            lastTickNanos = 0L;
            consecutiveSlowTicks = 0;
            return;
        }
        final long now = System.nanoTime();
        final long previous = lastTickNanos;
        lastTickNanos = now;
        if (previous != 0L && now - previous > SLOW_TICK_NANOS) {
            if (++consecutiveSlowTicks >= MAX_CONSECUTIVE_SLOW_TICKS) {
                requestStop(SLOW_CLIENT_TICKS);
            }
        } else {
            consecutiveSlowTicks = 0;
        }
    }

    private void runExport(final MapExportRequest request) {
        Path spool = null;
        Path part = null;
        try {
            Files.createDirectories(exportDir);
            preflightDisk(request);
            final Path output = uniqueOutput(request);
            spool = output.resolveSibling(output.getFileName() + ".argb.tmp");
            part = output.resolveSibling(output.getFileName() + ".part");
            activeSpool = spool;
            activePart = part;
            final Path visibleOutput = output;
            MapExportRasterizer.rasterize(
                request,
                sources.apply(request),
                spool,
                (completed, total) -> status = new MapExportStatus(
                    MapExportStatus.State.RASTERIZING,
                    completed,
                    total,
                    visibleOutput,
                    null
                ),
                () -> unhealthy(request)
            );
            checkHealthy(request);
            status = new MapExportStatus(
                MapExportStatus.State.ENCODING, 0L, request.pixelHeight(), visibleOutput, null
            );
            StreamingPngWriter.write(
                spool,
                part,
                request.pixelWidth(),
                request.pixelHeight(),
                () -> unhealthy(request),
                completedRows -> status = new MapExportStatus(
                    MapExportStatus.State.ENCODING,
                    completedRows,
                    request.pixelHeight(),
                    visibleOutput,
                    null
                )
            );
            checkHealthy(request);
            moveComplete(part, output);
            part = null;
            status = new MapExportStatus(
                MapExportStatus.State.COMPLETED, request.pixelHeight(), request.pixelHeight(), output, null
            );
        } catch (final CancellationException e) {
            final StopReason reason = stopReason.get();
            status = reason != null && reason.userCancelled()
                ? new MapExportStatus(MapExportStatus.State.CANCELLED, 0L, 0L, null, null)
                : failedStatus(reason == null ? failureMessage(e) : reason.error());
        } catch (final Exception | OutOfMemoryError e) {
            status = failedStatus(e);
        } finally {
            deleteQuietly(spool);
            deleteQuietly(part);
            activeSpool = null;
            activePart = null;
        }
    }

    private boolean unhealthy(final MapExportRequest request) {
        if (stopReason.get() != null) {
            return true;
        }
        if (!sessions.isCurrent(request.session().token())) {
            requestStop(SESSION_CHANGED);
            return true;
        }
        final Runtime runtime = Runtime.getRuntime();
        final long used = runtime.totalMemory() - runtime.freeMemory();
        final long headroom = runtime.maxMemory() - used;
        final long required = Math.max(MIN_HEAP_HEADROOM_BYTES, runtime.maxMemory() / 10L);
        if (headroom < required) {
            requestStop(new StopReason(false,
                "Not enough heap memory to continue map export: "
                    + toMib(headroom) + " MiB available, " + toMib(required) + " MiB required"
            ));
            return true;
        }
        return false;
    }

    private void checkHealthy(final MapExportRequest request) {
        if (unhealthy(request)) {
            throw new CancellationException("Map export health guard cancelled the task");
        }
    }

    private void preflightDisk(final MapExportRequest request) throws IOException {
        final FileStore store = Files.getFileStore(exportDir);
        final long rasterAndPng = saturatingMultiply(request.spoolBytes(), 2L);
        final long required = saturatingAdd(rasterAndPng, DISK_MARGIN_BYTES);
        if (store.getUsableSpace() < required) {
            throw new IOException("Not enough disk space for map export");
        }
    }

    private Path uniqueOutput(final MapExportRequest request) {
        final String base = sanitize(request.session().world().serverId()) + "-"
            + sanitize(request.session().world().worldId()) + "-"
            + sanitize(request.session().dimension().fileName()) + "-"
            + request.bounds().minX() + "_" + request.bounds().minZ() + "-"
            + request.bounds().maxX() + "_" + request.bounds().maxZ() + "-"
            + System.currentTimeMillis();
        Path candidate = exportDir.resolve(base + ".png");
        int suffix = 2;
        while (Files.exists(candidate)) {
            candidate = exportDir.resolve(base + "-" + suffix++ + ".png");
        }
        return candidate;
    }

    private static String sanitize(final String value) {
        final String safe = value.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.isEmpty() ? "unknown" : safe;
    }

    private static void moveComplete(final Path part, final Path output) throws IOException {
        try {
            Files.move(part, output, StandardCopyOption.ATOMIC_MOVE);
        } catch (final AtomicMoveNotSupportedException e) {
            Files.move(part, output);
        }
    }

    private static long saturatingMultiply(final long left, final long right) {
        try {
            return Math.multiplyExact(left, right);
        } catch (final ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    private static long saturatingAdd(final long left, final long right) {
        try {
            return Math.addExact(left, right);
        } catch (final ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    private static void deleteQuietly(final Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (final IOException ignored) {
            // A later manual cleanup can remove a file the OS still has open.
        }
    }

    private void requestStop(final StopReason reason) {
        stopReason.compareAndSet(null, reason);
    }

    private static long toMib(final long bytes) {
        return bytes / (1024L * 1024L);
    }

    private static MapExportStatus failedStatus(final Throwable error) {
        return failedStatus(failureMessage(error));
    }

    private static MapExportStatus failedStatus(final String message) {
        return new MapExportStatus(MapExportStatus.State.FAILED, 0L, 0L, null, message);
    }

    private static String failureMessage(final Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    @Override
    public void close() {
        closed = true;
        requestStop(SERVICE_CLOSED);
        executor.shutdownNow();
        try {
            executor.awaitTermination(2L, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        deleteQuietly(activeSpool);
        deleteQuietly(activePart);
    }

    private record StopReason(boolean userCancelled, String error) {
    }
}
