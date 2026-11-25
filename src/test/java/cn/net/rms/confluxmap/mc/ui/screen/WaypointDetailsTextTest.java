package cn.net.rms.confluxmap.mc.ui.screen;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class WaypointDetailsTextTest {
    @Test void formatsQuantitiesWithoutRoundingAcrossUnitBoundaries() {
        assertEquals("0", WaypointDetailsText.compactAmount(0));
        assertEquals("999", WaypointDetailsText.compactAmount(999));
        assertEquals("1k", WaypointDetailsText.compactAmount(1_000));
        assertEquals("11.1k", WaypointDetailsText.compactAmount(11_111));
        assertEquals("999.9k", WaypointDetailsText.compactAmount(999_999));
        assertEquals("1m", WaypointDetailsText.compactAmount(1_000_000));
        assertEquals("1.2m", WaypointDetailsText.compactAmount(1_250_000));
        assertEquals("2147.4m", WaypointDetailsText.compactAmount(Integer.MAX_VALUE));
    }
}
