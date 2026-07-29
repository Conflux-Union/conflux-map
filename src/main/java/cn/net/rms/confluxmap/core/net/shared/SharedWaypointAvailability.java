package cn.net.rms.confluxmap.core.net.shared;

import java.util.Objects;

/**
 * Stable public-feature availability derived from one client-state snapshot.
 * A companion that supports the protocol keeps the controls visible even when
 * the administrator disabled the feature. Public actions are only available
 * while the feature is enabled and its initial snapshot is synchronized.
 */
public record SharedWaypointAvailability(boolean visible, boolean enabled, boolean ready) {
    public static SharedWaypointAvailability from(
        final SharedWaypointClientState.State state,
        final boolean synchronizedSnapshot
    ) {
        Objects.requireNonNull(state, "state");
        final boolean visible = state == SharedWaypointClientState.State.SUPPORTED_DISABLED
            || state == SharedWaypointClientState.State.ENABLED;
        final boolean enabled = state == SharedWaypointClientState.State.ENABLED;
        return new SharedWaypointAvailability(visible, enabled, enabled && synchronizedSnapshot);
    }

    public boolean disabledByServer() {
        return visible && !enabled;
    }
}
