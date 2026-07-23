package cn.net.rms.confluxmap.core.waypoint.migrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import java.util.List;
import org.junit.jupiter.api.Test;

class VoxelMapWaypointsParserTest {
    @Test
    void parsesWaypointLinesAndIgnoresHeaders() {
        final List<ImportedWaypoint> parsed = VoxelMapWaypointsParser.parse(List.of(
            "subworlds:",
            "oldNorthWorlds:",
            "seeds:all#123,",
            "name:Home,x:100,z:-200,y:70,enabled:true,red:1.0,green:0.5,blue:0.0,"
                + "suffix:,world:,dimensions:overworld#"
        ));

        assertEquals(1, parsed.size());
        final ImportedWaypoint waypoint = parsed.get(0);
        assertEquals("Home", waypoint.name());
        assertEquals(DimensionId.OVERWORLD, waypoint.dimensionId());
        assertEquals(100.0, waypoint.x());
        assertEquals(70.0, waypoint.y());
        assertEquals(-200.0, waypoint.z());
        assertEquals(0xFFFF8000, waypoint.colorArgb());
        assertTrue(waypoint.visible());
        assertEquals("", waypoint.setName());
        assertEquals(Waypoint.Type.NORMAL, waypoint.type());
    }

    @Test
    void multiDimensionWaypointsAreRescaledPerDimension() {
        final List<ImportedWaypoint> parsed = VoxelMapWaypointsParser.parse(List.of(
            "name:Portal,x:800,z:-1608,y:40,enabled:true,red:0.0,green:0.0,blue:1.0,"
                + "suffix:,world:,dimensions:the_nether#overworld#"
        ));

        assertEquals(2, parsed.size());
        assertEquals(DimensionId.NETHER, parsed.get(0).dimensionId());
        assertEquals(100.0, parsed.get(0).x());
        assertEquals(-201.0, parsed.get(0).z());
        assertEquals(40.0, parsed.get(0).y());
        assertEquals(DimensionId.OVERWORLD, parsed.get(1).dimensionId());
        assertEquals(800.0, parsed.get(1).x());
        assertEquals(-1608.0, parsed.get(1).z());
    }

    @Test
    void appliesDefaultsEscapesAndLegacyDimensionIds() {
        final List<ImportedWaypoint> parsed = VoxelMapWaypointsParser.parse(List.of(
            "name:Bare~comma~ and~colon~ more,x:10,z:20",
            "name:Legacy,x:1,z:2,y:5,enabled:true,dimensions:-1#",
            "name:Modded,x:3,z:4,y:6,enabled:true,dimensions:otherns:place#"
        ));

        assertEquals(3, parsed.size());
        final ImportedWaypoint bare = parsed.get(0);
        assertEquals("Bare, and: more", bare.name());
        assertEquals(DimensionId.OVERWORLD, bare.dimensionId());
        assertEquals(64.0, bare.y());
        assertFalse(bare.visible());
        assertEquals(0xFF800000, bare.colorArgb());
        assertEquals(DimensionId.NETHER, parsed.get(1).dimensionId());
        assertEquals(DimensionId.of("otherns", "place"), parsed.get(2).dimensionId());
    }

    @Test
    void deathPointsAreRecognizedByName() {
        final List<ImportedWaypoint> parsed = VoxelMapWaypointsParser.parse(List.of(
            "name:Latest Death,x:1,z:1,y:30,enabled:true,suffix:Skull",
            "name:Previous Death 3,x:2,z:2,y:31,enabled:true,suffix:Skull",
            "name:Deathly Manor,x:3,z:3,y:32,enabled:true"
        ));

        assertEquals(Waypoint.Type.DEATH, parsed.get(0).type());
        assertEquals(Waypoint.Type.DEATH, parsed.get(1).type());
        assertEquals(Waypoint.Type.NORMAL, parsed.get(2).type());
    }

    @Test
    void unusableLinesAreSkippedIndividually() {
        final List<ImportedWaypoint> parsed = VoxelMapWaypointsParser.parse(List.of(
            "name:,x:1,z:1",
            "name:BadNumber,x:abc,z:1",
            "no separators here",
            "name:Good,x:1,z:1,y:2,enabled:true"
        ));

        assertEquals(1, parsed.size());
        assertEquals("Good", parsed.get(0).name());
    }
}
