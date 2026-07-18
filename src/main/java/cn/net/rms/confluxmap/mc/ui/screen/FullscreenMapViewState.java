package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Remembers where the player last left the {@link FullscreenMapScreen}'s view in
 * each dimension, for the lifetime of one play session. Pure UI state (no disk
 * persistence, no MC types) - deliberately not core/ only because it's owned by
 * the composition root alongside the other mc/ services, not because it needs to be.
 *
 * <p>Dimension switches rotate the {@link SessionGuard.Session} token but keep the
 * world identity active, so they preserve this map. A new world identity clears it
 * just like a real session end, via {@link #onSessionChanged}.
 */
public final class FullscreenMapViewState {
    /** Blocks-per-pixel scale, per the fullscreen map's continuous zoom (see FullscreenMapScreen). */
    public record View(double centerX, double centerZ, double scale) {
    }

    private final Map<DimensionId, View> perDimension = new HashMap<>();
    private WorldIdentity currentWorld;

    public View get(final DimensionId dimension) {
        return perDimension.get(dimension);
    }

    public void put(final DimensionId dimension, final View view) {
        perDimension.put(dimension, view);
    }

    /** Session listener: keep views across dimensions, but never across world identities. */
    public void onSessionChanged(final SessionGuard.Session session) {
        if (!session.active()) {
            perDimension.clear();
            currentWorld = null;
            return;
        }
        if (!Objects.equals(currentWorld, session.world())) {
            perDimension.clear();
            currentWorld = session.world();
        }
    }
}
