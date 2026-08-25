package cn.net.rms.confluxmap.mc.ui.world;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.waypoint.WaypointRenderEntry;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Client-only selection shared by the fullscreen map and the in-world waypoint HUD. */
public final class WaypointHighlightState {
    /** Stable ID used only for a highlighted map location that is not a saved waypoint. */
    public static final UUID SELECTED_LOCATION_ID = new UUID(
        0x434f4e464c555858L, 0x53454c4543544544L
    );
    public static final String SELECTED_LOCATION_TRANSLATION_KEY =
        "confluxmap.map.location_menu.selected_location";
    public static final double DEFAULT_LOCATION_Y = 64.0;

    public record Target(
        UUID waypointId,
        DimensionId dimension,
        double x,
        double y,
        double z,
        boolean yKnown
    ) {
        public Target {
            Objects.requireNonNull(dimension, "dimension");
        }

        public static Target waypoint(final WaypointRenderEntry waypoint, final DimensionId displayedDimension) {
            return new Target(waypoint.id(), displayedDimension, waypoint.x(), waypoint.y(), waypoint.z(), true);
        }

        public static Target location(
            final DimensionId dimension, final double x, final double y, final double z, final boolean yKnown
        ) {
            return new Target(null, dimension, x, y, z, yKnown);
        }
    }

    private Target target;
    private long currentSessionToken = Long.MIN_VALUE;

    public void select(final Target target) {
        this.target = Objects.requireNonNull(target, "target");
    }

    public void selectWaypoint(final WaypointRenderEntry waypoint, final DimensionId displayedDimension) {
        select(Target.waypoint(waypoint, displayedDimension));
    }

    public void clear() {
        target = null;
    }

    public Optional<Target> target() {
        return Optional.ofNullable(target);
    }

    public boolean active() {
        return target != null;
    }

    public boolean activeIn(final DimensionId dimension) {
        return target != null && target.dimension().equals(dimension);
    }

    public boolean matches(final WaypointRenderEntry waypoint, final DimensionId displayedDimension) {
        return target != null && target.waypointId() != null
            && target.waypointId().equals(waypoint.id())
            && target.dimension().equals(displayedDimension);
    }

    /** Matches either a saved waypoint by ID or the synthetic entry for a highlighted location. */
    public boolean matchesEntry(
        final WaypointRenderEntry waypoint,
        final DimensionId displayedDimension
    ) {
        if (target == null || !target.dimension().equals(displayedDimension)) {
            return false;
        }
        if (target.waypointId() != null) {
            return target.waypointId().equals(waypoint.id());
        }
        return SELECTED_LOCATION_ID.equals(waypoint.id())
            && matches(waypoint.x(), waypoint.z(), displayedDimension);
    }

    public boolean matches(final double x, final double z, final DimensionId dimension) {
        return target != null && target.waypointId() == null
            && target.dimension().equals(dimension)
            && Math.abs(target.x() - x) < 0.01
            && Math.abs(target.z() - z) < 0.01;
    }

    /** Selection is intentionally session-scoped and never leaks across worlds or dimensions. */
    public void onSessionChanged(final SessionGuard.Session session) {
        if (!session.active() || currentSessionToken != session.token()) {
            clear();
        }
        currentSessionToken = session.active() ? session.token() : Long.MIN_VALUE;
    }
}
