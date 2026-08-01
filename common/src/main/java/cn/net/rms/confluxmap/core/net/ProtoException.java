package cn.net.rms.confluxmap.core.net;

/**
 * Raised by {@link MsgCodec} whenever a wire payload violates a length cap, carries an
 * unknown message-type byte, or is truncated mid-record. Callers on the network boundary
 * must catch this, drop the offending payload, and carry on - never let it propagate into
 * the session as a generic {@link RuntimeException}.
 */
public final class ProtoException extends Exception {
    public ProtoException(final String message) {
        super(message);
    }

    public ProtoException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
