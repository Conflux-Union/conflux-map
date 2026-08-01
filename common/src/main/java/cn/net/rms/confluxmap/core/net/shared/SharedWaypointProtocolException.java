package cn.net.rms.confluxmap.core.net.shared;

/** Raised when a shared-waypoint payload violates the wire contract. */
public final class SharedWaypointProtocolException extends Exception {
    public SharedWaypointProtocolException(final String message) {
        super(message);
    }

    public SharedWaypointProtocolException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
