package cn.net.rms.confluxmap.core.net.image;

import java.util.Objects;
import java.util.UUID;

/**
 * Declares one image upload. {@code width}/{@code height} are the true encoded
 * dimensions (the server never decodes bytes, so the container header needs
 * them from here); {@code totalBytes} reserves the full payload size.
 */
public record ImageUploadBeginC2S(UUID imageId, int formatTag, int width, int height, int totalBytes)
    implements WaypointImageMessage.C2S {

    public ImageUploadBeginC2S {
        Objects.requireNonNull(imageId, "imageId");
        if (width < 1 || width > WaypointImageProto.MAX_DIMENSION
            || height < 1 || height > WaypointImageProto.MAX_DIMENSION) {
            throw new IllegalArgumentException(
                "image dimensions " + width + "x" + height + " outside 1.."
                    + WaypointImageProto.MAX_DIMENSION
            );
        }
        if (totalBytes < 1 || totalBytes > WaypointImageProto.MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException(
                "totalBytes " + totalBytes + " outside 1.." + WaypointImageProto.MAX_IMAGE_BYTES
            );
        }
        if (!WaypointImageCodec.isKnownFormat(formatTag)) {
            throw new IllegalArgumentException("unknown format tag " + formatTag);
        }
    }

    @Override
    public int typeId() {
        return WaypointImageProto.MSG_IMAGE_UPLOAD_BEGIN;
    }
}
