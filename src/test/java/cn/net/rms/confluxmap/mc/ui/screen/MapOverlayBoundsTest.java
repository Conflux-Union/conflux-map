package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.confluxmap.core.radar.RadarCategory;
import org.junit.jupiter.api.Test;

final class MapOverlayBoundsTest {
    @Test
    void structureAndPortalBoundsPreserveSubpixelCenterWithoutHoverShift() {
        assertEquals(
            new MapOverlayBounds(91.25f, 41.75f, 109.25f, 59.75f),
            MapOverlayBounds.structureIcon(100.25f, 50.75f)
        );
        assertEquals(
            new MapOverlayBounds(92.25f, 42.75f, 108.25f, 58.75f),
            MapOverlayBounds.portalIcon(100.25f, 50.75f, 16)
        );
    }

    @Test
    void textBoundsUseTheRenderedOriginAndDimensions() {
        assertEquals(
            new MapOverlayBounds(110f, 46f, 150f, 55f),
            MapOverlayBounds.text(110f, 46f, 40f, 9f)
        );
    }

    @Test
    void radarBoundsIgnoreNamesThatAreNotRendered() {
        final MapOverlayBounds hostile = MapOverlayBounds.radar(
            100f, 50f, 8f, 200f, 9f, RadarCategory.HOSTILE, true
        );
        final MapOverlayBounds playerWithNamesDisabled = MapOverlayBounds.radar(
            100f, 50f, 8f, 200f, 9f, RadarCategory.PLAYER, false
        );

        assertEquals(new MapOverlayBounds(88f, 38f, 112f, 62f), hostile);
        assertEquals(hostile, playerWithNamesDisabled);
    }

    @Test
    void radarBoundsUseTheWiderOfTheIconAndPlayerName() {
        assertEquals(
            new MapOverlayBounds(46f, 38f, 154f, 73f),
            MapOverlayBounds.radar(
                100f, 50f, 8f, 100f, 9f, RadarCategory.PLAYER, true
            )
        );
    }
}
