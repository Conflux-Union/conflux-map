package cn.net.rms.confluxmap.core.waypoint.image;

import cn.net.rms.confluxmap.core.net.image.WaypointImageCodec;
import cn.net.rms.confluxmap.core.net.image.WaypointImageProto;
import java.util.Objects;
import java.util.UUID;

/** One decoded image container: the opaque payload plus the metadata servers need. */
public record StoredImage(UUID owner, int formatTag, int width, int height, byte[] png) {

    public StoredImage {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(png, "png");
        if (!WaypointImageCodec.isKnownFormat(formatTag)) {
            throw new IllegalArgumentException("unknown format tag " + formatTag);
        }
        if (width < 1 || width > WaypointImageProto.MAX_DIMENSION
            || height < 1 || height > WaypointImageProto.MAX_DIMENSION) {
            throw new IllegalArgumentException(
                "image dimensions " + width + "x" + height + " outside 1.."
                    + WaypointImageProto.MAX_DIMENSION
            );
        }
        if (png.length < 1 || png.length > WaypointImageProto.MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException(
                "image length " + png.length + " outside 1.." + WaypointImageProto.MAX_IMAGE_BYTES
            );
        }
        png = png.clone();
    }
}
