package cn.net.rms.confluxmap.mc.ui.world;

import cn.net.rms.confluxmap.bridge.GameBridge;
import cn.net.rms.confluxmap.bridge.PlayerView;
import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.radar.ServerPlayerRadarState;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import cn.net.rms.confluxmap.core.waypoint.WaypointRenderCatalog;
import cn.net.rms.confluxmap.core.waypoint.WaypointRenderEntry;
import cn.net.rms.confluxmap.mc.render.WorldMatrices;
import cn.net.rms.confluxmap.mc.ui.WaypointMarkerRenderer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Gives waypoints an in-world presence (user feedback driving this slice: "waypoints
 * have NO in-world presence"): a vertical translucent beam at each visible waypoint's
 * column, and a camera-facing name/distance label floating above it.
 *
 * <p>This class is the version-free per-frame orchestrator: it collects the waypoints to
 * render (including highlight ghosts and selected-location synthetic entries), selects
 * which labels exist, animates the aim/select reveal, and decides alphas and what gets
 * published to the item-icon HUD flavor. Every version difference lives behind the seams it
 * drives: {@link WorldOverlayEvents} registers the render hook for the running Minecraft
 * line and adapts its native context into an {@link OverlayFrame}; {@link BeamPass} and
 * {@link LabelPass} own the per-version state brackets and submit paths around the passes.
 * The beam geometry lives in {@link WaypointBeamPainter}, the 3D label billboard in {@link
 * WaypointLabelPainter}, and the shared distance/scale math and label styling in {@link
 * WaypointLabelMetrics}/{@link WaypointLabelStyle}.
 *
 * <p>The beam and HUD label have deliberately separate distance rules (see
 * {@link WaypointLabelMetrics}). Label size follows perspective, and aiming at or
 * selecting a label zooms it back to full apparent size.
 */
public final class WaypointWorldRenderer {
    private static final int SELECTED_LOCATION_COLOR = 0xFFFFE066;

    private final MinecraftClient client;
    private final ConfluxConfig config;
    private final GameBridge gameBridge;
    private final WaypointRenderCatalog waypointRenderCatalog;
    private final WaypointHighlightState waypointHighlightState;
    private final ServerPlayerRadarState serverPlayerRadar;
    private final WaypointItemHudRenderer waypointItemHudRenderer;
    private final BeamPass beamPass;
    private final LabelPass labelPass;
    private final Map<UUID, Float> labelAnimationProgress = new HashMap<>();
    private long lastAnimationNanos;
    private SessionGuard.Session lastSnapshotSession;

    public WaypointWorldRenderer(
        final MinecraftClient client,
        final ConfluxConfig config,
        final GameBridge gameBridge,
        final WaypointRenderCatalog waypointRenderCatalog,
        final WaypointHighlightState waypointHighlightState,
        final ServerPlayerRadarState serverPlayerRadar,
        final WaypointItemHudRenderer waypointItemHudRenderer
    ) {
        this.client = client;
        this.config = config;
        this.gameBridge = gameBridge;
        this.waypointRenderCatalog = waypointRenderCatalog;
        this.waypointHighlightState = waypointHighlightState;
        this.serverPlayerRadar = serverPlayerRadar;
        this.waypointItemHudRenderer = waypointItemHudRenderer;
        this.beamPass = BeamPass.of();
        this.labelPass = LabelPass.of(client, config);
    }

    public void register() {
        WorldOverlayEvents.register(this);
    }

    void renderBeams(final OverlayFrame frame) {
        if (!config.waypointBeamsEnabled) {
            return;
        }
        if (!gameBridge.session().active()) {
            return;
        }
        final Optional<PlayerView> playerViewOpt = gameBridge.player(frame.tickDelta);
        if (playerViewOpt.isEmpty()) {
            return;
        }
        final PlayerView player = playerViewOpt.get();
        final DimensionId currentDimension = gameBridge.session().dimension();
        final double maxDistance = WaypointLabelMetrics.beamVisibleDistance(
            MinecraftAccess.viewDistance(client)
        );
        if (maxDistance <= 0.0) {
            return;
        }
        final double bottomY = client.world.getBottomY();
        final double topY = client.world.getTopY();
        final List<WaypointRenderEntry> waypoints = waypointsForRender(currentDimension);
        final boolean hasHighlight = waypointHighlightState.hasRenderableTarget(
            waypoints, currentDimension
        ) || serverPlayerRadar.highlightedPlayerId().isPresent();

        beamPass.beginPass(frame);
        for (final WaypointRenderEntry waypoint : waypoints) {
            final double worldX = waypoint.x();
            final double worldZ = waypoint.z();
            final double dx = worldX - player.x();
            final double dz = worldZ - player.z();
            final double renderDistance = waypointHighlightState.renderDistance(
                waypoint, currentDimension, player.x(), player.y(), player.z()
            );
            if (renderDistance > maxDistance) {
                continue;
            }
            final double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
            final boolean selected = isSelected(waypoint, currentDimension);
            beamPass.draw(
                worldX, worldZ, bottomY, topY,
                waypoint.colorArgb(), horizontalDistance, maxDistance,
                highlightVisibilityAlpha(selected, hasHighlight)
                    * playerHighlightAlpha(waypoint, currentDimension)
            );
        }
        beamPass.endPass();
    }

    void renderHud(final OverlayFrame frame) {
        // A new session or dimension invalidates the HUD label matrices captured during the
        // previous world pass; the current pass re-captures them right after this.
        final SessionGuard.Session session = gameBridge.session();
        if (!session.equals(lastSnapshotSession)) {
            lastSnapshotSession = session;
            WorldMatrices.clearHudFrame();
        }
        if (!config.waypointLabelsEnabled) {
            labelAnimationProgress.clear();
            waypointItemHudRenderer.publish(List.of());
            return;
        }
        if (!gameBridge.session().active()) {
            waypointItemHudRenderer.publish(List.of());
            return;
        }
        final Optional<PlayerView> playerViewOpt = gameBridge.player(frame.tickDelta);
        if (playerViewOpt.isEmpty()) {
            waypointItemHudRenderer.publish(List.of());
            return;
        }
        final PlayerView player = playerViewOpt.get();
        final DimensionId currentDimension = gameBridge.session().dimension();
        final double maxDistance = WaypointLabelMetrics.maxLabelDistance(config.waypointRenderDistance);
        final double labelProjectionDistance = WaypointLabelMetrics.labelProjectionDistance(
            MinecraftAccess.viewDistance(client)
        );
        final List<WaypointRenderEntry> waypoints = waypointsForRender(currentDimension);
        final LabelSelection labelSelection = selectLabels(
            waypoints,
            frame.cameraYaw,
            frame.cameraPitch,
            frame.cameraPos,
            player.x(),
            player.y(),
            player.z(),
            maxDistance,
            waypointHighlightState,
            currentDimension,
            serverPlayerRadar.highlightedPlayerId().orElse(null)
        );
        final float animationDeltaSeconds = animationDeltaSeconds();
        final Set<UUID> visibleWaypointIds = new HashSet<>(labelSelection.candidates().size());
        final List<WaypointItemHudRenderer.Label> itemLabels = new ArrayList<>();

        if (!labelSelection.candidates().isEmpty()) {
            labelPass.beginPass(frame);
            if (WorldOverlayEvents.debugRenderEnabled()) {
                final LabelCandidate first = labelSelection.candidates().get(0);
                WorldOverlayEvents.renderState("end-hud", frame, new double[] {
                    first.waypoint().x() - frame.cameraPos.x,
                    first.waypoint().y() + WaypointLabelMetrics.LABEL_Y_OFFSET - frame.cameraPos.y,
                    first.waypoint().z() - frame.cameraPos.z
                });
            }
            labelPass.beginLabels();
            try {
                for (final LabelCandidate candidate : labelSelection.candidates()) {
                    final WaypointRenderEntry waypoint = candidate.waypoint();
                    visibleWaypointIds.add(waypoint.id());
                    final double renderDistance = candidate.selected()
                        ? waypointHighlightState.renderDistance(
                            waypoint, currentDimension, player.x(), player.y(), player.z()
                        )
                        : candidate.distance();
                    final boolean targeted = candidate.selected()
                        || waypoint.id().equals(labelSelection.targetedWaypointId());
                    final float progress = updateLabelAnimation(waypoint.id(), targeted, animationDeltaSeconds);
                    final UUID playerId = serverPlayerRadar.isHighlighted(waypoint.id())
                        ? waypoint.id() : null;
                    if (playerId != null
                        || !WaypointMarkerRenderer.itemIcon(waypoint.iconItemId()).isEmpty()) {
                        itemLabels.add(new WaypointItemHudRenderer.Label(
                            waypoint,
                            renderDistance,
                            labelProjectionDistance,
                            progress,
                            highlightVisibilityAlpha(
                                candidate.selected(), labelSelection.hasHighlight()
                            ) * playerHighlightAlpha(waypoint, currentDimension),
                            candidate.selected(),
                            playerId
                        ));
                        continue;
                    }
                    labelPass.drawLabel(
                        waypoint.x(), waypoint.y(), waypoint.z(),
                        waypoint, renderDistance, labelProjectionDistance, progress,
                        highlightVisibilityAlpha(candidate.selected(), labelSelection.hasHighlight())
                            * playerHighlightAlpha(waypoint, currentDimension),
                        candidate.selected()
                    );
                }
                labelPass.endLabels();
            } finally {
                labelPass.endPass();
            }
        }

        waypointItemHudRenderer.publish(itemLabels);
        labelAnimationProgress.keySet().retainAll(visibleWaypointIds);
    }

    private List<WaypointRenderEntry> waypointsForRender(final DimensionId dimension) {
        final List<WaypointRenderEntry> base = waypointRenderCatalog.snapshot(dimension);
        final Optional<ServerPlayerRadarState.HighlightView> playerTarget =
            serverPlayerRadar.highlightedIn(
                dimension,
                System.currentTimeMillis(),
                config.radarPlayerHighlightGhostSeconds * 1_000L
            );
        final Optional<WaypointHighlightState.Target> target =
            waypointHighlightState.locationTargetIn(dimension);
        if (target.isEmpty() && playerTarget.isEmpty()) {
            return base;
        }
        final List<WaypointRenderEntry> result = new ArrayList<>(base.size() + 2);
        result.addAll(base);
        target.ifPresent(value -> result.add(selectedTargetEntry(value, dimension)));
        playerTarget.ifPresent(value -> result.add(new WaypointRenderEntry(
            value.player().playerId(),
            value.player().name(),
            dimension,
            value.player().x(),
            value.player().y(),
            value.player().z(),
            SELECTED_LOCATION_COLOR,
            Waypoint.Type.NORMAL,
            WaypointRenderEntry.Source.LOCAL
        )));
        return result;
    }

    private boolean isSelected(
        final WaypointRenderEntry waypoint,
        final DimensionId dimension
    ) {
        return waypointHighlightState.matchesEntry(waypoint, dimension)
            || serverPlayerRadar.isHighlighted(waypoint.id());
    }

    private float playerHighlightAlpha(
        final WaypointRenderEntry waypoint,
        final DimensionId dimension
    ) {
        if (!serverPlayerRadar.isHighlighted(waypoint.id())) {
            return 1f;
        }
        return serverPlayerRadar.highlightedIn(
            dimension,
            System.currentTimeMillis(),
            config.radarPlayerHighlightGhostSeconds * 1_000L
        ).filter(ServerPlayerRadarState.HighlightView::ghost).isPresent() ? 0.5f : 1f;
    }

    private WaypointRenderEntry selectedTargetEntry(
        final WaypointHighlightState.Target target,
        final DimensionId displayedDimension
    ) {
        return WaypointHighlightState.locationEntry(
            target,
            WaypointHighlightState.locationDisplayName(target, displayedDimension),
            selectedLocationY(target),
            SELECTED_LOCATION_COLOR
        );
    }

    private float highlightVisibilityAlpha(
        final boolean selected,
        final boolean hasHighlight
    ) {
        return selected || !hasHighlight
            ? 1f
            : config.waypointHighlightDimOpacity / 100f;
    }

    private double selectedLocationY(final WaypointHighlightState.Target target) {
        final double y = target.yKnown() ? target.y() : WaypointHighlightState.DEFAULT_LOCATION_Y;
        return MathHelper.clamp(y, client.world.getBottomY(), client.world.getTopY() - 1.0);
    }

    static LabelSelection selectLabels(
        final List<WaypointRenderEntry> waypoints,
        final float cameraYaw,
        final float cameraPitch,
        final Vec3d cameraPos,
        final double playerX,
        final double playerY,
        final double playerZ,
        final double maxDistance,
        final WaypointHighlightState waypointHighlightState,
        final DimensionId currentDimension
    ) {
        return selectLabels(
            waypoints, cameraYaw, cameraPitch, cameraPos,
            playerX, playerY, playerZ, maxDistance,
            waypointHighlightState, currentDimension, null
        );
    }

    static LabelSelection selectLabels(
        final List<WaypointRenderEntry> waypoints,
        final float cameraYaw,
        final float cameraPitch,
        final Vec3d cameraPos,
        final double playerX,
        final double playerY,
        final double playerZ,
        final double maxDistance,
        final WaypointHighlightState waypointHighlightState,
        final DimensionId currentDimension,
        final UUID highlightedPlayerId
    ) {
        final double maxDistanceSquared = maxDistance * maxDistance;
        final List<LabelCandidate> candidates = new ArrayList<>();
        WaypointRenderEntry targetedWaypoint = null;
        boolean hasHighlight = false;
        double bestAlignment = -1.0;
        double bestDistance = Double.POSITIVE_INFINITY;

        for (final WaypointRenderEntry waypoint : waypoints) {
            final double playerDx = waypoint.x() - playerX;
            final double playerDy = waypoint.y() - playerY;
            final double playerDz = waypoint.z() - playerZ;
            final double playerDistanceSquared =
                playerDx * playerDx + playerDy * playerDy + playerDz * playerDz;
            final boolean selected = waypointHighlightState.matchesEntry(
                waypoint, currentDimension
            ) || waypoint.id().equals(highlightedPlayerId);
            hasHighlight |= selected;
            // The configured cutoff hides a label entirely, even when aimed at; only a
            // selected/highlighted waypoint renders regardless of distance.
            if (playerDistanceSquared > maxDistanceSquared && !selected) {
                continue;
            }
            candidates.add(new LabelCandidate(waypoint, playerDistanceSquared, selected));

            final double dx = waypoint.x() - cameraPos.x;
            final double dy = waypoint.y() + WaypointLabelMetrics.LABEL_Y_OFFSET - cameraPos.y;
            final double dz = waypoint.z() - cameraPos.z;
            final double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance <= 0.001) {
                continue;
            }
            final double alignment = WaypointHudMotion.alignment(
                cameraYaw, cameraPitch, dx, dy, dz, distance
            );
            if (!WaypointHudMotion.insideTargetCone(alignment, distance)) {
                continue;
            }
            if (alignment > bestAlignment || (alignment == bestAlignment && distance < bestDistance)) {
                targetedWaypoint = waypoint;
                bestAlignment = alignment;
                bestDistance = distance;
            }
        }

        return new LabelSelection(
            List.copyOf(candidates),
            targetedWaypoint == null ? null : targetedWaypoint.id(),
            hasHighlight
        );
    }

    static record LabelCandidate(
        WaypointRenderEntry waypoint,
        double distanceSquared,
        boolean selected
    ) {
        double distance() {
            return Math.sqrt(distanceSquared);
        }
    }

    static record LabelSelection(
        List<LabelCandidate> candidates,
        UUID targetedWaypointId,
        boolean hasHighlight
    ) {
    }

    private float animationDeltaSeconds() {
        final long now = System.nanoTime();
        if (lastAnimationNanos == 0L) {
            lastAnimationNanos = now;
            return 0.0f;
        }
        final float delta = MathHelper.clamp((now - lastAnimationNanos) / 1_000_000_000.0f, 0.0f, 0.1f);
        lastAnimationNanos = now;
        return delta;
    }

    private float updateLabelAnimation(final UUID waypointId, final boolean targeted, final float deltaSeconds) {
        final float current = labelAnimationProgress.getOrDefault(waypointId, 0.0f);
        final float next = WaypointHudMotion.advance(current, targeted, deltaSeconds);
        if (next <= 0.0f && !targeted) {
            labelAnimationProgress.remove(waypointId);
        } else {
            labelAnimationProgress.put(waypointId, next);
        }
        return next;
    }
}
