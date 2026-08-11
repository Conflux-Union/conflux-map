package cn.net.rms.confluxmap.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class WebMapSnapshotTest {
    @Test
    void serializesPlayersAndPublicWaypoints() {
        final String json = new WebMapSnapshot(
            7,
            List.of(new WebPlayerSnapshot.Player("player", "A\"lice", 0, 1.5, 4.25, false)),
            List.of(new WebMapSnapshot.Waypoint(
                "waypoint", "Spawn", 0, 10.0, 64.0, -20.0, 0xff336699, "NORMAL"
            ))
        ).toJson();

        assertEquals(
            "{\"revision\":7,\"players\":[{\"id\":\"player\",\"name\":\"A\\\"lice\","
                + "\"dimension\":0,\"x\":1.5,\"z\":4.25,\"translucent\":false}],"
                + "\"waypoints\":[{\"id\":\"waypoint\",\"name\":\"Spawn\","
                + "\"dimension\":0,\"x\":10.0,\"y\":64.0,\"z\":-20.0,"
                + "\"colorArgb\":-13408615,\"type\":\"NORMAL\"}]}",
            json
        );
        assertTrue(json.contains("\"waypoints\":["));
    }

    @Test
    void advancesOnlyTheRevisionForStateThatActuallyChanged() {
        final WebPlayerSnapshot.Player firstPlayer = new WebPlayerSnapshot.Player(
            "player", "Alice", 0, 1.0, 2.0, false
        );
        final WebMapSnapshot.Waypoint waypoint = new WebMapSnapshot.Waypoint(
            "waypoint", "Spawn", 0, 10.0, 64.0, -20.0, 0xff336699, "NORMAL"
        );

        final WebMapSnapshot initial = WebMapSnapshot.EMPTY.next(
            List.of(firstPlayer), List.of(waypoint)
        );
        assertEquals(1L, initial.playerRevision());
        assertEquals(1L, initial.waypointRevision());
        assertSame(initial, initial.next(List.of(firstPlayer), List.of(waypoint)));

        final WebMapSnapshot moved = initial.next(
            List.of(new WebPlayerSnapshot.Player(
                "player", "Alice", 0, 3.0, 4.0, false
            )),
            List.of(waypoint)
        );
        assertEquals(2L, moved.playerRevision());
        assertEquals(1L, moved.waypointRevision());
    }
}
