package cn.net.rms.confluxmap.mc.ui.hud;

import cn.net.rms.confluxmap.bridge.GameBridge;
import cn.net.rms.confluxmap.bridge.PlayerView;
import cn.net.rms.confluxmap.core.api.CustomMarkerService;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.portal.PortalMarker;
import cn.net.rms.confluxmap.core.portal.PortalService;
import cn.net.rms.confluxmap.core.trail.PlayerTrail;
import cn.net.rms.confluxmap.core.trail.PlayerTrailProjection;
import cn.net.rms.confluxmap.core.waypoint.WaypointRenderCatalog;
import cn.net.rms.confluxmap.core.waypoint.WaypointRenderEntry;
import cn.net.rms.confluxmap.core.waypoint.WaypointVerticalRelation;
import cn.net.rms.confluxmap.mc.render.GuiShapes;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import cn.net.rms.confluxmap.mc.ui.PortalMarkerRenderer;
import cn.net.rms.confluxmap.mc.ui.PlayerMarkerRenderer;
import cn.net.rms.confluxmap.mc.ui.PlayerTrailRenderer;
import cn.net.rms.confluxmap.mc.ui.UiResourceTheme;
import cn.net.rms.confluxmap.mc.ui.WaypointMarkerRenderer;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.MathHelper;

/**
 * Everything projected on top of the minimap tiles: the camera and local player
 * markers, the player trail, and the world's waypoint/portal/custom markers.
 * All markers share one idiom: world deltas scaled by the frame's blocks-per-pixel,
 * rotated by the minimap's angle with an explicit cos/sin (these draw calls run
 * after the tile pass's push/pop, so the active matrix is unrotated), while the
 * glyph itself is drawn upright.
 */
final class MinimapMarkerPainter {
    private static final int PLAYER_MARKER_COLOR = 0xFFFFFFFF;
    private static final float PLAYER_MARKER_BOUNDING_RADIUS = (float) Math.hypot(8f, 8f);
    /** Half of the ~7px-across VoxelMap-style diamond/cross marker (deliverable B). */
    private static final float WAYPOINT_MARKER_HALF_SIZE = 3.5f;

    private final MinecraftClient client;
    private final ConfluxConfig config;
    private final GameBridge gameBridge;
    private final UiResourceTheme uiTheme;
    private final PlayerTrail playerTrail;
    private final WaypointRenderCatalog waypointRenderCatalog;
    private final PortalService portals;
    private final CustomMarkerService customMarkers;

    MinimapMarkerPainter(
        final MinecraftClient client,
        final ConfluxConfig config,
        final GameBridge gameBridge,
        final UiResourceTheme uiTheme,
        final PlayerTrail playerTrail,
        final WaypointRenderCatalog waypointRenderCatalog,
        final PortalService portals,
        final CustomMarkerService customMarkers
    ) {
        this.client = client;
        this.config = config;
        this.gameBridge = gameBridge;
        this.uiTheme = uiTheme;
        this.playerTrail = playerTrail;
        this.waypointRenderCatalog = waypointRenderCatalog;
        this.portals = portals;
        this.customMarkers = customMarkers;
    }

    void drawCameraMarker(
        final MatrixStack matrices,
        final PlayerView viewpoint,
        final float centerX,
        final float centerY,
        final boolean rotate
    ) {
        if (!gameBridge.isCameraDetached()) {
            return;
        }
        PlayerMarkerRenderer.draw(
            client,
            matrices,
            uiTheme,
            config.playerMarkerStyle,
            centerX,
            centerY,
            rotate ? 0f : viewpoint.yawDegrees() + 180f,
            PLAYER_MARKER_COLOR,
            0.5f
        );
    }

    /** Draws the real player's position relative to the active map viewpoint. */
    void drawLocalPlayerMarker(
        final MatrixStack matrices,
        final PlayerView viewpoint,
        final float centerX,
        final float centerY,
        final int size,
        final boolean rotate,
        final float tickDelta,
        final float blocksPerPixel
    ) {
        final Optional<PlayerView> localPlayer = gameBridge.player(tickDelta);
        if (localPlayer.isEmpty()) {
            return;
        }
        final PlayerView player = localPlayer.get();
        final float dx = (float) ((player.x() - viewpoint.x()) / blocksPerPixel);
        final float dz = (float) ((player.z() - viewpoint.z()) / blocksPerPixel);
        final float mapAngle = rotate ? 180f - viewpoint.yawDegrees() : 0f;
        final double radians = Math.toRadians(mapAngle);
        final float cos = (float) Math.cos(radians);
        final float sin = (float) Math.sin(radians);
        float screenDx = dx * cos - dz * sin;
        float screenDy = dx * sin + dz * cos;
        final float limit = playerMarkerEdgeLimit(size);
        if (config.minimapShape == ConfluxConfig.Shape.CIRCLE) {
            final float distance = (float) Math.sqrt(screenDx * screenDx + screenDy * screenDy);
            if (distance > limit) {
                screenDx *= limit / distance;
                screenDy *= limit / distance;
            }
        } else {
            screenDx = MathHelper.clamp(screenDx, -limit, limit);
            screenDy = MathHelper.clamp(screenDy, -limit, limit);
        }
        PlayerMarkerRenderer.draw(
            client,
            matrices,
            uiTheme,
            config.playerMarkerStyle,
            centerX + screenDx,
            centerY + screenDy,
            rotate
                ? player.yawDegrees() - viewpoint.yawDegrees()
                : player.yawDegrees() + 180f,
            PLAYER_MARKER_COLOR
        );
    }

    static float playerMarkerEdgeLimit(final int size) {
        return size / 2f - PLAYER_MARKER_BOUNDING_RADIUS;
    }

    void drawPlayerTrail(
        final MatrixStack matrices,
        final PlayerView player,
        final float centerX,
        final float centerY,
        final int size,
        final float mapAngle,
        final float blocksPerPixel
    ) {
        if (!config.playerTrailEnabled) {
            return;
        }
        PlayerTrailRenderer.draw(
            matrices,
            playerTrail,
            new PlayerTrailProjection(
                player.x(), player.z(), centerX, centerY,
                blocksPerPixel, mapAngle, size, size
            ),
            config.playerTrailDurationSeconds,
            config.playerTrailDotSize
        );
    }

    /**
     * In-range and edge-clamped icons for waypoints visible in the
     * current dimension. Reuses the cardinal letters' exact rotation trick: the
     * marker's screen *position* is computed with the same manual cos/sin rotation
     * as the cardinal letters (this method runs after the tile-drawing push/pop, so
     * the active matrix here is unrotated), while the marker glyph itself is drawn
     * with no rotation applied (upright). Out-of-range waypoints reuse that same
     * icon at the minimap edge so their identity remains visible instead of
     * changing into a generic direction arrow.
     */
    void drawWaypointMarkers(
        final GuiDraw draw,
        final float centerX,
        final float centerY,
        final int size,
        final float mapAngle,
        final PlayerView player,
        final float blocksPerPixel
    ) {
        final float pxPerBlock = 1f / blocksPerPixel;
        final double rad = Math.toRadians(mapAngle);
        final float cos = (float) Math.cos(rad);
        final float sin = (float) Math.sin(rad);
        // Keep the marker plate and its one-pixel outline inside the minimap frame.
        final float limit = size / 2f - WAYPOINT_MARKER_HALF_SIZE - 4f;
        final boolean circleFrame = config.minimapShape == ConfluxConfig.Shape.CIRCLE;
        final DimensionId currentDimension = gameBridge.session().dimension();

        for (final WaypointRenderEntry waypoint : waypointRenderCatalog.snapshot(currentDimension)) {
            final WaypointMarkerOffset offset = waypointMarkerOffset(
                waypoint.x() - player.x(),
                waypoint.z() - player.z(),
                pxPerBlock,
                cos,
                sin,
                limit,
                circleFrame,
                config.waypointEdgeIndicatorsEnabled
            );
            if (offset == null) {
                continue;
            }

            WaypointMarkerRenderer.draw(
                draw, client.textRenderer, waypoint,
                centerX + offset.x(), centerY + offset.y(),
                WAYPOINT_MARKER_HALF_SIZE, 1f, false,
                WaypointVerticalRelation.between(waypoint.y(), player.y())
            );
        }
    }

    /**
     * Screen offset of one minimap waypoint marker: the rotated, zoom-scaled world
     * delta, clamped to the frame edge when it lands outside. Deliberately ignores
     * the configured waypoint visibility distance - that cutoff belongs to the
     * in-world HUD only; the minimap keeps every waypoint in the dimension
     * identifiable through its edge indicator. Returns null for off-frame markers
     * when edge indicators are disabled.
     */
    static WaypointMarkerOffset waypointMarkerOffset(
        final double dx,
        final double dz,
        final float pxPerBlock,
        final float cos,
        final float sin,
        final float limit,
        final boolean circleFrame,
        final boolean edgeIndicatorsEnabled
    ) {
        final float rawX = (float) (dx * pxPerBlock);
        final float rawY = (float) (dz * pxPerBlock);
        final float screenOffX = rawX * cos - rawY * sin;
        final float screenOffY = rawX * sin + rawY * cos;

        final boolean inRange = circleFrame
            ? Math.hypot(screenOffX, screenOffY) <= limit
            : Math.abs(screenOffX) <= limit && Math.abs(screenOffY) <= limit;
        if (inRange) {
            return new WaypointMarkerOffset(screenOffX, screenOffY);
        }
        if (!edgeIndicatorsEnabled) {
            return null;
        }
        final float k = circleFrame
            ? limit / (float) Math.hypot(screenOffX, screenOffY)
            : limit / Math.max(Math.abs(screenOffX), Math.abs(screenOffY));
        return new WaypointMarkerOffset(screenOffX * k, screenOffY * k);
    }

    record WaypointMarkerOffset(float x, float y) {}

    /**
     * Translucent fill over each portal-containing chunk, drawn in the tile
     * pass's own rotated transform (origin at the map center, one axis-aligned
     * 16-block cell per chunk). The square shape is scissor-clipped and the
     * circle is disk-clipped by its off-screen canvas, like the tiles beneath.
     */
    void drawPortalChunkHighlights(
        final MatrixStack matrices,
        final PlayerView player,
        final int size,
        final float blocksPerPixel
    ) {
        if (!config.portalMarkersEnabled || !config.portalChunkHighlightEnabled) {
            return;
        }
        final float pxPerBlock = 1f / blocksPerPixel;
        final float sizePx = 16f * pxPerBlock;
        if (sizePx < 2f) {
            return;
        }
        final float half = size / 2f;
        for (final PortalMarker marker : portals.list(gameBridge.session().dimension())) {
            final float left = (float) ((marker.chunkX() * 16.0 - player.x()) * pxPerBlock);
            final float top = (float) ((marker.chunkZ() * 16.0 - player.z()) * pxPerBlock);
            if (left > half || top > half || left + sizePx < -half || top + sizePx < -half) {
                continue;
            }
            GuiShapes.fillRect(matrices, left, top, sizePx, sizePx, config.portalHighlightColor.argb());
        }
    }

    /**
     * Portal icon plates with the same manual cos/sin projection as
     * {@link #drawWaypointMarkers}, culled (not edge-clamped) when off frame:
     * a portal is a place on the map, not a navigation target the player
     * steers by. Drawn below waypoints so both remain readable where they meet.
     */
    void drawPortalMarkers(
        final GuiDraw draw,
        final float centerX,
        final float centerY,
        final int size,
        final float mapAngle,
        final PlayerView player,
        final float blocksPerPixel
    ) {
        if (!config.portalMarkersEnabled || !config.portalIconsEnabled) {
            return;
        }
        final List<PortalMarker> markers = portals.list(gameBridge.session().dimension());
        if (markers.isEmpty()) {
            return;
        }
        final float pxPerBlock = 1f / blocksPerPixel;
        final double rad = Math.toRadians(mapAngle);
        final float cos = (float) Math.cos(rad);
        final float sin = (float) Math.sin(rad);
        final float halfSize = config.portalIconSize / 2f;
        final float limit = size / 2f - halfSize - 2f;
        final boolean circleFrame = config.minimapShape == ConfluxConfig.Shape.CIRCLE;
        for (final PortalMarker marker : markers) {
            final float rawX = (float) ((marker.anchorX() + 0.5 - player.x()) * pxPerBlock);
            final float rawY = (float) ((marker.anchorZ() + 0.5 - player.z()) * pxPerBlock);
            final float screenOffX = rawX * cos - rawY * sin;
            final float screenOffY = rawX * sin + rawY * cos;
            final boolean inFrame = circleFrame
                ? Math.hypot(screenOffX, screenOffY) <= limit
                : Math.abs(screenOffX) <= limit && Math.abs(screenOffY) <= limit;
            if (!inFrame) {
                continue;
            }
            PortalMarkerRenderer.draw(
                draw, marker.kind(), config.portalIconTexture(marker.kind()),
                centerX + screenOffX, centerY + screenOffY,
                config.portalIconSize, config.portalIconOpacity, false
            );
        }
    }

    /**
     * Third-party markers from the public API, drawn above waypoints with the same
     * world-delta projection as {@link #drawWaypointMarkers} but culled (not clamped to
     * the edge) when they fall outside the frame - external integrations show a moving
     * position, not a navigation target.
     */
    void drawCustomMarkers(
        final GuiDraw draw,
        final float centerX,
        final float centerY,
        final int size,
        final float mapAngle,
        final PlayerView player,
        final float blocksPerPixel
    ) {
        final List<WaypointRenderEntry> entries = customMarkers.renderEntries(
            gameBridge.session().dimension(), true
        );
        if (entries.isEmpty()) {
            return;
        }
        final float pxPerBlock = 1f / blocksPerPixel;
        final double rad = Math.toRadians(mapAngle);
        final float cos = (float) Math.cos(rad);
        final float sin = (float) Math.sin(rad);
        final float limit = size / 2f - WAYPOINT_MARKER_HALF_SIZE - 4f;

        for (final WaypointRenderEntry marker : entries) {
            final float rawX = (float) ((marker.x() - player.x()) * pxPerBlock);
            final float rawY = (float) ((marker.z() - player.z()) * pxPerBlock);
            final float screenOffX = rawX * cos - rawY * sin;
            final float screenOffY = rawX * sin + rawY * cos;
            if (Math.hypot(screenOffX, screenOffY) > limit) {
                continue;
            }
            WaypointMarkerRenderer.draw(
                draw, client.textRenderer, marker,
                centerX + screenOffX, centerY + screenOffY,
                WAYPOINT_MARKER_HALF_SIZE, 1f, false,
                WaypointVerticalRelation.between(marker.y(), player.y())
            );
        }
    }
}
