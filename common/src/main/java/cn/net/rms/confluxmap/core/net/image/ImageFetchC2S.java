package cn.net.rms.confluxmap.core.net.image;

import java.util.Objects;
import java.util.UUID;

/** Requests the bytes of one image; the server answers DATA or MISSING. */
public record ImageFetchC2S(UUID imageId) implements WaypointImageMessage.C2S {

    public ImageFetchC2S {
        Objects.requireNonNull(imageId, "imageId");
    }

    @Override
    public int typeId() {
        return WaypointImageProto.MSG_IMAGE_FETCH;
    }
}
