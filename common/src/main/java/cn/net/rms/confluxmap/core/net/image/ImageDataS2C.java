package cn.net.rms.confluxmap.core.net.image;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** The stored bytes of one image, served on demand. */
public record ImageDataS2C(UUID imageId, int formatTag, byte[] bytes)
    implements WaypointImageMessage.S2C {

    public ImageDataS2C {
        Objects.requireNonNull(imageId, "imageId");
        Objects.requireNonNull(bytes, "bytes");
        if (!WaypointImageCodec.isKnownFormat(formatTag)) {
            throw new IllegalArgumentException("unknown format tag " + formatTag);
        }
        if (bytes.length < 1 || bytes.length > WaypointImageProto.MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException(
                "image length " + bytes.length + " outside 1.." + WaypointImageProto.MAX_IMAGE_BYTES
            );
        }
        bytes = bytes.clone();
    }

    @Override
    public boolean equals(final Object other) {
        return other instanceof final ImageDataS2C that
            && imageId.equals(that.imageId)
            && formatTag == that.formatTag
            && Arrays.equals(bytes, that.bytes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(imageId, formatTag, Arrays.hashCode(bytes));
    }

    @Override
    public int typeId() {
        return WaypointImageProto.MSG_IMAGE_DATA;
    }
}
