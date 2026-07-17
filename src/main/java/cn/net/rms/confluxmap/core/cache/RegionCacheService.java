package cn.net.rms.confluxmap.core.cache;

import cn.net.rms.confluxmap.core.store.MapWorld;
import cn.net.rms.confluxmap.core.store.MapWorldService;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.tile.TileService;
import java.nio.file.Path;
import org.apache.logging.log4j.Logger;

/**
 * Owns the current {@link RegionDiskCache}, one per world session - mirrors
 * {@link MapWorldService}'s own current-instance-per-session shape.
 *
 * <p>Must be registered in the session listener chain <b>before</b> {@link MapWorldService},
 * not after: on session end this reads {@link MapWorldService#current()} for the world that's
 * about to be replaced, so the final flush can capture its column store data. If this ran
 * after {@code MapWorldService}'s own listener, {@code current()} would already be the new
 * (or null) world and there would be nothing left to flush.
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

    /** Main thread, from the session tracker. See the class javadoc for the required registration order. */
    public void onSessionChanged(final SessionGuard.Session session) {
        final MapWorld endingWorld = mapWorlds.current();
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
