package cn.net.rms.confluxmap.core.cache;

import cn.net.rms.confluxmap.core.store.MapWorld;
import cn.net.rms.confluxmap.core.store.MapWorldService;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.tile.TileService;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import org.apache.logging.log4j.Logger;

/**
 * Owns the durable map session boundary: it seals and rotates {@link MapWorldService}, then queues
 * the ending world's final flush before exposing the next session's disk cache.
 */
public final class RegionCacheService {
    private final Path root;
    private final MapWorldService mapWorlds;
    private final MapExecutors executors;
    private final TileService tiles;
    private final Logger logger;

    private volatile RegionDiskCache current;
    private final Map<WorldIdentity, CompletableFuture<Void>> endingFlushes = new ConcurrentHashMap<>();

    public RegionCacheService(
        final Path root,
        final MapWorldService mapWorlds,
        final MapExecutors executors,
        final TileService tiles,
        final Logger logger
    ) {
        this.root = root;
        this.mapWorlds = mapWorlds;
        this.executors = executors;
        this.tiles = tiles;
        this.logger = logger;
    }

    /** Main thread, from the session tracker. */
    public void onSessionChanged(final SessionGuard.Session session) {
        final MapWorld endingWorld = mapWorlds.switchSession(session);
        final RegionDiskCache endingCache = current;
        if (endingCache != null && endingWorld != null) {
            final CompletableFuture<Void> flush = endingCache.flushAllOnSessionEnd(endingWorld);
            endingFlushes.put(endingWorld.session().world(), flush);
            flush.whenComplete((ignored, error) -> endingFlushes.remove(endingWorld.session().world(), flush));
        }
        current = session.active() ? new RegionDiskCache(root, session, mapWorlds, executors, tiles, logger) : null;
    }

    /**
     * Blocks a destructive profile move until the last session flush for that world has finished.
     * The wait is only used by explicit profile deletion, never by the per-tick session path.
     */
    public void awaitFlush(final WorldIdentity world) {
        final CompletableFuture<Void> flush = endingFlushes.get(world);
        boolean interrupted = false;
        try {
            if (flush != null) {
                while (true) {
                    try {
                        flush.get();
                        break;
                    } catch (final InterruptedException error) {
                        interrupted = true;
                    } catch (final ExecutionException error) {
                        break;
                    }
                }
            }
            // Even when the region future has already been removed, a waypoint/annotation save
            // may still be queued behind it on the shared single-thread IO executor.
            try {
                awaitIoIdle();
            } catch (final InterruptedException error) {
                interrupted = true;
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void awaitIoIdle() throws InterruptedException {
        final Future<?> marker;
        try {
            marker = executors.io().submit(() -> { });
        } catch (final RejectedExecutionException error) {
            return;
        }
        try {
            marker.get();
        } catch (final ExecutionException error) {
            // The marker itself has no user work; a failed queue task is already logged by its
            // owner and must not prevent the deletion transaction from making progress.
        }
    }

    /** The disk cache for the active session, or null between sessions. */
    public RegionDiskCache current() {
        return current;
    }
}
