package cn.net.rms.confluxmap.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class StatusEffectHudAvoidanceTest {
    /** A right-aligned icon row, matching the shape the Fabric adapter derives from vanilla. */
    private static HudRect row(final int screenWidth, final int top, final int icons) {
        return icons <= 0 ? null : new HudRect(screenWidth - 25 * icons, top, screenWidth, top + 24);
    }

    @Test
    void shiftsVisibleEffectRowsToTheLeftOfAnOverlappingMinimap() {
        final MinimapPlacement.Layout minimap = new MinimapPlacement.Layout(196, 4, 100);

        assertEquals(
            -108,
            StatusEffectHudAvoidance.horizontalShift(
                minimap, row(300, 1, 2), row(300, 27, 1)
            )
        );
    }

    @Test
    void leavesEffectsInPlaceWhenOnlyTheUnusedRowAreaOverlaps() {
        final MinimapPlacement.Layout minimap = new MinimapPlacement.Layout(196, 28, 100);

        assertEquals(
            0,
            StatusEffectHudAvoidance.horizontalShift(minimap, row(300, 1, 2), null)
        );
    }

    @Test
    void leavesEffectsInPlaceWhenTheMinimapIsElsewhere() {
        final MinimapPlacement.Layout minimap = new MinimapPlacement.Layout(4, 4, 100);

        assertEquals(
            0,
            StatusEffectHudAvoidance.horizontalShift(
                minimap, row(300, 1, 2), row(300, 27, 2)
            )
        );
    }

    @Test
    void leavesEffectsInPlaceWhenNoIconsAreVisible() {
        final MinimapPlacement.Layout minimap = new MinimapPlacement.Layout(196, 4, 100);

        assertEquals(0, StatusEffectHudAvoidance.horizontalShift(minimap, null, null));
    }

    @Test
    void leavesEffectsVisibleWhenNoInBoundsShiftExists() {
        final MinimapPlacement.Layout minimap = new MinimapPlacement.Layout(4, 4, 292);

        assertEquals(
            0,
            StatusEffectHudAvoidance.horizontalShift(
                minimap, row(300, 1, 2), row(300, 27, 1)
            )
        );
    }

    @Test
    void measuresAnExternallyShrunkOverlayAtItsRenderedSize() {
        final MinimapPlacement.Layout minimap = new MinimapPlacement.Layout(196, 20, 100);
        final HudRect vanillaRow = row(300, 1, 2);
        // Half-scale overlay anchored at the right edge, as a scaling tweak would render it.
        final HudAmbient halved = new HudAmbient(150f, 0f, 0.5f, 0.5f);

        assertEquals(
            -108,
            StatusEffectHudAvoidance.horizontalShift(minimap, vanillaRow, null)
        );
        // At half height the row stops short of the minimap, so nothing has to move.
        assertEquals(
            0,
            StatusEffectHudAvoidance.horizontalShift(minimap, halved.apply(vanillaRow), null)
        );
    }
}
