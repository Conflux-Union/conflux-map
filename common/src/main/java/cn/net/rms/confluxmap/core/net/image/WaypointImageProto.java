package cn.net.rms.confluxmap.core.net.image;

/** Wire constants for the independent waypoint-image channel. */
public final class WaypointImageProto {
    private WaypointImageProto() {
    }

    public static final String CHANNEL_ID = "confluxmap:waypoint_images_v1";
    public static final int PROTO_MAJOR = 1;
    public static final int PROTO_MINOR = 1;

    public static final int MSG_IMAGE_UPLOAD_BEGIN = 0x01;
    public static final int MSG_IMAGE_UPLOAD_CHUNK = 0x02;
    public static final int MSG_IMAGE_FETCH = 0x03;
    public static final int MSG_IMAGE_UPLOAD_RESULT = 0x04;
    public static final int MSG_IMAGE_DATA = 0x05;
    public static final int MSG_IMAGE_MISSING = 0x06;

    /** Vanilla C2S custom payload cap; a full chunk frame fits with room to spare. */
    public static final int MAX_C2S_PAYLOAD = 32 * 1024;
    public static final int MAX_S2C_PAYLOAD = 1 << 20;
    public static final int MAX_IMAGE_BYTES = 64 * 1024;
    public static final int MAX_CHUNK_BYTES = 16 * 1024;
    public static final int MAX_DIMENSION = 4096;

    public static final int FORMAT_PNG = 1;

    public static final int STATUS_OK = 0;
    public static final int STATUS_TOO_LARGE = 1;
    public static final int STATUS_RATE_LIMITED = 2;
    public static final int STATUS_QUOTA_EXCEEDED = 3;
    public static final int STATUS_DUPLICATE_OWNER_CONFLICT = 4;
    public static final int STATUS_INVALID_FORMAT = 5;
    public static final int STATUS_STORAGE_ERROR = 6;
}
