package cn.net.rms.confluxmap.core.net.shared;

import java.util.Objects;

/**
 * Stable public-feature availability derived from one client-state snapshot.
 * Enabled controls may be shown before the initial snapshot arrives, but public
 * actions remain unavailable until synchronization completes.
 */
public record SharedWaypointAvailability(boolean enabled, boolean ready) {
    public static SharedWaypointAvailability from(
        final SharedWaypointClientState.State state,
        final boolean synchronizedSnapshot
    ) {
        Objects.requireNonNull(state, "state");
        final boolean enabled = state == SharedWaypointClientState.State.ENABLED;
        return new SharedWaypointAvailability(enabled, enabled && synchronizedSnapshot);
    }
}
