package cn.net.rms.confluxmap.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ToastHudAvoidanceTest {
    /** The stack a right-aligned column of default-sized toasts occupies. */
    private static HudRect stack(final int screenWidth, final int width, final int height) {
        return new HudRect(screenWidth - width, 0, screenWidth, height);
    }

    @Test
    void placesToastStackBelowAMinimapInTheVanillaToastColumn() {
        final MinimapPlacement.Layout minimap = new MinimapPlacement.Layout(196, 4, 100);

        assertEquals(108, ToastHudAvoidance.verticalShift(300, minimap, 0, stack(300, 160, 32)));
    }

    @Test
    void placesToastStackBelowMinimapInformationText() {
        final MinimapPlacement.Layout minimap = new MinimapPlacement.Layout(196, 4, 100);
        final int informationHeight = MinimapInformationLayout.height(true, true, true);

        assertEquals(
            141,
            ToastHudAvoidance.verticalShift(300, minimap, informationHeight, stack(300, 160, 32))
        );
    }

    @Test
    void leavesToastStackAtTheTopWhenTheMinimapIsOutsideItsColumn() {
        final MinimapPlacement.Layout minimap = new MinimapPlacement.Layout(4, 4, 100);

        assertEquals(0, ToastHudAvoidance.verticalShift(300, minimap, 0, stack(300, 160, 32)));
    }

    @Test
    void leavesToastStackAtTheTopWhenOneToastCannotFitBelowTheMinimap() {
        final MinimapPlacement.Layout minimap = new MinimapPlacement.Layout(16, 4, 100);

        assertEquals(0, ToastHudAvoidance.verticalShift(120, minimap, 0, stack(120, 160, 32)));
    }

    @Test
    void leavesToastStackAtTheTopWhenNothingWasDrawn() {
        final MinimapPlacement.Layout minimap = new MinimapPlacement.Layout(196, 4, 100);

        assertEquals(0, ToastHudAvoidance.verticalShift(300, minimap, 0, null));
    }

    @Test
    void refusesToShiftATallStackThatWouldRunOffTheBottom() {
        final MinimapPlacement.Layout minimap = new MinimapPlacement.Layout(196, 4, 100);

        // A single default toast fits below the minimap on a 160px screen.
        assertEquals(108, ToastHudAvoidance.verticalShift(160, minimap, 0, stack(300, 160, 32)));
        // A three-line system toast is 56 tall, so the same slot no longer holds it.
        assertEquals(0, ToastHudAvoidance.verticalShift(160, minimap, 0, stack(300, 160, 56)));
    }

    @Test
    void detectsAWideToastThatTheDefaultWidthWouldHaveMissed() {
        final MinimapPlacement.Layout minimap = new MinimapPlacement.Layout(40, 4, 100);

        // The vanilla-width column starts at 140, clear of a minimap ending at 140.
        assertEquals(0, ToastHudAvoidance.verticalShift(300, minimap, 0, stack(300, 160, 32)));
        // A wider notification reaches into it and has to move.
        assertEquals(108, ToastHudAvoidance.verticalShift(300, minimap, 0, stack(300, 240, 32)));
    }

    @Test
    void leavesToastStackAloneWhenTheMinimapSitsBelowIt() {
        final MinimapPlacement.Layout minimap = new MinimapPlacement.Layout(196, 120, 100);

        assertEquals(0, ToastHudAvoidance.verticalShift(300, minimap, 0, stack(300, 160, 32)));
    }
}
