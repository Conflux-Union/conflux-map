package cn.net.rms.confluxmap.api;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compiles and exercises the public API exactly the way a third-party mod would: this
 * class deliberately imports nothing outside {@code cn.net.rms.confluxmap.api}, which
 * the compiler itself proves is sufficient to integrate.
 */
class PublicApiConsumerViewTest {
    @Test
    void instanceIsEmptyBeforeClientStartup() {
        assertTrue(ConfluxMapApi.instance().isEmpty());
    }

    @Test
    void pluginEntryInterfaceIsImplementable() {
        final UUID[] received = new UUID[1];
        final ConfluxMapPlugin plugin = api -> {
            received[0] = UUID.nameUUIDFromBytes(api.version().getBytes());
        };
        plugin.onConfluxMapInitialize(new StubApi());
        assertEquals(UUID.nameUUIDFromBytes("stub".getBytes()), received[0]);
    }

    @Test
    void waypointEditBuilderLeavesUnsetFieldsNull() {
        final WaypointApi.WaypointEdit edit = WaypointApi.WaypointEdit.builder()
            .name("Home")
            .position(1, 64, 2)
            .colorArgb(0xFF3498DB)
            .build();
        assertEquals("Home", edit.name());
        assertEquals(1, edit.x());
        assertEquals(64, edit.y());
        assertEquals(2, edit.z());
        assertEquals(0xFF3498DB, edit.colorArgb());
        assertEquals(null, edit.visible());
        assertEquals(null, edit.dimensionId());
        assertEquals(null, edit.group());
        assertEquals(null, edit.type());
    }

    @Test
    void requestPayloadsCarryIntegratorAnswers() {
        final ConfluxMapEvents.WaypointListOpening opening =
            new ConfluxMapEvents.WaypointListOpening(true, false);
        assertTrue(opening.openedFromHotkey());
        assertTrue(opening.requestedTab() == null);
        opening.requestTab(ConfluxMapEvents.WaypointListTab.PUBLIC);
        opening.requestAllDimensions();
        assertEquals(ConfluxMapEvents.WaypointListTab.PUBLIC, opening.requestedTab());
        assertTrue(opening.requestedAllDimensions());

        final ConfluxMapEvents.TeleportLandingResolve resolve =
            new ConfluxMapEvents.TeleportLandingResolve(1, 2, "minecraft:overworld", true);
        assertTrue(resolve.landingY().isEmpty());
        resolve.setLandingY(128);
        assertEquals(128, resolve.landingY().getAsInt());
        assertTrue(resolve.voidColumn());
        resolve.clearLandingY();
        assertTrue(resolve.landingY().isEmpty());
    }

    @Test
    void snapshotPayloadsDefensivelyCopyTheirLists() {
        final List<SharedWaypointApi.ApiSharedWaypoint> mutable = new ArrayList<>();
        final ConfluxMapEvents.SharedWaypointChange change = new ConfluxMapEvents.SharedWaypointChange(
            mutable
        );
        mutable.add(new SharedWaypointApi.ApiSharedWaypoint(
            UUID.randomUUID(), "Trirrin", "base", "minecraft:overworld",
            1, 64, 2, 0xFFF1C40F, WaypointApi.WaypointType.NORMAL, "", "", "", "", 0L
        ));
        assertTrue(change.waypoints().isEmpty());

        final ConfluxMapEvents.LocationActionEvent action = new ConfluxMapEvents.LocationActionEvent(
            ConfluxMapEvents.LocationAction.TELEPORT, 1, 2, "minecraft:overworld", false
        );
        assertEquals(ConfluxMapEvents.LocationAction.TELEPORT, action.action());
        assertEquals(1, action.blockX());
        assertEquals(2, action.blockZ());
        assertFalse(action.heightKnown());
    }

    /** A plugin-side implementation of the facade, to keep the api types honest. */
    private static final class StubApi implements ConfluxMapApi {
        @Override
        public String version() {
            return "stub";
        }

        @Override
        public ConfluxMapEvents events() {
            return null;
        }

        @Override
        public WaypointApi waypoints() {
            return null;
        }

        @Override
        public SharedWaypointApi sharedWaypoints() {
            return null;
        }

        @Override
        public MapDataApi mapData() {
            return null;
        }

        @Override
        public MarkerApi markers() {
            return null;
        }

        @Override
        public ActionApi actions() {
            return null;
        }
    }
}
