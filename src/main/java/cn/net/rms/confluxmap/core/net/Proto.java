package cn.net.rms.confluxmap.core.net;

/**
 * Protocol-level constants for the {@code confluxmap:map_sync} companion channel.
 *
 * <p>All multi-byte wire values are big-endian, written/read via {@link
 * java.io.DataOutput}/{@link java.io.DataInput}. Strings are length-prefixed
 * UTF-8 with a hard cap (see {@link #MAX_UTF8_BYTES}); arrays have a {@code u8} or {@code u16}
 * count with a per-message hard cap (see {@link #MAX_DIM_ENTRIES}, {@link #MAX_TILES_PER_REQ},
 * and {@link #MAX_LOAD_STATE_ENTRIES});
 * byte blobs are a {@code u32} length with the relevant per-message cap.
 *
 * <p>Caps are enforced in {@link MsgCodec} on both encode and decode. Anything
 * coming off the network is treated as hostile: a violation throws {@link
 * ProtoException}, never a negative-array-size or {@code OOM}.
 */
public final class Proto {
    private Proto() {
    }

    /** Channel identifier registered with Fabric's networking API on both sides. */
    public static final String CHANNEL_ID = "confluxmap:map_sync";

    /**
     * Protocol version this build speaks. Mismatched minors are tolerated; majors are not.
     * Major 2 switched the MAP_PATCH body to field-plane layout with delta-coded heights.
     * Minor 1 added the per-dim generator preset in spare bits of HELLO_POLICY's dim flag byte.
     * Minor 2 added FLAT_BASELINE (0x07); pre-minor-2 clients log and ignore it.
     * Minor 3 added server-authoritative chunk load-state subscriptions (0x08/0x09).
     */
    public static final int PROTO_MAJOR = 2;
    public static final int PROTO_MINOR = 3;

    // ---- Message type ids (first byte of every framed payload) ----

    /** C2S: client announces its versions; server replies with {@link #MSG_HELLO_POLICY_S2C}. */
    public static final int MSG_HELLO_C2S = 0x01;
    /** S2C: server's policy + per-dim metadata (possibly including seed). */
    public static final int MSG_HELLO_POLICY_S2C = 0x02;
    /** C2S: viewport-driven request for one tile set; body is parsed in S5. */
    public static final int MSG_MAP_VIEW_REQ_C2S = 0x03;
    /** S2C: one tile's worth of corrections; body is parsed by PatchCodec in S4. */
    public static final int MSG_MAP_PATCH_S2C = 0x04;
    /** S2C: mid-session policy update; shape mirrors the mutable subset of HELLO_POLICY. */
    public static final int MSG_POLICY_UPDATE_S2C = 0x05;
    /** S2C: structured error from the server (rate-limit, malformed request, etc.). */
    public static final int MSG_ERROR_S2C = 0x06;
    /** S2C: uniform surface per superflat dimension; sent just before {@link #MSG_HELLO_POLICY_S2C}. */
    public static final int MSG_FLAT_BASELINE_S2C = 0x07;
    /** C2S: subscribe to or cancel the current fullscreen load-state viewport. */
    public static final int MSG_LOAD_STATE_SUBSCRIBE_C2S = 0x08;
    /** S2C: one bounded initial-snapshot or incremental load-state batch. */
    public static final int MSG_LOAD_STATE_DELTA_S2C = 0x09;

    /** First valid message id; used to range-check the type byte. */
    public static final int MSG_MIN = MSG_HELLO_C2S;
    /** Last valid message id for this proto major version. */
    public static final int MSG_MAX = MSG_LOAD_STATE_DELTA_S2C;

    // ---- Hard caps (enforced everywhere untrusted bytes cross a boundary) ----

    /**
     * Fabric 1.17.1 S2C custom payload limit ({@code CustomPayloadS2CPacket.MAX_PAYLOAD_SIZE}
     * verified in the research report). Messages above this cannot reach the client intact, so
     * the codec rejects them before a half-sent packet can be observed.
     */
    public static final int MAX_S2C_PAYLOAD = 1 << 20; // 1 MiB

    /**
     * Practical C2S cap. Fabric's hard limit is 32767 bytes for the whole custom-payload
     * packet; 8 KiB is well below that and large enough for any HELLO / MAP_VIEW_REQ the
     * client legitimately sends (tiles-per-req is itself capped at {@value #MAX_TILES_PER_REQ}).
     */
    public static final int MAX_C2S_PAYLOAD = 8 * 1024;

    /** Hard cap on any UTF-8 string field (modVersion, predictorVersion, worldId, error detail). */
    public static final int MAX_UTF8_BYTES = 256;

    /** Maximum number of per-dimension entries the server will advertise in one HELLO_POLICY. */
    public static final int MAX_DIM_ENTRIES = 8;

    /** Maximum number of tiles one MAP_VIEW_REQ may carry; same cap used by the server's budget. */
    public static final int MAX_TILES_PER_REQ = 8;

    /** Maximum entries in one load-state payload; each entry is ten bytes on the wire. */
    public static final int MAX_LOAD_STATE_ENTRIES = 512;
    /** Maximum requested viewport width or height, in chunks. */
    public static final int MAX_LOAD_STATE_SPAN = 4_096;
    /** Wire sentinel for a chunk that left the server's loaded set. */
    public static final int LOAD_STATE_UNLOADED_LEVEL = 0xFF;

    /** Sentinel written/read as the {@code mapColorId} when no vanilla map color applies. */
    public static final int MAP_COLOR_NONE = 0xFF;

    /** Length of the {@code presence} bitmap carried in every MAP_PATCH (one bit per 16x16 output-pixel cell). */
    public static final int PATCH_PRESENCE_BYTES = 32;

    /**
     * {@code mode} field in MAP_PATCH: no column data follows, so the receiver keeps the samples
     * it already has and takes only the presence bitmap. Sent both when the server's baseline
     * matches the client's prediction exactly, and for tiles coarser than the server's correction
     * ceiling, where presence is the only thing cheap enough to compute.
     */
    public static final int PATCH_MODE_UNCHANGED = 0;
    /** {@code mode} field in MAP_PATCH: server sends differing pixels and removals (residual coding). */
    public static final int PATCH_MODE_RESIDUAL = 1;
    /** {@code mode} field in MAP_PATCH: server sends every pixel (baseline mismatch or cold cache). */
    public static final int PATCH_MODE_ABSOLUTE = 2;
    /** {@code mode} field in MAP_PATCH: server has no data for this tile (prediction only). */
    public static final int PATCH_MODE_UNAVAILABLE = 3;

    /** Budget defaults advertised in HELLO_POLICY when the server config is at its defaults. */
    public static final int DEFAULT_MAX_BYTES_PER_SEC = 65_536;
    /** Budget defaults advertised in HELLO_POLICY when the server config is at its defaults. */
    public static final int DEFAULT_MAX_TILES_PER_REQ = 8;
    /** Budget defaults advertised in HELLO_POLICY when the server config is at its defaults. */
    public static final int DEFAULT_MIN_REQ_INTERVAL_MS = 300;
    /** Budget defaults advertised in HELLO_POLICY when the server config is at its defaults. */
    public static final int DEFAULT_MAX_PATCH_LOD = 2;
}
