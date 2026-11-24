package cn.net.rms.confluxmap.core.waypoint.library;

import static org.junit.jupiter.api.Assertions.*;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WaypointLibraryTest {
    @TempDir Path temp;

    @Test
    void metadataIsImmutableNormalizedAndSearchable() {
        final Set<String> input = new LinkedHashSet<>(List.of(" Base ", "", "基地"));
        final WaypointMetadata extra = new WaypointMetadata("Main Storage", input, true, Set.of());
        input.clear();
        assertEquals(Set.of("base"), extra.tags());
        assertTrue(extra.matches(" STORAGE "));
        assertTrue(extra.matches("base"));
        assertTrue(extra.matches("基地"));
        assertFalse(extra.matches("village"));
        assertThrows(UnsupportedOperationException.class, () -> extra.tags().add("modified"));
        assertThrows(IllegalArgumentException.class, () -> new WaypointMetadata("x".repeat(4097), Set.of(), false, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new WaypointMetadata("", Set.of("x".repeat(65)), false, Set.of()));
    }

    @Test
    void deletingACollectionPreservesMetadataAndOtherMemberships() {
        final WaypointLibrary library = new WaypointLibrary();
        final WaypointCollection first = collection("First");
        final WaypointCollection second = collection("Second");
        library.putCollection(first);
        library.putCollection(second);
        final UUID waypoint = UUID.randomUUID();
        library.put(waypoint, new WaypointMetadata("keep", Set.of("tag"), true, Set.of(first.id(), second.id())));
        library.deleteCollection(first.id());
        assertEquals(new WaypointMetadata("keep", Set.of("tag"), true, Set.of(second.id())), library.get(waypoint));
        assertEquals(List.of(second), library.collections());
        assertThrows(IllegalArgumentException.class, () -> library.putCollection(collection("second")));
        assertThrows(IllegalArgumentException.class, () -> library.put(waypoint,
            new WaypointMetadata("", Set.of(), false, Set.of(first.id()))));
    }

    @Test
    void collectionOrderingAndRenamePreserveIdsAndMemberships() {
        final WaypointLibrary library = new WaypointLibrary();
        final WaypointCollection first = collection("First");
        final WaypointCollection second = collection("Second");
        library.putCollection(first);
        library.putCollection(second);
        library.moveCollection(second.id(), -1);
        assertEquals(List.of(second, first), library.collections());
        library.moveCollection(second.id(), -1);
        assertEquals(List.of(second, first), library.collections());
        final WaypointCollection renamed = new WaypointCollection(second.id(), "Renamed", 0xFF123456, "B");
        library.putCollection(renamed);
        assertEquals(List.of(renamed, first), library.collections());
    }

    @Test
    void worldSwitchAndRestartPreservePrivateDataAndKeepWorldsIsolated() {
        final MapExecutors executors = new MapExecutors(1);
        try {
            final WaypointLibraryService service = service(executors);
            final SessionGuard.Session first = session(1, "first");
            final SessionGuard.Session second = session(2, "second");
            final UUID id = UUID.randomUUID();
            final WaypointCollection collection = collection("Home");
            service.onSessionChanged(first);
            service.library().putCollection(collection);
            final WaypointMetadata extra = new WaypointMetadata("Private note", Set.of("基地"), true, Set.of(collection.id()));
            service.library().put(id, extra);
            service.save();
            service.onSessionChanged(second);
            assertEquals(WaypointMetadata.EMPTY, service.library().get(id));
            assertTrue(service.library().collections().isEmpty());
            final WaypointLibraryService restarted = service(executors);
            restarted.onSessionChanged(first);
            assertEquals(extra, restarted.library().get(id));
            assertEquals(List.of(collection), restarted.library().collections());
            final Waypoint renamed = new Waypoint(id, "New name", DimensionId.OVERWORLD,
                1, 64, 2, 0xFFFFFFFF, "", true, false, Waypoint.Type.NORMAL, 0);
            service.onSessionChanged(first);
            service.onWaypointsChanged(List.of(renamed));
            assertEquals(extra, service.library().get(id));
            service.onWaypointsChanged(List.of());
            assertEquals(WaypointMetadata.EMPTY, service.library().get(id));
            service.flush();
            restarted.onSessionChanged(second);
            restarted.onSessionChanged(first);
            assertEquals(WaypointMetadata.EMPTY, restarted.library().get(id));
            assertEquals(List.of(collection), restarted.library().collections());
        } finally {
            executors.shutdown(1000);
        }
    }

    @Test
    void unreadableAndFutureSchemasAreNeverOverwritten() throws Exception {
        for (final String input : List.of("{broken", "{\"schemaVersion\":2,\"waypoints\":{},\"collections\":[]}")) {
            final Path file = temp.resolve("server/first.json");
            Files.createDirectories(file.getParent());
            Files.writeString(file, input);
            final MapExecutors executors = new MapExecutors(1);
            try {
                final WaypointLibraryService service = service(executors);
                service.onSessionChanged(session(1, "first"));
                assertFalse(service.writable());
                service.save();
                service.flush();
                assertEquals(input, Files.readString(file));
            } finally {
                executors.shutdown(1000);
            }
        }
    }

    @Test
    void failedWritesDisableMutationWithoutDestroyingExistingFiles() throws Exception {
        final Path blocked = temp.resolve("not-a-directory");
        Files.writeString(blocked, "preserve");
        final MapExecutors executors = new MapExecutors(1);
        try {
            final WaypointLibraryService service = new WaypointLibraryService(blocked, executors, LogManager.getLogger());
            service.onSessionChanged(session(1, "first"));
            service.save();
            service.flush();
            assertFalse(service.writable());
            assertEquals("preserve", Files.readString(blocked));
        } finally {
            executors.shutdown(1000);
        }
    }

    @Test
    void localAndSharedStarsWithTheSameUuidAreIndependentAndSurviveRestart() {
        final MapExecutors executors = new MapExecutors(1);
        try {
            final WaypointLibraryService service = service(executors);
            service.onSessionChanged(session(1, "first"));
            final UUID id = UUID.randomUUID();
            service.library().put(id, new WaypointMetadata("keep", Set.of("tag"), false, Set.of()));
            service.library().setStarred(id, true, true);
            assertFalse(service.library().isStarred(id, false));
            assertTrue(service.library().isStarred(id, true));
            service.library().setStarred(id, false, true);
            assertEquals("keep", service.library().get(id).note());
            assertEquals(Set.of("tag"), service.library().get(id).tags());
            service.library().setStarred(id, true, false);
            assertTrue(service.library().isStarred(id, false));
            assertFalse(service.library().isStarred(id, true));
            service.library().setStarred(id, true, true);
            service.save();
            service.onSessionChanged(session(2, "second"));
            assertFalse(service.library().isStarred(id, false));
            assertFalse(service.library().isStarred(id, true));
            final WaypointLibraryService restarted = service(executors);
            restarted.onSessionChanged(session(3, "first"));
            assertTrue(restarted.library().isStarred(id, false));
            assertTrue(restarted.library().isStarred(id, true));
            restarted.onWaypointsChanged(List.of());
            assertFalse(restarted.library().isStarred(id, false));
            assertTrue(restarted.library().isStarred(id, true));
            restarted.save();
            restarted.flush();
        } finally {
            executors.shutdown(1000);
        }
    }

    @Test
    void resourcesKeepTheirParentAndSurviveStarsCollectionsAndRestart() {
        final MapExecutors executors = new MapExecutors(1);
        try {
            final WaypointLibraryService service = service(executors);
            service.onSessionChanged(session(1, "first"));
            final UUID id = UUID.randomUUID();
            final WaypointCollection collection = collection("Resources");
            service.library().putCollection(collection);
            final Set<String> resources = Set.of("minecraft:iron_ingot", "examplemod:steel_ingot");
            final WaypointMetadata metadata = new WaypointMetadata("My iron farm", Set.of("building"), false,
                Set.of(collection.id()), resources, java.util.Map.of("minecraft:iron_ingot", 256));
            assertTrue(metadata.tags().contains("resource"));
            assertTrue(metadata.matches("examplemod:steel"));
            assertTrue(metadata.matches("资源"));
            service.library().put(id, metadata);
            service.library().setStarred(id, false, true);
            service.library().deleteCollection(collection.id());
            assertEquals(resources, service.library().get(id).resourceItems());
            assertTrue(service.library().get(id).collections().isEmpty());
            service.library().put(id, true, new WaypointMetadata("Private shared note", Set.of("resource"), true,
                Set.of(), Set.of("examplemod:steel_ingot"), java.util.Map.of("examplemod:steel_ingot", 0)));
            service.save();
            service.flush();
            final WaypointLibraryService restarted = service(executors);
            restarted.onSessionChanged(session(2, "first"));
            assertEquals(resources, restarted.library().get(id).resourceItems());
            assertEquals(java.util.Map.of("minecraft:iron_ingot", 256), restarted.library().get(id).resourceAmounts());
            assertTrue(restarted.library().get(id).starred());
            assertEquals("Private shared note", restarted.library().get(id, true).note());
            restarted.library().setStarred(id, true, false);
            assertEquals(Set.of("examplemod:steel_ingot"), restarted.library().get(id, true).resourceItems());
            assertFalse(restarted.library().get(id, true).starred());
            assertEquals(java.util.Map.of("examplemod:steel_ingot", 0), restarted.library().get(id, true).resourceAmounts());
        } finally {
            executors.shutdown(1000);
        }
    }

    @Test
    void legacyResourceItemsKeepUnknownQuantities() throws Exception {
        final UUID id = UUID.randomUUID();
        final Path file = temp.resolve("server/first.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
            {"schemaVersion":1,"waypoints":{"%s":{"resourceItems":["examplemod:steel_ingot"]}},"collections":[]}
            """.formatted(id));
        final MapExecutors executors = new MapExecutors(1);
        try {
            final WaypointLibraryService service = service(executors);
            service.onSessionChanged(session(1, "first"));
            final WaypointMetadata legacy = service.library().get(id);
            assertTrue(service.writable());
            assertEquals(Set.of("examplemod:steel_ingot"), legacy.resourceItems());
            assertTrue(legacy.resourceAmounts().isEmpty());
            assertTrue(legacy.tags().contains("resource"));
        } finally {
            executors.shutdown(1000);
        }
    }

    @Test
    void legacyPresetLabelsNormalizeAndInvalidResourceIdsAreRejected() {
        final WaypointMetadata legacy = new WaypointMetadata(null, Set.of("资源", "基地", "custom"), false, null);
        assertEquals(Set.of("resource", "base", "custom"), legacy.tags());
        assertTrue(legacy.resourceItems().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new WaypointMetadata("", Set.of(), false, Set.of(), Set.of("../file")));
        assertThrows(IllegalArgumentException.class, () -> new WaypointMetadata("", Set.of(), false, Set.of(), Set.of("mod:Bad Id")));
    }

    private WaypointLibraryService service(final MapExecutors executors) {
        return new WaypointLibraryService(temp, executors, LogManager.getLogger());
    }

    private static SessionGuard.Session session(final long token, final String world) {
        return new SessionGuard.Session(token, new WorldIdentity("server", world), DimensionId.OVERWORLD);
    }

    private static WaypointCollection collection(final String name) {
        return new WaypointCollection(UUID.randomUUID(), name, 0xFF3498DB, "*");
    }
}
