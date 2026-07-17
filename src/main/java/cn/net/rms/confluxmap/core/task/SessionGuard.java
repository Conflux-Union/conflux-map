package cn.net.rms.confluxmap.core.task;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks the current world session. Every asynchronous job captures the token
 * that was current when it was queued; results whose token no longer matches
 * are dropped, so work from a previous world/dimension can never write into
 * the current one.
 */
public final class SessionGuard {
    /** Immutable snapshot of "where the player is" for one session generation. */
    public record Session(long token, WorldIdentity world, DimensionId dimension) {
        public static final Session NONE = new Session(0L, WorldIdentity.NONE, DimensionId.OVERWORLD);

        public boolean active() {
            return world.isPresent();
        }
    }

    private final AtomicReference<Session> current = new AtomicReference<>(Session.NONE);
    private long counter;

    /** Main thread only. */
    public synchronized Session begin(final WorldIdentity world, final DimensionId dimension) {
        final Session session = new Session(++counter, world, dimension);
        current.set(session);
        return session;
    }

    /** Main thread only. */
    public synchronized void end() {
        ++counter;
        current.set(Session.NONE);
    }

    public Session current() {
        return current.get();
    }

    public boolean isCurrent(final long token) {
        return current.get().token() == token;
    }
}
