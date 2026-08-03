package cn.net.rms.confluxmap.mc.world;

import java.util.Objects;
import java.util.Optional;

/** One-shot lifecycle for the client-issued Velocity /server identity query. */
final class VelocityServerIdentityQuery {
    private final int timeoutTicks;
    private State state = State.DISARMED;
    private boolean mayAdoptLegacyProfile;
    private boolean noticeConsumed;
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
        noticeConsumed = false;
        deadlineTick = 0L;
    }

    void disarm() {
        state = State.DISARMED;
        mayAdoptLegacyProfile = false;
        noticeConsumed = false;
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
        return observe(Optional.of(serverName), false).match();
    }

    Response observe(
        final Optional<String> serverName,
        final boolean currentServerNotice
    ) {
        Objects.requireNonNull(serverName, "serverName");
        if (state != State.PENDING) {
            return Response.ignored();
        }
        if (serverName.isPresent()) {
            state = State.COMPLETE;
            return Response.match(new Match(serverName.orElseThrow(), mayAdoptLegacyProfile));
        }
        if (currentServerNotice && !noticeConsumed) {
            noticeConsumed = true;
            return Response.notice();
        }
        return Response.ignored();
    }

    record Match(String serverName, boolean mayAdoptLegacyProfile) {
    }

    record Response(boolean consumed, Optional<Match> match) {
        private static Response ignored() {
            return new Response(false, Optional.empty());
        }

        private static Response notice() {
            return new Response(true, Optional.empty());
        }

        private static Response match(final Match match) {
            return new Response(true, Optional.of(match));
        }
    }

    private enum State { DISARMED, READY, PENDING, COMPLETE, UNAVAILABLE }
}
