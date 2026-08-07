package cn.net.rms.confluxmap.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
