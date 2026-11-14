package cn.net.rms.confluxmap.mc.net.waypointimage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import cn.net.rms.confluxmap.core.waypoint.WaypointStore;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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

    @Test
    void missingOrStaleStoreSkipsSweep() {
        final WorldIdentity active = new WorldIdentity("server", "live");
        assertNull(WaypointImageClient.referencedIdsForSweep(null, active));
        final WaypointStore stale = storeFor(new WorldIdentity("server", "other"));
        assertNull(WaypointImageClient.referencedIdsForSweep(stale, active));
    }

    @Test
    void currentStoreCollectsNonEmptyImageIds() {
        final WorldIdentity world = new WorldIdentity("server", "live");
        final WaypointStore store = storeFor(
            world, waypointWithImage("a"), waypointWithImage(""), waypointWithImage("b")
        );
        assertEquals(Set.of("a", "b"), WaypointImageClient.referencedIdsForSweep(store, world));
    }

    private static WaypointStore storeFor(final WorldIdentity world, final Waypoint... waypoints) {
        return new WaypointStore(world, List.of(waypoints));
    }

    private static Waypoint waypointWithImage(final String imageId) {
        final Waypoint waypoint = new Waypoint(
            UUID.randomUUID(), "wp", DimensionId.OVERWORLD, 0, 64, 0, 0xFFFFFF, "group",
            true, false, Waypoint.Type.NORMAL, 0L
        );
        waypoint.imageId = imageId;
        return waypoint;
    }
}
