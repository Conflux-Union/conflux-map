package cn.net.rms.confluxmap.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class WebPlayerSnapshotTest {
    @Test
    void producesEscapedCompactJson() {
        final String json = new WebPlayerSnapshot(7, List.of(
            new WebPlayerSnapshot.Player("id", "a\"b", 2, -1.5, 4.25, true)
        )).toJson();

        assertEquals(
            "{\"revision\":7,\"players\":[{\"id\":\"id\",\"name\":\"a\\\"b\","
                + "\"dimension\":2,\"x\":-1.5,\"z\":4.25,\"translucent\":true}]}",
            json
        );
        assertTrue(json.contains("a\\\"b"));
        assertFalse(json.contains("a\"b"));
    }
}
