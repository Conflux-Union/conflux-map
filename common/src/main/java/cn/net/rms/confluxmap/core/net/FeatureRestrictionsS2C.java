package cn.net.rms.confluxmap.core.net;

/**
 * {@code 0x19 S2C FEATURE_RESTRICTIONS}: client features the server forbids on this connection.
 * Covers the surfaces {@link HelloPolicyS2C.Flags} cannot express because its flag byte is full:
 * the HUD minimap, the fullscreen world map (including everything reached through it - export,
 * annotations, map markers), local waypoints (list, editor, in-world rendering), and the
 * prediction underlay.
 *
 * <p>Sent just before HELLO_POLICY and only when at least one feature is forbidden, so default
 * configurations produce no frame. Like the negative HELLO_POLICY flags, absence of the frame -
 * an older server, a disabled companion, or a server that forbids nothing - means every feature
 * stays allowed. This is honest-client enforcement: a modified client can ignore it, and that is
 * accepted (see {@code entityRadarForbidden} for the precedent).
 */
public record FeatureRestrictionsS2C(
    boolean minimapForbidden,
    boolean worldMapForbidden,
    boolean waypointsForbidden,
    boolean predictionForbidden
) implements Message {
    /** The no-restriction projection; also what peers infer from a missing frame. */
    public static FeatureRestrictionsS2C none() {
        return new FeatureRestrictionsS2C(false, false, false, false);
    }

    /** True when no feature is forbidden, i.e. servers need not send the frame. */
    public boolean isEmpty() {
        return !minimapForbidden && !worldMapForbidden && !waypointsForbidden && !predictionForbidden;
    }

    @Override
    public int typeId() {
        return Proto.MSG_FEATURE_RESTRICTIONS_S2C;
    }
}
