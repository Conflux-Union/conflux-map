package cn.net.rms.confluxmap.core.export;

import java.nio.file.Path;

/** Thread-safe immutable progress snapshot for the export UI. */
public record MapExportStatus(
    State state,
    long completed,
    long total,
    Path output,
    String error
) {
    public enum State {
        IDLE,
        RASTERIZING,
        ENCODING,
        COMPLETED,
        CANCELLED,
        FAILED
    }

    public static MapExportStatus idle() {
        return new MapExportStatus(State.IDLE, 0L, 0L, null, null);
    }

    public boolean active() {
        return state == State.RASTERIZING || state == State.ENCODING;
    }

    public boolean terminal() {
        return state == State.COMPLETED || state == State.CANCELLED || state == State.FAILED;
    }
}
