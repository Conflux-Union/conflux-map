package cn.net.rms.confluxmap.mc.net.waypointimage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WaypointImageClientPublishTest {
    @Test
    void emptyImageIdNeverUploads() {
        assertFalse(WaypointImageClient.needsUpload("", ""));
        assertFalse(WaypointImageClient.needsUpload("", "existing"));
    }

    @Test
    void newOrReplacedImageIdUploads() {
        assertTrue(WaypointImageClient.needsUpload("a", ""));
        assertTrue(WaypointImageClient.needsUpload("a", null));
        assertTrue(WaypointImageClient.needsUpload("a", "b"));
    }

    @Test
    void unchangedImageIdSkipsUpload() {
        assertFalse(WaypointImageClient.needsUpload("a", "a"));
    }
}
