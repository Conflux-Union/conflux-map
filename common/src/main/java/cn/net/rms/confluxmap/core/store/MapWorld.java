package cn.net.rms.confluxmap.core.store;

import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.SampleSource;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * All in-memory map data for one world session. Discarded wholesale when the
 * session ends; the session token ties every queued job to this instance.
 */
public final class MapWorld {
    private final SessionGuard.Session session;
    private final ConcurrentHashMap<String, ColumnStore> stores = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();
    private volatile boolean active = true;

    public MapWorld(final SessionGuard.Session session) {
        this.session = session;
    }

    public SessionGuard.Session session() {
        return session;
    }

    public ColumnStore store(final MapLayer layer) {
        return stores.computeIfAbsent(layer.cacheId(), id -> new ColumnStore());
    }

    /**
     * Writes through the session boundary. Once the world is sealed, no queued task can mutate it;
     * the session-end disk flush therefore observes every accepted write and no later ones.
     */
    public boolean put(final MapLayer layer, final ChunkSnapshot snapshot, final SampleSource source) {
        lifecycle.readLock().lock();
        try {
            if (!active || snapshot.sessionToken != session.token()) {
                return false;
            }
            return store(layer).put(snapshot, source);
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    void deactivate() {
        lifecycle.writeLock().lock();
        try {
            active = false;
        } finally {
            lifecycle.writeLock().unlock();
        }
    }

    boolean active() {
        return active;
    }

    public int totalRegions() {
        int n = 0;
        for (final ColumnStore store : stores.values()) {
            n += store.regionCount();
        }
        return n;
    }
}
