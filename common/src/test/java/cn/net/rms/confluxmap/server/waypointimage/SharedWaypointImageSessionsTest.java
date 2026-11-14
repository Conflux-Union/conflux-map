package cn.net.rms.confluxmap.server.waypointimage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.server.ServerConfig;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SharedWaypointImageSessionsTest {
    private static final UUID PLAYER = UUID.fromString("0123abcd-4567-789a-bcde-f0123456789a");

    @Test
    void uploadsAreBurstTenThenRateLimitedUntilTheMinutePasses() {
        final SharedWaypointImageSessions sessions = new SharedWaypointImageSessions(10);
        for (int i = 0; i < 10; i++) {
            assertTrue(sessions.tryUpload(PLAYER, 0L), "upload " + i + " should pass");
        }
        assertFalse(sessions.tryUpload(PLAYER, 0L));
        // One minute later the bucket has fully refilled.
        assertTrue(sessions.tryUpload(PLAYER, 60_000L));
        // Another player is unaffected.
        assertTrue(sessions.tryUpload(UUID.randomUUID(), 0L));
    }

    @Test
    void fetchesHaveTheirOwnIndependentBucket() {
        final SharedWaypointImageSessions sessions = new SharedWaypointImageSessions(10);
        for (int i = 0; i < 60; i++) {
            assertTrue(sessions.tryFetch(PLAYER, 0L), "fetch " + i + " should pass");
        }
        assertFalse(sessions.tryFetch(PLAYER, 0L));
        // Uploads were not touched by the fetch drain.
        assertTrue(sessions.tryUpload(PLAYER, 0L));
    }

    @Test
    void fetchesRefillAtSixtyPerMinute() {
        final SharedWaypointImageSessions sessions = new SharedWaypointImageSessions(10);
        for (int i = 0; i < 60; i++) {
            sessions.tryFetch(PLAYER, 0L);
        }
        assertFalse(sessions.tryFetch(PLAYER, 0L));
        // Refill runs at one token per second; nothing else accrues mid-second.
        assertTrue(sessions.tryFetch(PLAYER, 1_000L));
        assertFalse(sessions.tryFetch(PLAYER, 1_500L));
    }

    @Test
    void serverConfigClampsTheImageQuotaFields() {
        final ServerConfig config = new ServerConfig();
        assertEquals(2 * 1024 * 1024, config.maxWaypointImageBytesPerWorld);
        assertEquals(512 * 1024, config.maxWaypointImageBytesPerPlayer);
        assertEquals(10, config.waypointImageUploadsPerMinute);

        config.maxWaypointImageBytesPerWorld = -1;
        config.maxWaypointImageBytesPerPlayer = 999_999_999;
        config.waypointImageUploadsPerMinute = 0;
        config.normalize();
        assertEquals(64 * 1024, config.maxWaypointImageBytesPerWorld);
        assertEquals(config.maxWaypointImageBytesPerWorld, config.maxWaypointImageBytesPerPlayer);
        assertEquals(1, config.waypointImageUploadsPerMinute);
    }
}
