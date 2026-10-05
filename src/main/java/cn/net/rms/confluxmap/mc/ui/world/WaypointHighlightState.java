package cn.net.rms.confluxmap.mc.ui.world;

import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.waypoint.DimensionScale;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import cn.net.rms.confluxmap.core.waypoint.WaypointRenderEntry;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;

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

    public static WaypointRenderEntry locationEntry(
        final Target target,
        final String translatedName,
        final double renderY,
        final int colorArgb
    ) {
        return new WaypointRenderEntry(
            SELECTED_LOCATION_ID,
            translatedName,
            target.dimension(),
            target.x(),
            renderY,
            target.z(),
            colorArgb,
            Waypoint.Type.NORMAL,
            WaypointRenderEntry.Source.LOCAL
        );
    }

    private Target target;
    private long currentSessionToken = Long.MIN_VALUE;
    private WorldIdentity currentWorld = WorldIdentity.NONE;
    private final BooleanSupplier crossDimensionVisible;

    public WaypointHighlightState() {
        this(() -> false);
    }

    /**
     * @param crossDimensionVisible while true, a highlighted location also renders from the
     *                              portal-linked dimension with converted coordinates
     *                              ({@link DimensionScale}) and survives the same-world
     *                              session rotation a dimension change performs
     */
    public WaypointHighlightState(final BooleanSupplier crossDimensionVisible) {
        this.crossDimensionVisible = Objects.requireNonNull(crossDimensionVisible, "crossDimensionVisible");
    }

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

    /**
     * Whether the current target renders while displaying {@code dimension}: always in its own
     * dimension, and for a highlighted location also from the portal-linked dimension when
     * cross-dimension display is enabled. A saved-waypoint target stays confined to its own
     * dimension because the waypoint's own visibility already decides whether its entry
     * reaches the linked dimension.
     */
    private boolean rendersIn(final DimensionId dimension) {
        if (target == null) {
            return false;
        }
        if (target.dimension().equals(dimension)) {
            return true;
        }
        return target.waypointId() == null
            && crossDimensionVisible.getAsBoolean()
            && DimensionScale.isVisibleFrom(target.dimension(), dimension);
    }

    /** Whether the highlighted location (not a saved waypoint) renders from {@code dimension}. */
    private boolean rendersLocationIn(final DimensionId dimension) {
        return target != null && target.waypointId() == null && rendersIn(dimension);
    }

    /**
     * The highlighted location target as it renders from {@code displayedDimension}: x/z are
     * converted into that dimension's coordinate space while {@link Target#dimension()} keeps
     * the stored dimension, matching how {@link WaypointRenderEntry} carries converted
     * cross-dimension waypoints. Empty when the location does not render there.
     */
    public Optional<Target> locationTargetIn(final DimensionId displayedDimension) {
        if (!rendersLocationIn(displayedDimension)) {
            return Optional.empty();
        }
        final Target stored = target;
        if (stored.dimension().equals(displayedDimension)) {
            return Optional.of(stored);
        }
        return Optional.of(Target.location(
            stored.dimension(),
            DimensionScale.convertHorizontal(stored.x(), stored.dimension(), displayedDimension),
            stored.y(),
            DimensionScale.convertHorizontal(stored.z(), stored.dimension(), displayedDimension),
            stored.yKnown()
        ));
    }

    /** Display name of the highlighted-location entry as seen from {@code displayedDimension}. */
    public static String locationDisplayName(final Target target, final DimensionId displayedDimension) {
        final String base = Texts.translatable(SELECTED_LOCATION_TRANSLATION_KEY).getString();
        if (target.dimension().equals(displayedDimension)) {
            return base;
        }
        return base + " (" + dimensionDisplayName(target.dimension()) + ")";
    }

    private static String dimensionDisplayName(final DimensionId dimension) {
        if (dimension.equals(DimensionId.OVERWORLD)) {
            return Texts.translatable("confluxmap.dimension.overworld").getString();
        }
        if (dimension.equals(DimensionId.NETHER)) {
            return Texts.translatable("confluxmap.dimension.the_nether").getString();
        }
        if (dimension.equals(DimensionId.END)) {
            return Texts.translatable("confluxmap.dimension.the_end").getString();
        }
        return dimension.path();
    }

    public boolean hasRenderableTarget(
        final List<WaypointRenderEntry> waypoints,
        final DimensionId displayedDimension
    ) {
        return rendersIn(displayedDimension)
            && waypoints.stream().anyMatch(waypoint -> matchesEntry(waypoint, displayedDimension));
    }

    public double renderDistance(
        final WaypointRenderEntry waypoint,
        final DimensionId displayedDimension,
        final double playerX,
        final double playerY,
        final double playerZ
    ) {
        final double dx = waypoint.x() - playerX;
        final double dz = waypoint.z() - playerZ;
        if (target != null && !target.yKnown() && matchesEntry(waypoint, displayedDimension)) {
            return Math.sqrt(dx * dx + dz * dz);
        }
        final double dy = waypoint.y() - playerY;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
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
        if (target == null) {
            return false;
        }
        if (target.waypointId() != null) {
            return target.waypointId().equals(waypoint.id())
                && target.dimension().equals(displayedDimension);
        }
        return SELECTED_LOCATION_ID.equals(waypoint.id())
            && matches(waypoint.x(), waypoint.z(), displayedDimension);
    }

    /** Matches the rendered x/z of the highlighted location in {@code dimension}'s coordinate space. */
    public boolean matches(final double x, final double z, final DimensionId dimension) {
        return locationTargetIn(dimension)
            .filter(view -> Math.abs(view.x() - x) < 0.01 && Math.abs(view.z() - z) < 0.01)
            .isPresent();
    }

    /**
     * Selection is session-scoped and never leaks into another world. A same-world dimension
     * change rotates the session token; that rotation keeps the selection only while
     * cross-dimension display is enabled, so a highlighted location survives the portal trip
     * and is still selected on the way back.
     */
    public void onSessionChanged(final SessionGuard.Session session) {
        final boolean retained = session.active()
            && (currentSessionToken == session.token()
                || (crossDimensionVisible.getAsBoolean()
                    && currentWorld.equals(session.world())));
        if (!retained) {
            clear();
        }
        currentSessionToken = session.active() ? session.token() : Long.MIN_VALUE;
        currentWorld = session.active() ? session.world() : WorldIdentity.NONE;
    }
}
