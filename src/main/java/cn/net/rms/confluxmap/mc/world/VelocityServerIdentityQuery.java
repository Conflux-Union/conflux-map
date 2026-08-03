package cn.net.rms.confluxmap.mc.world;

import java.util.Optional;

/** One-shot lifecycle for the client-issued Velocity /server identity query. */
final class VelocityServerIdentityQuery {
    private final int timeoutTicks;
    private State state = State.DISARMED;
    private boolean mayAdoptLegacyProfile;
    private long deadlineTick;

    VelocityServerIdentityQuery(final int timeoutTicks) {
        if (timeoutTicks < 1) {
            throw new IllegalArgumentException("timeoutTicks must be positive");
        }
        this.timeoutTicks = timeoutTicks;
    }

    void arm(final boolean mayAdoptLegacyProfile) {
        state = State.READY;
        this.mayAdoptLegacyProfile = mayAdoptLegacyProfile;
        deadlineTick = 0L;
    }

    void disarm() {
        state = State.DISARMED;
        mayAdoptLegacyProfile = false;
        deadlineTick = 0L;
    }

    boolean ready() {
        return state == State.READY;
    }

    boolean mayAdoptLegacyProfile() {
        return mayAdoptLegacyProfile;
    }

    boolean blocksFallback() {
        return state == State.READY || state == State.PENDING;
    }

    boolean shouldAwait(final long currentTick, final boolean supported, final Runnable sendQuery) {
        if (state == State.READY) {
            if (!supported) {
                state = State.UNAVAILABLE;
                return false;
            }
            sendQuery.run();
            state = State.PENDING;
            deadlineTick = currentTick + timeoutTicks;
            return true;
        }
        if (state != State.PENDING) {
            return false;
        }
        if (currentTick >= deadlineTick) {
            state = State.UNAVAILABLE;
            return false;
        }
        return true;
    }

    Optional<Match> accept(final String serverName) {
        if (state != State.PENDING) {
            return Optional.empty();
        }
        state = State.COMPLETE;
        return Optional.of(new Match(serverName, mayAdoptLegacyProfile));
    }

    record Match(String serverName, boolean mayAdoptLegacyProfile) {
    }

    private enum State { DISARMED, READY, PENDING, COMPLETE, UNAVAILABLE }
}
