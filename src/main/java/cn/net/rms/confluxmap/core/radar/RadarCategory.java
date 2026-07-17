package cn.net.rms.confluxmap.core.radar;

/**
 * Coarse bucket a tracked entity is sorted into for the radar overlay.
 * See {@code docs/reference-specs/radar-icons.md} section 1 for the behavior
 * this is derived from; M1 collapses the spec's richer live classifier
 * (Monster marker / killer-rabbit-variant / anger-at-player checks) down to
 * the vanilla spawn-group taxonomy for simplicity (see
 * {@code mc.radar.EntityRadarScanner}).
 */
public enum RadarCategory {
    PLAYER,
    HOSTILE,
    PASSIVE,
    /** Anything living that doesn't fall into a known spawn group; effectively unreachable for vanilla 1.17.1 entities, kept as a defensive default. */
    OTHER
}
