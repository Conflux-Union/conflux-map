package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import org.junit.jupiter.api.Test;

class FullscreenMapViewStateTest {
    @Test
    void reopeningMapCentersOnCurrentPlayerAndKeepsZoom() {
        final FullscreenMapViewState state = new FullscreenMapViewState();
        final DimensionId dimension = DimensionId.OVERWORLD;
        state.rememberScale(dimension, 4.0);

        final FullscreenMapViewState.View reopened = state.viewForOpening(
            dimension, 12.5, -34.5, 2.0
        );

        assertEquals(12.5, reopened.centerX());
        assertEquals(-34.5, reopened.centerZ());
        assertEquals(4.0, reopened.scale());
    }

    @Test
    void dimensionChangeWithinWorldKeepsRememberedScale() {
        final FullscreenMapViewState state = new FullscreenMapViewState();
        final WorldIdentity world = WorldIdentity.singleplayer("same-world");

        state.onSessionChanged(new SessionGuard.Session(1L, world, DimensionId.OVERWORLD));
        state.rememberScale(DimensionId.OVERWORLD, 4.0);
        state.onSessionChanged(new SessionGuard.Session(2L, world, DimensionId.NETHER));

        assertEquals(
            4.0,
            state.viewForOpening(DimensionId.OVERWORLD, 12.5, -34.5, 2.0).scale()
        );
    }

    @Test
    void worldIdentityChangeDropsRememberedScale() {
        final FullscreenMapViewState state = new FullscreenMapViewState();
        final DimensionId dimension = DimensionId.OVERWORLD;

        state.onSessionChanged(new SessionGuard.Session(
            1L, WorldIdentity.singleplayer("first-world"), dimension
        ));
        state.rememberScale(dimension, 4.0);

        state.onSessionChanged(new SessionGuard.Session(
            2L, WorldIdentity.singleplayer("second-world"), dimension
        ));

        assertEquals(2.0, state.viewForOpening(dimension, 12.5, -34.5, 2.0).scale());
    }
}
