package cn.net.rms.confluxmap.core.net.shared;

/** Wire constants for the independent shared-waypoint channel. */
public final class SharedWaypointProto {
    private SharedWaypointProto() {
    }

    public static final String CHANNEL_ID = "confluxmap:waypoints_v1";
    public static final int PROTO_MAJOR = 1;
    public static final int PROTO_MINOR = 7;
    /** First wire minor carrying client-originated group mutations. */
    public static final int PROTO_MINOR_GROUP_MUTATIONS = 5;
    /** First wire minor carrying atomic batch group moves and create/update group assignment. */
    public static final int PROTO_MINOR_BATCH_MOVE = 6;
    /** First wire minor carrying waypoint descriptions, preview-image references, and S2C fragment envelopes. */
    public static final int PROTO_MINOR_WAYPOINT_DESCRIPTION = 7;

    public static final int MSG_HELLO_C2S = 0x01;
    public static final int MSG_STATUS_S2C = 0x02;
    public static final int MSG_SUBSCRIBE_C2S = 0x03;
    public static final int MSG_CREATE_C2S = 0x04;
    public static final int MSG_DELETE_C2S = 0x05;
    public static final int MSG_LOCK_C2S = 0x06;
    public static final int MSG_SNAPSHOT_S2C = 0x07;
    public static final int MSG_UPSERT_S2C = 0x08;
    public static final int MSG_REMOVE_S2C = 0x09;
    public static final int MSG_RESULT_S2C = 0x0A;
    public static final int MSG_UPDATE_C2S = 0x0B;
    public static final int MSG_GROUP_UPSERT_S2C = 0x0C;
    public static final int MSG_GROUP_REMOVE_S2C = 0x0D;
    public static final int MSG_GROUP_CREATE_C2S = 0x0E;
    public static final int MSG_GROUP_RENAME_C2S = 0x0F;
    public static final int MSG_GROUP_DELETE_C2S = 0x10;
    public static final int MSG_SET_GROUP_C2S = 0x11;
    public static final int MSG_MOVE_BATCH_C2S = 0x12;

    public static final int MAX_C2S_PAYLOAD = 8 * 1024;
    public static final int MAX_S2C_PAYLOAD = 1 << 20;
    public static final int MAX_UTF8_BYTES = 256;
    /** Description cap: a maxed CREATE frame stays well inside the C2S cap. */
    public static final int MAX_DESCRIPTION_UTF8_BYTES = 2048;
    public static final int MAX_SNAPSHOT_WAYPOINTS = 512;
    public static final int MAX_SNAPSHOT_GROUPS = 64;
    /** Batch entries: 2 uuids + revision + flag fits 128 entries inside the C2S cap. */
    public static final int MAX_BATCH_MOVE_ENTRIES = 128;

    public static final int RESULT_STATUS_APPLIED = 0;
    public static final int RESULT_STATUS_REJECTED = 1;

    public static final int RESULT_ERROR_NONE = 0;
    public static final int RESULT_ERROR_INVALID_REQUEST = 1;
    public static final int RESULT_ERROR_REVISION_CONFLICT = 2;
    public static final int RESULT_ERROR_NOT_FOUND = 3;
    public static final int RESULT_ERROR_FORBIDDEN = 4;
    public static final int RESULT_ERROR_WORLD_QUOTA_EXCEEDED = 5;
    public static final int RESULT_ERROR_PLAYER_QUOTA_EXCEEDED = 6;
    public static final int RESULT_ERROR_RATE_LIMITED = 7;
    public static final int RESULT_ERROR_PERSISTENCE_FAILED = 8;
    public static final int RESULT_ERROR_ID_GENERATION_FAILED = 9;
    public static final int RESULT_ERROR_DISABLED = 10;
    public static final int RESULT_ERROR_DUPLICATE_LOCATION = 11;
}
