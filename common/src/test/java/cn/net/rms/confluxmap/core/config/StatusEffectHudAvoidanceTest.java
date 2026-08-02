package cn.net.rms.confluxmap.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class StatusEffectHudAvoidanceTest {
    @Test
    void shiftsVisibleEffectRowsToTheLeftOfAnOverlappingMinimap() {
        final MinimapPlacement.Layout minimap = new MinimapPlacement.Layout(196, 4, 100);

        assertEquals(-108, StatusEffectHudAvoidance.horizontalShift(300, 1, 2, 1, minimap));
    }

    @Test
    void leavesEffectsInPlaceWhenOnlyTheUnusedRowAreaOverlaps() {
        final MinimapPlacement.Layout minimap = new MinimapPlacement.Layout(196, 28, 100);

        assertEquals(0, StatusEffectHudAvoidance.horizontalShift(300, 1, 2, 0, minimap));
    }

    @Test
    void leavesEffectsInPlaceWhenTheMinimapIsElsewhere() {
        final MinimapPlacement.Layout minimap = new MinimapPlacement.Layout(4, 4, 100);

        assertEquals(0, StatusEffectHudAvoidance.horizontalShift(300, 1, 2, 2, minimap));
    }

    @Test
    void leavesEffectsInPlaceWhenNoIconsAreVisible() {
        final MinimapPlacement.Layout minimap = new MinimapPlacement.Layout(196, 4, 100);

        assertEquals(0, StatusEffectHudAvoidance.horizontalShift(300, 1, 0, 0, minimap));
    }
}
