package cn.net.rms.confluxmap.core.waypoint;

import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
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
public final class WaypointService {
    private final Path baseDir;
    private final MapExecutors executors;
    private final Logger logger;

    private volatile WaypointStore current;

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
            saveSnapshot(current.world(), current.list());
        }
        if (newWorld == null) {
            current = null;
            return;
        }
        final List<Waypoint> loaded = WaypointIo.load(fileFor(newWorld), logger);
        final WaypointStore store = new WaypointStore(newWorld, loaded);
        store.addListener(waypoints -> saveSnapshot(newWorld, waypoints));
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

    private void saveSnapshot(final WorldIdentity world, final List<Waypoint> snapshot) {
        final Path file = fileFor(world);
        executors.io().execute(() -> WaypointIo.save(file, snapshot, logger));
    }

    private Path fileFor(final WorldIdentity world) {
        return baseDir.resolve(world.serverId()).resolve(world.worldId() + ".json");
    }
}
