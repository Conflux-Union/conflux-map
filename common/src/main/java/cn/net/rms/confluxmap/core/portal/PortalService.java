package cn.net.rms.confluxmap.core.portal;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.store.WorldStorageMigration;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.apache.logging.log4j.Logger;

/**
 * Owns the current world's {@link PortalRegistry}, bound to
 * {@code WorldSessionTracker} sessions exactly like
 * {@link cn.net.rms.confluxmap.core.waypoint.WaypointService}: a genuinely new
 * world identity loads that world's file (blocking, main thread - the files
 * are small), the outgoing world is saved before switching away, and every
 * scan that changes state schedules an atomic save on the IO executor.
 * Dimension changes inside one world touch nothing: every dimension of one
 * world lives in the same file.
 */
public final class PortalService {
    private final Path baseDir;
    private final MapExecutors executors;
    private final Logger logger;

    private final PortalRegistry registry = new PortalRegistry();
    private volatile WorldIdentity currentWorld;

    public PortalService(final Path baseDir, final MapExecutors executors, final Logger logger) {
        this.baseDir = baseDir;
        this.executors = executors;
        this.logger = logger;
    }

    /** Main thread only: {@code WorldSessionTracker} listener. */
    public void onSessionChanged(final SessionGuard.Session session) {
        final WorldIdentity newWorld = session.active() ? session.world() : null;
        if (Objects.equals(newWorld, currentWorld)) {
            return;
        }
        if (currentWorld != null) {
            saveNow(currentWorld);
        }
        currentWorld = newWorld;
        registry.load(newWorld == null
            ? Map.of()
            : PortalIo.load(fileFor(newWorld), logger));
    }

    /**
     * Reconciles one chunk's scan result into the registry; saves when the
     * state changed. Main thread only.
     */
    public void applyChunkScan(
        final DimensionId dimension,
        final int chunkX,
        final int chunkZ,
        final List<PortalMarker> found
    ) {
        if (!registry.applyChunkScan(dimension, chunkX, chunkZ, found)) {
            return;
        }
        final WorldIdentity world = currentWorld;
        if (world != null) {
            // Snapshot on the main thread: the registry is main-thread-only and the
            // IO executor must never read it while another scan mutates it.
            final Map<DimensionId, List<PortalMarker>> snapshot = registry.snapshot();
            executors.io().execute(() -> PortalIo.save(fileFor(world), snapshot, logger));
        }
    }

    /** Immutable markers of one dimension, or an empty list between sessions. */
    public List<PortalMarker> list(final DimensionId dimension) {
        return registry.list(dimension);
    }

    /** True when a block update in this chunk should trigger a rescan. */
    public boolean hasMarkersInChunk(final DimensionId dimension, final int chunkX, final int chunkZ) {
        return registry.hasMarkersInChunk(dimension, chunkX, chunkZ);
    }

    private void saveNow(final WorldIdentity world) {
        final Path file = fileFor(world);
        final Map<DimensionId, List<PortalMarker>> snapshot = registry.snapshot();
        // Queue behind any pending scan save instead of writing from the main
        // thread: both would write the same .tmp file and race the atomic move,
        // which can leave a truncated file that the next load quarantines.
        final Future<?> save = executors.io().submit(() -> PortalIo.save(file, snapshot, logger));
        boolean interrupted = false;
        while (true) {
            try {
                save.get();
                break;
            } catch (final InterruptedException e) {
                interrupted = true;
            } catch (final ExecutionException e) {
                logger.error("Failed to save portal markers for {}", world, e.getCause());
                break;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private Path fileFor(final WorldIdentity world) {
        return WorldStorageMigration.file(baseDir, world, ".json", logger);
    }
}
