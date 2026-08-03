package cn.net.rms.confluxmap.core.cache;

import cn.net.rms.confluxmap.core.store.MapWorld;
import cn.net.rms.confluxmap.core.store.MapWorldService;
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

    private volatile RegionDiskCache current;

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
            endingCache.flushAllOnSessionEnd(endingWorld);
        }
        current = session.active() ? new RegionDiskCache(root, session, mapWorlds, executors, tiles, logger) : null;
    }

    /** The disk cache for the active session, or null between sessions. */
    public RegionDiskCache current() {
        return current;
    }
}
