package cn.net.rms.confluxmap.core.waypoint;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WaypointVerticalRelationTest {
    @Test
    void classifiesWaypointHeightAgainstThePlayersBlock() {
        assertEquals(WaypointVerticalRelation.ABOVE, WaypointVerticalRelation.between(66, 64.9));
        assertEquals(WaypointVerticalRelation.LEVEL, WaypointVerticalRelation.between(65, 64.9));
        assertEquals(WaypointVerticalRelation.LEVEL, WaypointVerticalRelation.between(63, 64.9));
        assertEquals(WaypointVerticalRelation.BELOW, WaypointVerticalRelation.between(62, 64.9));
    }
}
