package cn.net.rms.confluxmap.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ToastHudAvoidanceTest {
    @Test
    void placesToastStackBelowAMinimapInTheVanillaToastColumn() {
        final MinimapPlacement.Layout minimap = new MinimapPlacement.Layout(196, 4, 100);

        assertEquals(108, ToastHudAvoidance.verticalShift(300, 300, minimap));
    }

    @Test
    void leavesToastStackAtTheTopWhenTheMinimapIsOutsideItsColumn() {
        final MinimapPlacement.Layout minimap = new MinimapPlacement.Layout(4, 4, 100);

        assertEquals(0, ToastHudAvoidance.verticalShift(300, 300, minimap));
    }

    @Test
    void leavesToastStackAtTheTopWhenOneToastCannotFitBelowTheMinimap() {
        final MinimapPlacement.Layout minimap = new MinimapPlacement.Layout(16, 4, 100);

        assertEquals(0, ToastHudAvoidance.verticalShift(120, 120, minimap));
    }
}
