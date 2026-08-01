package cn.net.rms.confluxmap.core.net;

/**
 * {@code 0x06 S2C ERROR}: structured error from the server. The client surfaces a short toast
 * and/or logs the detail; it never tears down the channel on receipt (a malformed request gets
 * a code and life goes on - only channel-level violations close the connection).
 *
 * @param code   one of the {@code ERR_*} constants below (a single byte on the wire)
 * @param detail human-readable explanation, ≤ {@value Proto#MAX_UTF8_BYTES} UTF-8 bytes
 */
public record ErrorS2C(int code, String detail) implements Message {

    /** The MAP_VIEW_REQ arrived outside the server's rate/batch limits; the client should back off. */
    public static final int ERR_RATE_LIMITED = 1;
    /** The MAP_VIEW_REQ was structurally invalid (bad dim index, lod above budget, etc.). */
    public static final int ERR_MALFORMED_REQUEST = 2;
    /** Server hit an internal error (IO, summary-cache corruption, ...); the client should retry later. */
    public static final int ERR_INTERNAL = 3;
    /** The companion disabled itself at runtime (admin toggle); the client should stop syncing. */
    public static final int ERR_COMPANION_DISABLED = 4;

    @Override
    public int typeId() {
        return Proto.MSG_ERROR_S2C;
    }
}
