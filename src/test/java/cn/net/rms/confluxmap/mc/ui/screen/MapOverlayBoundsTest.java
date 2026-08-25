package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.confluxmap.core.radar.RadarCategory;
import org.junit.jupiter.api.Test;

final class MapOverlayBoundsTest {
    @Test
    void hoveredStructureBoundsMatchTheRenderedIcon() {
        assertEquals(
            new MapOverlayBounds(90f, 40f, 110f, 60f),
            MapOverlayBounds.structureIcon(100f, 50f, true)
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
