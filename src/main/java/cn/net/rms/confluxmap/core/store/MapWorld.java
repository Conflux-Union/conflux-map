package cn.net.rms.confluxmap.core.store;

import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import java.util.concurrent.ConcurrentHashMap;

/**
 * All in-memory map data for one world session. Discarded wholesale when the
 * session ends; the session token ties every queued job to this instance.
 */
public final class MapWorld {
    private final SessionGuard.Session session;
    private final ConcurrentHashMap<String, ColumnStore> stores = new ConcurrentHashMap<>();

    public MapWorld(final SessionGuard.Session session) {
        this.session = session;
    }

    public SessionGuard.Session session() {
        return session;
    }

    public ColumnStore store(final MapLayer layer) {
        return stores.computeIfAbsent(layer.cacheId(), id -> new ColumnStore());
    }

    public int totalRegions() {
        int n = 0;
        for (final ColumnStore store : stores.values()) {
            n += store.regionCount();
        }
        return n;
    }
}
