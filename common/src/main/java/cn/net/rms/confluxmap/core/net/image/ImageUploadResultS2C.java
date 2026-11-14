package cn.net.rms.confluxmap.core.net.image;

import java.util.Objects;
import java.util.UUID;

/** Outcome of one upload. Idempotent resends of BEGIN report OK for the same owner. */
public record ImageUploadResultS2C(UUID imageId, int status) implements WaypointImageMessage.S2C {

    public ImageUploadResultS2C {
        Objects.requireNonNull(imageId, "imageId");
        if (status < WaypointImageProto.STATUS_OK || status > WaypointImageProto.STATUS_STORAGE_ERROR) {
            throw new IllegalArgumentException("unknown upload status " + status);
        }
    }

    @Override
    public int typeId() {
        return WaypointImageProto.MSG_IMAGE_UPLOAD_RESULT;
    }
}
