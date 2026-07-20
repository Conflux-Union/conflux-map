package cn.net.rms.confluxmap.core.store;

import cn.net.rms.confluxmap.core.task.SessionGuard;

/**
 * Owns the current {@link MapWorld}. A transition seals the ending world before publishing the
 * replacement, giving persistence code a stable object that cannot receive late async writes.
 */
public final class MapWorldService {
    private volatile MapWorld current;

    /** Main thread only: switches sessions and returns the sealed ending world, if any. */
    public synchronized MapWorld switchSession(final SessionGuard.Session session) {
        final MapWorld ending = current;
        if (ending != null) {
            ending.deactivate();
        }
        current = session.active() ? new MapWorld(session) : null;
        return ending;
    }

    /** The active world, or null between sessions. */
    public MapWorld current() {
        return current;
    }

    /** The active world if it still matches {@code token}, else null. */
    public MapWorld ifCurrent(final long token) {
        final MapWorld world = current;
        return world != null && world.active() && world.session().token() == token ? world : null;
    }
}
