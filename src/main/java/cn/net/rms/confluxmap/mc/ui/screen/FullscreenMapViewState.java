package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Remembers the {@link FullscreenMapScreen}'s zoom in each dimension for the
 * lifetime of one play session. The center always comes from the current player
 * when a new map screen opens. Pure UI state (no disk persistence, no MC types) -
 * deliberately not core/ only because it's owned by the composition root alongside
 * the other mc/ services, not because it needs to be.
 *
 * <p>Dimension switches rotate the {@link SessionGuard.Session} token but keep the
 * world identity active, so they preserve these scales. A new world identity clears them
 * just like a real session end, via {@link #onSessionChanged}.
 */
public final class FullscreenMapViewState {
    /** Blocks-per-pixel scale, per the fullscreen map's continuous zoom (see FullscreenMapScreen). */
    public record View(double centerX, double centerZ, double scale) {
    }

    private final Map<DimensionId, Double> scalePerDimension = new HashMap<>();
    private WorldIdentity currentWorld;

    public View viewForOpening(
        final DimensionId dimension,
        final double playerX,
        final double playerZ,
        final double defaultScale
    ) {
        return new View(
            playerX,
            playerZ,
            scalePerDimension.getOrDefault(dimension, defaultScale)
        );
    }

    public void rememberScale(final DimensionId dimension, final double scale) {
        scalePerDimension.put(dimension, scale);
    }

    /** Session listener: keep zoom levels across dimensions, but never across world identities. */
    public void onSessionChanged(final SessionGuard.Session session) {
        if (!session.active()) {
            scalePerDimension.clear();
            currentWorld = null;
            return;
        }
        if (!Objects.equals(currentWorld, session.world())) {
            scalePerDimension.clear();
            currentWorld = session.world();
        }
    }
}
