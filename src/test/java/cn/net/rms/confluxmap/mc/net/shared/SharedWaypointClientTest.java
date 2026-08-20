package cn.net.rms.confluxmap.mc.net.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.net.shared.DeleteC2S;
import cn.net.rms.confluxmap.core.net.shared.UpdateC2S;
import cn.net.rms.confluxmap.core.shared.SharedWaypoint;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SharedWaypointClientTest {
    private static final UUID PUBLISHER = UUID.fromString("fedcba98-7654-3210-fedc-ba9876543210");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-000000000042");

    @Test
    void deleteAndUpdateUseTheTargetWaypointRevision() {
        final UUID operationId = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
        final SharedWaypoint waypoint = waypoint();
        final Waypoint edited = new Waypoint(
            waypoint.id(), "Changed", DimensionId.NETHER, 8d, 70d, 9d,
            0xFF3498DB, "", true, Waypoint.Type.NORMAL, waypoint.createdAtEpochMs()
        );

        final DeleteC2S delete = SharedWaypointClient.deleteMessage(operationId, waypoint);
        final UpdateC2S update = SharedWaypointClient.updateMessage(operationId, waypoint, edited);

        assertEquals(37L, delete.expectedRevision());
        assertEquals(37L, update.expectedRevision());
        assertEquals("Changed", update.name());
        assertEquals(DimensionId.NETHER, update.dimensionId());
    }

    @Test
    void managementIsAllowedForOperatorsAndEnabledOwners() {
        assertTrue(SharedWaypointClient.canManage(waypoint(), false, true, PUBLISHER));
        assertTrue(SharedWaypointClient.canManage(waypoint(), true, false, STRANGER));

        assertFalse(SharedWaypointClient.canManage(waypoint(), false, false, PUBLISHER));
        assertFalse(SharedWaypointClient.canManage(waypoint(), false, true, STRANGER));
        assertFalse(SharedWaypointClient.canManage(waypoint(), false, true, null));
        assertFalse(SharedWaypointClient.canManage(null, true, true, PUBLISHER));
    }

    private static SharedWaypoint waypoint() {
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
            1L,
            37L
        );
    }
}
