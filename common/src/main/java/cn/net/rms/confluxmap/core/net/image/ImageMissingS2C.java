package cn.net.rms.confluxmap.core.net.image;

import java.util.Objects;
import java.util.UUID;

/** The requested image is absent (never uploaded or garbage-collected). */
public record ImageMissingS2C(UUID imageId) implements WaypointImageMessage.S2C {

    public ImageMissingS2C {
        Objects.requireNonNull(imageId, "imageId");
    }

    @Override
    public int typeId() {
        return WaypointImageProto.MSG_IMAGE_MISSING;
    }
}
