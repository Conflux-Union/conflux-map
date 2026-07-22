package cn.net.rms.confluxmap.core.net.shared;

import java.util.Objects;
import java.util.UUID;

/** Correlates a server operation result with its client-generated operation id. */
public record ResultS2C(UUID operationId, int statusCode, int errorCode) implements SharedWaypointMessage {
    public ResultS2C {
        Objects.requireNonNull(operationId, "operationId");
    }

    @Override
    public int typeId() {
        return SharedWaypointProto.MSG_RESULT_S2C;
    }
}
