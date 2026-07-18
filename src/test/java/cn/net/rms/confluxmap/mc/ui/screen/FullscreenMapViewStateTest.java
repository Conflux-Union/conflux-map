package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import org.junit.jupiter.api.Test;

class FullscreenMapViewStateTest {
    @Test
    void dimensionChangeWithinWorldKeepsRememberedView() {
        final FullscreenMapViewState state = new FullscreenMapViewState();
        final FullscreenMapViewState.View view = new FullscreenMapViewState.View(100.0, 200.0, 2.0);
        final WorldIdentity world = WorldIdentity.singleplayer("same-world");

        state.onSessionChanged(new SessionGuard.Session(1L, world, DimensionId.OVERWORLD));
        state.put(DimensionId.OVERWORLD, view);
        state.onSessionChanged(new SessionGuard.Session(2L, world, DimensionId.NETHER));

        assertSame(view, state.get(DimensionId.OVERWORLD));
    }

    @Test
    void worldIdentityChangeDropsRememberedView() {
        final FullscreenMapViewState state = new FullscreenMapViewState();
        final DimensionId dimension = DimensionId.OVERWORLD;
        state.put(dimension, new FullscreenMapViewState.View(100.0, 200.0, 2.0));

        state.onSessionChanged(new SessionGuard.Session(
            1L, WorldIdentity.singleplayer("first-world"), dimension
        ));
        state.put(dimension, new FullscreenMapViewState.View(100.0, 200.0, 2.0));

        state.onSessionChanged(new SessionGuard.Session(
            2L, WorldIdentity.singleplayer("second-world"), dimension
        ));

        assertNull(state.get(dimension));
    }
}
