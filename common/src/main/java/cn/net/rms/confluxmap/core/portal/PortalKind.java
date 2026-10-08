package cn.net.rms.confluxmap.core.portal;

/**
 * The three portal blocks whose mere presence means "activated": a lit nether
 * portal, an eye-filled end portal, and an end gateway only exists once the
 * dragon fight has produced it. Unactivated frames (obsidian frames without
 * fire, {@code end_portal_frame} without eyes) are deliberately not tracked.
 */
public enum PortalKind {
    NETHER_PORTAL("nether_portal"),
    END_PORTAL("end_portal"),
    END_GATEWAY("end_gateway");

    private final String blockId;

    PortalKind(final String blockId) {
        this.blockId = blockId;
    }

    /** The vanilla block registry id, e.g. {@code minecraft:nether_portal}. */
    public String blockId() {
        return blockId;
    }

    /** Parses an IO/registry id; returns null for anything this mod does not track. */
    public static PortalKind parse(final String id) {
        for (final PortalKind kind : values()) {
            if (kind.blockId.equals(id)) {
                return kind;
            }
        }
        return null;
    }
}
