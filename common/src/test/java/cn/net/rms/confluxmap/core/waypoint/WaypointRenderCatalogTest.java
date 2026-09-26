package cn.net.rms.confluxmap.core.waypoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.shared.SharedWaypoint;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WaypointRenderCatalogTest {
    private static final Logger LOGGER = LogManager.getLogger("WaypointRenderCatalogTest");
    @Test
    void mergesVisibleLocalAndSharedEntriesWithoutLosingOwnership() {
        final Waypoint local = local("Home", true);
        local.iconItemId = "minecraft:diamond";
        local.markerLabel = "H";
        final Waypoint hidden = local("Hidden", false);
        final SharedWaypoint shared = new SharedWaypoint(
            UUID.randomUUID(), UUID.randomUUID(), "Publisher", "Village", DimensionId.NETHER,
            3.0, 70.0, 4.0, 0xFF3366CC, Waypoint.Type.NORMAL,
            "minecraft:emerald", "村", 20L, 1L
        );
        final SharedWaypoint spawn = sharedAt("Spawn", DimensionId.NETHER, 5.0, 71.0, 6.0);

        final List<WaypointRenderEntry> entries = WaypointRenderCatalog.merge(
            List.of(local, hidden), List.of(shared, spawn), true, true, id -> id.equals(shared.id())
        );

        assertEquals(3, entries.size());
        assertTrue(entries.get(0).local());
        assertTrue(entries.get(1).shared());
        assertTrue(entries.get(1).crossDimensionVisible());
        assertFalse(entries.get(2).crossDimensionVisible());
        assertTrue(entries.get(2).shared());
        assertEquals("minecraft:diamond", entries.get(0).iconItemId());
        assertEquals("H", entries.get(0).markerLabel());
        assertEquals("minecraft:emerald", entries.get(1).iconItemId());
        assertEquals("村", entries.get(1).markerLabel());
        assertThrows(UnsupportedOperationException.class, () -> entries.clear());
    }

    @Test
    void appliesLocalAndSharedMasterVisibilityIndependently() {
        final Waypoint local = local("Home", true);
        final SharedWaypoint shared = shared("Village");

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
    void filtersEachEntryByItsOwnCrossDimensionPreference() {
        final WaypointRenderEntry overworld = WaypointRenderCatalog.merge(
            List.of(local("Home", true)), List.of(), true, true
        ).get(0);
        final WaypointRenderEntry nether = WaypointRenderCatalog.merge(
            List.of(), List.of(shared("Fortress")), true, true
        ).get(0);

        final List<WaypointRenderEntry> entries = WaypointRenderCatalog.visibleFrom(
            List.of(overworld, nether), DimensionId.NETHER
        );

        assertEquals(List.of("Fortress"), entries.stream().map(WaypointRenderEntry::name).toList());
        assertEquals(3.0, entries.get(0).x());
        assertThrows(UnsupportedOperationException.class, () -> entries.clear());
    }

    @Test
    void convertsPortalLinkedEntriesWhenThatEntryAllowsCrossDimensionDisplay() {
        final Waypoint home = local("Home", true);
        home.crossDimensionVisible = true;
        final WaypointRenderEntry overworld = WaypointRenderCatalog.merge(
            List.of(home), List.of(), true, true
        ).get(0);
        final WaypointRenderEntry nether = WaypointRenderCatalog.merge(
            List.of(), List.of(shared("Fortress")), true, true
        ).get(0);

        final List<WaypointRenderEntry> entries = WaypointRenderCatalog.visibleFrom(
            List.of(overworld, nether), DimensionId.NETHER
        );

        assertEquals(List.of("Home", "Fortress"), entries.stream().map(WaypointRenderEntry::name).toList());
        // Overworld 1.0/2.0 seen from the Nether becomes 0.125/0.25; Y is never scaled.
        assertEquals(DimensionId.OVERWORLD, entries.get(0).dimensionId());
        assertEquals(0.125, entries.get(0).x());
        assertEquals(64.0, entries.get(0).y());
        assertEquals(0.25, entries.get(0).z());
        assertEquals(3.0, entries.get(1).x());
    }

    @Test
    void confinesEndEntriesToTheEndEvenWithCrossDimensionDisplayOn() {
        final WaypointRenderEntry end = new WaypointRenderEntry(
            UUID.randomUUID(), "Island", DimensionId.END, 100.0, 64.0, 200.0,
            0xFF3366CC, Waypoint.Type.NORMAL, WaypointRenderEntry.Source.LOCAL, true
        );

        assertTrue(WaypointRenderCatalog.visibleFrom(List.of(end), DimensionId.OVERWORLD).isEmpty());
        assertEquals(1, WaypointRenderCatalog.visibleFrom(List.of(end), DimensionId.END).size());
    }

    @Test
    void collapsesSharedCopyOntoLocalWaypointAtTheSameBlock() {
        // The publisher keeps the local original and also receives the server-side copy,
        // which arrives under a fresh id but at the same block.
        final SharedWaypoint echo = sharedAt("Home (shared)", DimensionId.OVERWORLD, 1.4, 64.9, 2.7);
        final SharedWaypoint elsewhere = sharedAt("Fortress", DimensionId.OVERWORLD, 3.0, 70.0, 4.0);

        final List<WaypointRenderEntry> entries = WaypointRenderCatalog.visibleFrom(
            WaypointRenderCatalog.merge(
                List.of(local("Home", true)), List.of(echo, elsewhere), true, true, id -> false
            ),
            DimensionId.OVERWORLD
        );

        assertEquals(List.of("Home", "Fortress"), entries.stream().map(WaypointRenderEntry::name).toList());
        assertTrue(entries.get(0).local());
    }

    @Test
    void keepsSharedCopyCrossDimensionWhenLocalAtSameBlockCannotRenderThere() {
        // The local original and the published copy keep separate cross-dimension switches
        // (the waypoint's own flag vs the per-id shared allowlist), so the block collapse
        // must run after the per-view dimension filter: collapsing the shared copy onto the
        // local entry before it hid the block from every dimension except the waypoint's
        // own, because the local entry was filtered out while the shared copy was already
        // dropped.
        final Waypoint home = local("Home", true);
        home.crossDimensionVisible = false;
        final SharedWaypoint echo = sharedAt("Home (shared)", DimensionId.OVERWORLD, 1.4, 64.9, 2.7);

        final List<WaypointRenderEntry> bothToggles = WaypointRenderCatalog.merge(
            List.of(home), List.of(echo), true, true, id -> id.equals(echo.id())
        );
        final List<WaypointRenderEntry> sharedOnly = WaypointRenderCatalog.merge(
            List.of(home), List.of(echo), false, true, id -> id.equals(echo.id())
        );

        final List<WaypointRenderEntry> fromNether = WaypointRenderCatalog.visibleFrom(
            bothToggles, DimensionId.NETHER
        );
        assertEquals(1, fromNether.size());
        assertTrue(fromNether.get(0).shared());
        // Nether-viewed coordinates are converted for the surviving shared copy.
        assertEquals(1.4 / 8.0, fromNether.get(0).x());
        assertEquals(1, WaypointRenderCatalog.visibleFrom(sharedOnly, DimensionId.NETHER).size());
        // In the waypoint's own dimension the local entry still wins the block.
        final List<WaypointRenderEntry> fromOverworld = WaypointRenderCatalog.visibleFrom(
            bothToggles, DimensionId.OVERWORLD
        );
        assertEquals(List.of("Home"), fromOverworld.stream().map(WaypointRenderEntry::name).toList());
        assertTrue(fromOverworld.get(0).local());
    }

    @Test
    void rendersSharedCopyWhenNoVisibleLocalWaypointOccupiesTheBlock() {
        final SharedWaypoint echo = sharedAt("Home (shared)", DimensionId.OVERWORLD, 1.9, 64.1, 2.2);

        final List<WaypointRenderEntry> perWaypointHidden = WaypointRenderCatalog.merge(
            List.of(local("Home", false)), List.of(echo), true, true, id -> false
        );
        final List<WaypointRenderEntry> masterHidden = WaypointRenderCatalog.merge(
            List.of(local("Home", true)), List.of(echo), false, true, id -> false
        );

        assertEquals(
            List.of("Home (shared)"),
            perWaypointHidden.stream().map(WaypointRenderEntry::name).toList()
        );
        assertTrue(perWaypointHidden.get(0).shared());
        assertEquals(
            List.of("Home (shared)"),
            masterHidden.stream().map(WaypointRenderEntry::name).toList()
        );
    }

    @Test
    void collapsesDuplicateSharedEntriesAtTheSameBlock() {
        final SharedWaypoint first = sharedAt("Tower", DimensionId.OVERWORLD, 8.0, 70.0, 9.0);
        final SharedWaypoint second = sharedAt("Tower (old)", DimensionId.OVERWORLD, 8.5, 70.5, 9.5);

        final List<WaypointRenderEntry> entries = WaypointRenderCatalog.visibleFrom(
            WaypointRenderCatalog.merge(List.of(), List.of(first, second), true, true, id -> false),
            DimensionId.OVERWORLD
        );

        assertEquals(List.of("Tower"), entries.stream().map(WaypointRenderEntry::name).toList());
    }

    @Test
    void mergesVisibleSiblingsWithTheirOriginLabel() {
        final Waypoint siblingPoint = local("Resource spawn", true);
        final Waypoint hiddenSibling = local("Hidden elsewhere", false);

        final List<WaypointRenderEntry> entries = WaypointRenderCatalog.merge(
            List.of(), List.of(),
            List.of(
                new SiblingWaypoint(siblingPoint, "资源服"),
                new SiblingWaypoint(hiddenSibling, "资源服")
            ),
            true, true, id -> false
        );

        assertEquals(1, entries.size());
        assertTrue(entries.get(0).sibling());
        assertEquals("资源服", entries.get(0).originWorldLabel());
    }

    @Test
    void collapsesSiblingCopyOntoLocalWaypointAtTheSameBlock() {
        final Waypoint siblingCopy = local("Home (sibling)", true);
        siblingCopy.x = 1.4;
        siblingCopy.z = 2.7;

        final List<WaypointRenderEntry> entries = WaypointRenderCatalog.visibleFrom(
            WaypointRenderCatalog.merge(
                List.of(local("Home", true)), List.of(),
                List.of(new SiblingWaypoint(siblingCopy, "Mirror")),
                true, true, id -> false
            ),
            DimensionId.OVERWORLD
        );

        assertEquals(List.of("Home"), entries.stream().map(WaypointRenderEntry::name).toList());
        assertTrue(entries.get(0).local());
    }

    @Test
    void convertsPortalLinkedSiblingsAndKeepsTheirOriginLabel() {
        final Waypoint siblingPoint = local("Nether side", true);
        siblingPoint.dimensionId = DimensionId.NETHER;
        siblingPoint.crossDimensionVisible = true;
        siblingPoint.x = 8.0;
        siblingPoint.z = 16.0;
        final WaypointRenderEntry entry = WaypointRenderCatalog.merge(
            List.of(), List.of(), List.of(new SiblingWaypoint(siblingPoint, "Mirror")), true, true, id -> false
        ).get(0);

        final List<WaypointRenderEntry> fromOverworld = WaypointRenderCatalog.visibleFrom(
            List.of(entry), DimensionId.OVERWORLD
        );

        assertEquals(1, fromOverworld.size());
        assertTrue(fromOverworld.get(0).sibling());
        assertEquals(64.0, fromOverworld.get(0).x());
        assertEquals(128.0, fromOverworld.get(0).z());
        assertEquals("Mirror", fromOverworld.get(0).originWorldLabel());
    }

    @Test
    void snapshotGatesSiblingsBehindBothToggles(@TempDir final Path tempDir) {
        final MapExecutors executors = new MapExecutors();
        try {
            final WaypointService local = new WaypointService(tempDir, executors, LOGGER);
            local.onSessionChanged(new SessionGuard.Session(
                1L, new WorldIdentity("play.example.net", "client-a"), DimensionId.OVERWORLD
            ));
            final Waypoint siblingPoint = local("Resource spawn", true);
            final ConfluxConfig config = new ConfluxConfig();
            final WaypointRenderCatalog catalog = new WaypointRenderCatalog(
                local, List::of, () -> List.of(new SiblingWaypoint(siblingPoint, "资源服")), config
            );

            assertEquals(0, catalog.snapshot().size());
            config.crossWorldWaypointsVisible = true;
            assertEquals(List.of("Resource spawn"), catalog.snapshot().stream()
                .map(WaypointRenderEntry::name).toList());
            assertTrue(catalog.snapshot().get(0).sibling());
            config.localWaypointsVisible = false;
            assertEquals(0, catalog.snapshot().size());
        } finally {
            executors.shutdown(1000L);
        }
    }

    private static Waypoint local(final String name, final boolean visible) {
        return new Waypoint(
            UUID.randomUUID(), name, DimensionId.OVERWORLD, 1.0, 64.0, 2.0,
            0xFF22AA44, "", visible, Waypoint.Type.NORMAL, 10L
        );
    }

    private static SharedWaypoint shared(final String name) {
        return sharedAt(name, DimensionId.NETHER, 3.0, 70.0, 4.0);
    }

    private static SharedWaypoint sharedAt(
        final String name,
        final DimensionId dimensionId,
        final double x,
        final double y,
        final double z
    ) {
        return new SharedWaypoint(
            UUID.randomUUID(), UUID.randomUUID(), "Publisher", name, dimensionId,
            x, y, z, 0xFF3366CC, Waypoint.Type.NORMAL, 20L, 1L
        );
    }
}
