package cn.net.rms.confluxmap.mc.net.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.net.shared.DeleteC2S;
import cn.net.rms.confluxmap.core.net.shared.LockC2S;
import cn.net.rms.confluxmap.core.shared.SharedWaypoint;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SharedWaypointClientTest {
    private static final UUID PUBLISHER = UUID.fromString("fedcba98-7654-3210-fedc-ba9876543210");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-000000000042");

    @Test
    void deleteAndLockUseTheTargetWaypointRevision() {
        final UUID operationId = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
        final SharedWaypoint waypoint = waypoint(false);

        final DeleteC2S delete = SharedWaypointClient.deleteMessage(operationId, waypoint);
        final LockC2S lock = SharedWaypointClient.lockMessage(operationId, waypoint, true);

        assertEquals(37L, delete.expectedRevision());
        assertEquals(37L, lock.expectedRevision());
    }

    @Test
    void deleteIsAllowedForOperatorsAndForPublishersOfUnlockedPoints() {
        assertTrue(SharedWaypointClient.canDelete(waypoint(false), false, PUBLISHER));
        assertTrue(SharedWaypointClient.canDelete(waypoint(false), true, STRANGER));
        assertTrue(SharedWaypointClient.canDelete(waypoint(true), true, STRANGER));

        assertFalse(SharedWaypointClient.canDelete(waypoint(false), false, STRANGER));
        assertFalse(SharedWaypointClient.canDelete(waypoint(true), false, PUBLISHER));
        assertFalse(SharedWaypointClient.canDelete(waypoint(false), false, null));
        assertFalse(SharedWaypointClient.canDelete(null, true, PUBLISHER));
    }

    private static SharedWaypoint waypoint(final boolean locked) {
        return new SharedWaypoint(
            UUID.fromString("12345678-1234-5678-9abc-def012345678"),
            PUBLISHER,
            "Player",
            "Spawn",
            DimensionId.OVERWORLD,
            1d,
            64d,
            2d,
            0xFFFFFFFF,
            Waypoint.Type.NORMAL,
            locked,
            1L,
            37L
        );
    }
}
