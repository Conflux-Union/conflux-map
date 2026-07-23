package cn.net.rms.confluxmap.core.waypoint.migrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import java.util.List;
import org.junit.jupiter.api.Test;

class XaeroWaypointsParserTest {
    @Test
    void parsesRealWorldLineShape() {
        final List<ImportedWaypoint> parsed = XaeroWaypointsParser.parse(List.of(
            "#",
            "#waypoint:name:initials:x:y:z:color:disabled:type:set:rotate_on_tp:tp_yaw:visibility_type:destination",
            "#",
            "waypoint:123:1:18:92:793:14:false:0:gui.xaero_default:false:0:0:false"
        ), DimensionId.OVERWORLD);

        assertEquals(1, parsed.size());
        final ImportedWaypoint waypoint = parsed.get(0);
        assertEquals("123", waypoint.name());
        assertEquals(DimensionId.OVERWORLD, waypoint.dimensionId());
        assertEquals(18.0, waypoint.x());
        assertEquals(92.0, waypoint.y());
        assertEquals(793.0, waypoint.z());
        assertEquals(0xFFFFFF55, waypoint.colorArgb());
        assertEquals("", waypoint.setName());
        assertTrue(waypoint.visible());
        assertEquals(Waypoint.Type.NORMAL, waypoint.type());
    }

    @Test
    void decodesEscapesUnknownHeightDisabledFlagAndColorNormalization() {
        final List<ImportedWaypoint> parsed = XaeroWaypointsParser.parse(List.of(
            "sets:gui.xaero_default:My§§Set",
            "waypoint:Base§§2:B:1:~:2:20:true:0:My§§Set:false:0:0:false",
            "waypoint:Negative:N:3:10:4:-5:false:0:gui.xaero_default"
        ), DimensionId.NETHER);

        assertEquals(2, parsed.size());
        assertEquals("Base:2", parsed.get(0).name());
        assertEquals(64.0, parsed.get(0).y());
        assertEquals(0xFFAA0000, parsed.get(0).colorArgb());
        assertEquals("My:Set", parsed.get(0).setName());
        assertFalse(parsed.get(0).visible());
        // A ten-token line without the optional trailing fields still parses (old files).
        assertEquals("Negative", parsed.get(1).name());
        assertEquals(0xFF000000, parsed.get(1).colorArgb());
    }

    @Test
    void deathTypesMapToDeathWaypointsWithReadableNames() {
        final List<ImportedWaypoint> parsed = XaeroWaypointsParser.parse(List.of(
            "waypoint:gui.xaero_deathpoint:D:1:60:1:0:false:1:gui.xaero_default:false:0:1:true",
            "waypoint:gui.xaero_deathpoint_old:D:2:61:2:0:false:2:gui.xaero_default:false:0:1:true"
        ), DimensionId.OVERWORLD);

        assertEquals("Latest Death", parsed.get(0).name());
        assertEquals(Waypoint.Type.DEATH, parsed.get(0).type());
        assertEquals("Old Death", parsed.get(1).name());
        assertEquals(Waypoint.Type.DEATH, parsed.get(1).type());
    }

    @Test
    void malformedAndForeignLinesAreSkippedIndividually() {
        final List<ImportedWaypoint> parsed = XaeroWaypointsParser.parse(List.of(
            "",
            "server_waypoint:key:true",
            "waypoint:TooShort:T:1:2:3:0:false:0",
            "waypoint:BadNumber:B:notanint:2:3:0:false:0:gui.xaero_default",
            "waypoint:Good:G:1:2:3:0:false:0:gui.xaero_default"
        ), DimensionId.OVERWORLD);

        assertEquals(1, parsed.size());
        assertEquals("Good", parsed.get(0).name());
    }
}
