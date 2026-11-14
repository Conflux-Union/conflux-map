package cn.net.rms.confluxmap.mc.net.waypointimage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
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

    /**
     * The spec's anti-goal: no upload path outside the publish boundary. The
     * deleted encode-then-upload helper must stay deleted, not resurrect.
     */
    @Test
    void noEncodeUploadPathRemains() {
        for (final Method method : WaypointImageClient.class.getDeclaredMethods()) {
            assertNotEquals("encodeAndUpload", method.getName());
        }
    }
}
