package cn.net.rms.confluxmap.mc.ui.hud;

import cn.net.rms.confluxmap.bridge.GameBridge;
import cn.net.rms.confluxmap.bridge.PlayerView;
import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.radar.RadarEntry;
import cn.net.rms.confluxmap.core.radar.RadarViewRange;
import cn.net.rms.confluxmap.core.radar.ServerPlayerRadarEntries;
import cn.net.rms.confluxmap.core.radar.ServerPlayerRadarState;
import cn.net.rms.confluxmap.mc.radar.EntityIconManager;
import cn.net.rms.confluxmap.mc.radar.EntityRadarScanner;
import cn.net.rms.confluxmap.mc.radar.RadarMarkerRenderer;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;

/**
 * The minimap's entity radar: owns the {@link RadarViewRange} the scanner culls
 * against (zeroed whenever no minimap surface is shown) and draws each
 * {@link RadarEntry} in the scanner's latest snapshot.
 */
final class MinimapRadarPainter {
    private final MinecraftClient client;
    private final ConfluxConfig config;
    private final GameBridge gameBridge;
    private final EntityRadarScanner radarScanner;
    private final EntityIconManager iconManager;
    private final ServerPlayerRadarState serverPlayerRadar;
    private final RadarViewRange radarViewRange;

    MinimapRadarPainter(
        final MinecraftClient client,
        final ConfluxConfig config,
        final GameBridge gameBridge,
        final EntityRadarScanner radarScanner,
        final EntityIconManager iconManager,
        final ServerPlayerRadarState serverPlayerRadar,
        final RadarViewRange radarViewRange
    ) {
        this.client = client;
        this.config = config;
        this.gameBridge = gameBridge;
        this.radarScanner = radarScanner;
        this.iconManager = iconManager;
        this.serverPlayerRadar = serverPlayerRadar;
        this.radarViewRange = radarViewRange;
    }

    /** Zeroes the scan range so the scanner stops scanning nothing in particular. */
    void stop() {
        radarViewRange.set(0);
    }

    /**
     * Radar scans exactly what this frame's minimap will show: the circle's radius, or
     * the square's half-diagonal (so a corner-cropped mob is still caught by the scan).
     */
    void updateViewRange(final int contentSize, final float blocksPerPixel, final boolean circle) {
        final double visibleRadius = contentSize / 2.0
            * blocksPerPixel
            * (circle ? 1.0 : Math.sqrt(2));
        radarViewRange.set(visibleRadius);
    }

    /**
     * Draws each {@link RadarEntry} in {@link EntityRadarScanner}'s latest snapshot.
     * Players keep their portraits; holding the player-list key expands the other
     * colored diamond markers into portraits or item icons. Positions use the same
     * world-delta * pxPerBlock projection as the tiles, but — like the cardinal
     * letters — rotate the offset by {@code mapAngle} explicitly and draw the
     * marker shape unrotated afterward, so markers stay upright regardless of the
     * minimap's current rotation.
     */
    void drawRadar(
        final GuiDraw draw,
        final float centerX,
        final float centerY,
        final int size,
        final float mapAngle,
        final PlayerView player,
        final float tickDelta,
        final float blocksPerPixel,
        final float fadeAlpha
    ) {
        if (!config.radarEnabled || client.world == null) {
            return;
        }
        final float pxPerBlock = 1f / blocksPerPixel;
        final float cullRadiusSq = (size / 2f) * (size / 2f);
        final double rad = Math.toRadians(mapAngle);
        final float cos = (float) Math.cos(rad);
        final float sin = (float) Math.sin(rad);
        final List<RadarMarkerRenderer.Marker> markers = new ArrayList<>();
        final boolean playerListPressed = MinecraftAccess.isPlayerListKeyPressed(client);
        final RadarMarkerRenderer.Presentation presentation =
            RadarMarkerRenderer.Presentation.minimap(
                config.radarDisplayMode, playerListPressed, config.radarShowPlayerNames,
                config.radarAlwaysShowPlayerNames
            );
        final List<ServerPlayerRadarState.PlayerView> serverPlayers =
            config.radarShowPlayers
                ? serverPlayerRadar.playersIn(gameBridge.session().dimension(), System.currentTimeMillis())
                : List.of();
        final List<RadarEntry> entries = ServerPlayerRadarEntries.merge(
            radarScanner.snapshot(),
            serverPlayers,
            client.player == null ? null : client.player.getUuid(),
            player.y()
        );
        final Optional<ServerPlayerRadarState.HighlightView> highlight =
            serverPlayerRadar.highlightedIn(
                gameBridge.session().dimension(),
                System.currentTimeMillis(),
                config.radarPlayerHighlightGhostSeconds * 1_000L
            );
        final List<RadarEntry> visibleEntries;
        if (highlight.isPresent() && highlight.get().ghost()) {
            visibleEntries = new ArrayList<>(entries);
            visibleEntries.add(ServerPlayerRadarEntries.entry(highlight.get().player(), player.y()));
        } else {
            visibleEntries = entries;
        }
        for (final RadarEntry entry : visibleEntries) {
            double ex = entry.x();
            double ez = entry.z();
            int yDelta = entry.yDelta();
            // Prefer a live, per-frame interpolated position over the scan-time snapshot so
            // motion is smooth every frame instead of snapping once per scan interval (spec sec 5).
            final Entity live = client.world.getEntityById(entry.entityId());
            if (live != null) {
                ex = MathHelper.lerp(tickDelta, live.prevX, live.getX());
                ez = MathHelper.lerp(tickDelta, live.prevZ, live.getZ());
                yDelta = (int) Math.round(live.getY() - player.y());
            }

            final float dirX = (float) ((ex - player.x()) * pxPerBlock);
            final float dirY = (float) ((ez - player.z()) * pxPerBlock);
            float screenX = dirX * cos - dirY * sin;
            float screenY = dirX * sin + dirY * cos;
            final boolean highlighted = entry.playerId() != null
                && serverPlayerRadar.isHighlighted(entry.playerId());
            final boolean outside = dirX * dirX + dirY * dirY > cullRadiusSq;
            if (outside) {
                if (!highlighted) {
                    continue;
                }
                final float limit = size / 2f - Math.max(5f, config.radarIconSize / 2f) - 2f;
                final float divisor = config.minimapShape == ConfluxConfig.Shape.CIRCLE
                    ? (float) Math.hypot(screenX, screenY)
                    : Math.max(Math.abs(screenX), Math.abs(screenY));
                if (divisor > 0f) {
                    final float factor = limit / divisor;
                    screenX *= factor;
                    screenY *= factor;
                }
            }
            final boolean ghost = highlight.isPresent()
                && highlight.get().ghost()
                && entry.playerId() != null
                && entry.playerId().equals(highlight.get().player().playerId());
            markers.add(new RadarMarkerRenderer.Marker(
                entry,
                centerX + screenX,
                centerY + screenY,
                yDelta,
                live,
                highlighted,
                ghost,
                ghost ? highlight.get().destination() : null
            ));
        }
        RadarMarkerRenderer.drawAll(
            draw, client, config, iconManager, markers, presentation, marker -> true, fadeAlpha
        );
    }
}
