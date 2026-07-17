package cn.net.rms.confluxmap.core.store;

import cn.net.rms.confluxmap.core.task.SessionGuard;

/**
 * Owns the current {@link MapWorld}. Rotated by the session tracker on the
 * main thread; async consumers must check the session token before writing.
 */
public final class MapWorldService {
    private volatile MapWorld current;

    /** Main thread only: called when a session starts or ends. */
    public void onSessionChanged(final SessionGuard.Session session) {
        current = session.active() ? new MapWorld(session) : null;
    }

    /** The active world, or null between sessions. */
    public MapWorld current() {
        return current;
    }

    /** The active world if it still matches {@code token}, else null. */
    public MapWorld ifCurrent(final long token) {
        final MapWorld world = current;
        return world != null && world.session().token() == token ? world : null;
    }
}
