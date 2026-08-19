package cn.net.rms.confluxmap.core.cache;

import cn.net.rms.confluxmap.core.store.MapWorld;
import cn.net.rms.confluxmap.core.store.MapWorldService;
import cn.net.rms.confluxmap.core.store.WorldStorageMigration;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.tile.TileService;
import java.nio.file.Path;
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
    private final boolean readOnly;

    private volatile RegionDiskCache current;
    private SessionGuard.Session currentSession;

    public RegionCacheService(
        final Path root,
        final MapWorldService mapWorlds,
        final MapExecutors executors,
        final TileService tiles,
        final Logger logger
    ) {
        this(root, mapWorlds, executors, tiles, logger, false);
    }

    /**
     * @param readOnly true for the fullscreen archive browser, which may load but never flush cache data
     */
    public RegionCacheService(
        final Path root,
        final MapWorldService mapWorlds,
        final MapExecutors executors,
        final TileService tiles,
        final Logger logger,
        final boolean readOnly
    ) {
        this.root = root;
        this.mapWorlds = mapWorlds;
        this.executors = executors;
        this.tiles = tiles;
        this.logger = logger;
        this.readOnly = readOnly;
    }

    /** Main thread, from the session tracker. */
    public void onSessionChanged(final SessionGuard.Session session) {
        final SessionGuard.Session endingSession = currentSession;
        final MapWorld endingWorld = mapWorlds.switchSession(session);
        final RegionDiskCache endingCache = current;
        if (!readOnly && endingCache != null && endingWorld != null) {
            endingCache.flushAllOnSessionEnd(endingWorld);
        }
        final boolean waitForEndingFlush = !readOnly
            && migrationMustFollowEndingFlush(session, endingSession);
        final RegionDiskCache next = session.active()
            ? new RegionDiskCache(
                root, session, mapWorlds, executors.io(), tiles, logger,
                !readOnly && !waitForEndingFlush
            )
            : null;
        if (waitForEndingFlush) {
            // Both jobs use the single IO queue. The previous session's flush therefore writes
            // into its old namespace before this move, rather than recreating it afterwards.
            executors.io().execute(() -> WorldStorageMigration.directory(root, session.world(), logger));
        }
        current = next;
        currentSession = session;
    }

    private static boolean migrationMustFollowEndingFlush(
        final SessionGuard.Session session,
        final SessionGuard.Session endingSession
    ) {
        return session.active()
            && endingSession != null
            && endingSession.active()
            && "local".equals(session.world().serverId())
            && session.dimension().equals(endingSession.dimension())
            && session.world().serverId().equals(endingSession.world().serverId())
            && session.world().legacyStorageIds().contains(endingSession.world().worldId());
    }

    /** The disk cache for the active session, or null between sessions. */
    public RegionDiskCache current() {
        return current;
    }
}
