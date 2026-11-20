package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;

final class MapCoordinateJumpScreenTest {
    @Test
    void acceptsSignedBlockCoordinatesWithinTheWorld() {
        assertEquals(-1234, MapCoordinateJumpScreen.parseCoordinate(" -1234 "));
        assertEquals(0, MapCoordinateJumpScreen.parseCoordinate("0"));
        assertEquals(30_000_000, MapCoordinateJumpScreen.parseCoordinate("+30000000"));
        assertEquals(-30_000_000, MapCoordinateJumpScreen.parseCoordinate("-30000000"));
    }

    @Test
    void rejectsIncompleteFractionalAndOutOfRangeCoordinates() {
        for (final String value : new String[]{"", "-", "1.5", "-30000001", "30000001", "9999999999999999"}) {
            assertNull(MapCoordinateJumpScreen.parseCoordinate(value), value);
        }
    }
}
