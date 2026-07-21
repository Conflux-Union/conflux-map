package cn.net.rms.confluxmap.core.waypoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.shared.SharedWaypoint;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class WaypointRenderCatalogTest {
    @Test
    void mergesVisibleLocalAndSharedEntriesWithoutLosingOwnership() {
        final Waypoint local = local("Home", true);
        final Waypoint hidden = local("Hidden", false);
        final SharedWaypoint shared = shared("Village", false);
        final SharedWaypoint locked = shared("Spawn", true);

        final List<WaypointRenderEntry> entries = WaypointRenderCatalog.merge(
            List.of(local, hidden), List.of(shared, locked), true, true
        );

        assertEquals(3, entries.size());
        assertTrue(entries.get(0).local());
        assertFalse(entries.get(0).locked());
        assertTrue(entries.get(1).shared());
        assertFalse(entries.get(1).locked());
        assertTrue(entries.get(2).shared());
        assertTrue(entries.get(2).locked());
        assertThrows(UnsupportedOperationException.class, () -> entries.clear());
    }

    @Test
    void appliesLocalAndSharedMasterVisibilityIndependently() {
        final Waypoint local = local("Home", true);
        final SharedWaypoint shared = shared("Village", false);

        final List<WaypointRenderEntry> localOnly = WaypointRenderCatalog.merge(
            List.of(local), List.of(shared), true, false
        );
        final List<WaypointRenderEntry> sharedOnly = WaypointRenderCatalog.merge(
            List.of(local), List.of(shared), false, true
        );

        assertEquals(List.of("Home"), localOnly.stream().map(WaypointRenderEntry::name).toList());
        assertEquals(List.of("Village"), sharedOnly.stream().map(WaypointRenderEntry::name).toList());
    }

    @Test
    void capturesValuesInsteadOfRetainingMutableLocalWaypoint() {
        final Waypoint local = local("Before", true);
        final WaypointRenderEntry entry = WaypointRenderCatalog.merge(List.of(local), List.of(), true, true).get(0);

        local.name = "After";
        local.x = 99.0;

        assertEquals("Before", entry.name());
        assertEquals(1.0, entry.x());
    }

    @Test
    void filtersRenderEntriesToTheExactDimensionWithoutPortalConversion() {
        final WaypointRenderEntry overworld = WaypointRenderCatalog.merge(
            List.of(local("Home", true)), List.of(), true, true
        ).get(0);
        final WaypointRenderEntry nether = WaypointRenderCatalog.merge(
            List.of(), List.of(shared("Fortress", false)), true, true
        ).get(0);

        final List<WaypointRenderEntry> entries = WaypointRenderCatalog.inDimension(
            List.of(overworld, nether), DimensionId.NETHER
        );

        assertEquals(List.of("Fortress"), entries.stream().map(WaypointRenderEntry::name).toList());
        assertEquals(3.0, entries.get(0).x());
        assertThrows(UnsupportedOperationException.class, () -> entries.clear());
    }

    private static Waypoint local(final String name, final boolean visible) {
        return new Waypoint(
            UUID.randomUUID(), name, DimensionId.OVERWORLD, 1.0, 64.0, 2.0,
            0xFF22AA44, "", visible, Waypoint.Type.NORMAL, 10L
        );
    }

    private static SharedWaypoint shared(final String name, final boolean locked) {
        return new SharedWaypoint(
            UUID.randomUUID(), UUID.randomUUID(), "Publisher", name, DimensionId.NETHER,
            3.0, 70.0, 4.0, 0xFF3366CC, Waypoint.Type.NORMAL, locked, 20L, 1L
        );
    }
}
