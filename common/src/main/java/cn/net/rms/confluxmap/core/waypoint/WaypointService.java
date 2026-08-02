package cn.net.rms.confluxmap.core.waypoint;

import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.store.WorldStorageMigration;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.Logger;

/**
 * Owns the current world's {@link WaypointStore}, bound to
 * {@link cn.net.rms.confluxmap.mc.world.WorldSessionTracker} sessions: loads
 * (blocking, main thread - mirrors {@link cn.net.rms.confluxmap.core.config.ConfigIo}'s
 * own load-at-a-natural-pause-point pattern; these files are small) whenever
 * a genuinely *new* world/server identity appears, saves the outgoing
 * world's data before switching away, and saves again on every store
 * mutation and on session end. All saves go through {@link MapExecutors#io()},
 * atomically (see {@link WaypointIo}).
 *
 * <p>A dimension change within the same world/server does not reload or
 * save anything - waypoints for every dimension of one world/server live in
 * a single file (see {@code waypoint-ux.md} S2), so only a genuine world
 * identity change matters here. This mirrors {@link cn.net.rms.confluxmap.core.store.MapWorldService}'s
 * session-swap shape.
 */
public final class WaypointService implements WaypointDataView {
    private final Path baseDir;
    private final MapExecutors executors;
    private final Logger logger;

    private volatile WaypointStore current;
    /** Keeps a just-written outgoing state available while its IO task is still queued. */
    private final Map<WorldIdentity, WaypointStore.State> pendingOutgoing = new ConcurrentHashMap<>();

    public WaypointService(final Path baseDir, final MapExecutors executors, final Logger logger) {
        this.baseDir = baseDir;
        this.executors = executors;
        this.logger = logger;
    }

    /** Main thread only: {@link cn.net.rms.confluxmap.mc.world.WorldSessionTracker} listener. */
    public void onSessionChanged(final SessionGuard.Session session) {
        final WorldIdentity newWorld = session.active() ? session.world() : null;
        final WorldIdentity oldWorld = current == null ? null : current.world();
        if (Objects.equals(newWorld, oldWorld)) {
            return;
        }
        if (current != null) {
            final WorldIdentity outgoingWorld = current.world();
            final WaypointStore.State outgoingState = current.state();
            pendingOutgoing.put(outgoingWorld, outgoingState);
            saveOutgoingSnapshot(outgoingWorld, outgoingState);
        }
        if (newWorld == null) {
            current = null;
            return;
        }
        final Path file = WorldStorageMigration.file(baseDir, newWorld, ".json", logger);
        final WaypointStore.State pendingState = pendingOutgoing.remove(newWorld);
        final WaypointStore.State loaded = pendingState != null
            ? pendingState
            : WaypointIo.loadState(file, logger);
        final WaypointStore store = new WaypointStore(newWorld, loaded);
        store.addListener(waypoints -> saveSnapshot(newWorld, store.state()));
        current = store;
    }

    /** The active store, or {@code null} between sessions. */
    public WaypointStore current() {
        return current;
    }

    /** Convenience: current waypoints, or an empty list between sessions. */
    public List<Waypoint> list() {
        final WaypointStore store = current;
        return store == null ? Collections.emptyList() : store.list();
    }

    @Override
    public WaypointDataView.Snapshot snapshot() {
        final WaypointStore store = current;
        return store == null ? WaypointDataView.Snapshot.empty() : store.dataSnapshot();
    }

    private void saveSnapshot(final WorldIdentity world, final WaypointStore.State snapshot) {
        final Path file = fileFor(world);
        executors.io().execute(() -> WaypointIo.save(file, snapshot, logger));
    }

    private void saveOutgoingSnapshot(final WorldIdentity world, final WaypointStore.State snapshot) {
        final Path file = fileFor(world);
        try {
            executors.io().execute(() -> {
                try {
                    WaypointIo.save(file, snapshot, logger);
                } catch (final RuntimeException error) {
                    logger.error("Failed to save outgoing waypoints for {}", world, error);
                } finally {
                    pendingOutgoing.remove(world, snapshot);
                }
            });
        } catch (final RuntimeException error) {
            pendingOutgoing.remove(world, snapshot);
            logger.error("Failed to schedule outgoing waypoint save for {}", world, error);
        }
    }

    int pendingOutgoingCount() {
        return pendingOutgoing.size();
    }

    private Path fileFor(final WorldIdentity world) {
        return baseDir.resolve(world.serverId()).resolve(world.worldId() + ".json");
    }
}
