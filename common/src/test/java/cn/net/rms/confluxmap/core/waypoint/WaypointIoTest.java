package cn.net.rms.confluxmap.core.waypoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WaypointIoTest {
    private static final Logger LOGGER = LogManager.getLogger("WaypointIoTest");

    @Test
    void temporaryWaypointsNeverReachDisk(@TempDir final Path tempDir) throws IOException {
        final Path file = tempDir.resolve("waypoints.json");
        final Waypoint home = Waypoint.create(
            "Home", DimensionId.OVERWORLD, 1.0, 64.0, 2.0,
            0xFF336699, "", Waypoint.Type.NORMAL
        );
        final Waypoint stop = Waypoint.create(
            "Stop", DimensionId.OVERWORLD, 3.0, 64.0, 4.0,
            0xFF336699, "", Waypoint.Type.TEMPORARY
        );

        WaypointIo.save(file, List.of(home, stop), LOGGER);
        final WaypointStore.State loaded = WaypointIo.loadState(file, LOGGER);

        assertEquals(1, loaded.waypoints().size());
        assertEquals("Home", loaded.waypoints().get(0).name);
        assertFalse(Files.readString(file).contains("TEMPORARY"));
    }

    @Test
    void roundTripPreservesEmptyCustomSets(@TempDir final Path tempDir) throws IOException {
        final Path file = tempDir.resolve("waypoints.json");
        final Waypoint waypoint = Waypoint.create(
            "Home", DimensionId.OVERWORLD, 1.0, 64.0, 2.0,
            0xFF336699, "Bases", Waypoint.Type.NORMAL
        );
        waypoint.crossDimensionVisible = true;
        waypoint.iconItemId = "minecraft:diamond";
        waypoint.markerLabel = "\u5bb6\uD83D\uDE80";
        final WaypointStore.State state = new WaypointStore.State(
            List.of(new WaypointSet("Bases"), new WaypointSet("Empty")), List.of(waypoint)
        );

        WaypointIo.save(file, state, LOGGER);
        final WaypointStore.State loaded = WaypointIo.loadState(file, LOGGER);

        assertEquals(
            List.of(WaypointSet.DEFAULT, new WaypointSet("Bases"), new WaypointSet("Empty")),
            loaded.sets()
        );
        assertEquals(1, loaded.waypoints().size());
        assertEquals("Bases", loaded.waypoints().get(0).group);
        assertTrue(loaded.waypoints().get(0).crossDimensionVisible);
        assertEquals("minecraft:diamond", loaded.waypoints().get(0).iconItemId);
        assertEquals("\u5bb6\uD83D\uDE80", loaded.waypoints().get(0).markerLabel);
        assertTrue(Files.readString(file).contains("\"schemaVersion\": 2"));
    }

    @Test
    void roundTripsCollapsedStateIncludingTheDefaultSet(@TempDir final Path tempDir) throws IOException {
        final Path file = tempDir.resolve("collapsed.json");
        final Waypoint unassigned = Waypoint.create(
            "Home", DimensionId.OVERWORLD, 1.0, 64.0, 2.0,
            0xFF336699, "", Waypoint.Type.NORMAL
        );
        final Waypoint base = Waypoint.create(
            "Base", DimensionId.OVERWORLD, 3.0, 64.0, 4.0,
            0xFF336699, "Bases", Waypoint.Type.NORMAL
        );
        final Waypoint travel = Waypoint.create(
            "Trip", DimensionId.OVERWORLD, 5.0, 64.0, 6.0,
            0xFF336699, "Travel", Waypoint.Type.NORMAL
        );
        final WaypointStore.State state = new WaypointStore.State(
            List.of(new WaypointSet("", true), new WaypointSet("Bases", true), new WaypointSet("Travel")),
            List.of(unassigned, base, travel)
        );

        WaypointIo.save(file, state, LOGGER);
        final WaypointStore.State loaded = WaypointIo.loadState(file, LOGGER);

        assertEquals(
            List.of(new WaypointSet("", true), new WaypointSet("Bases", true), new WaypointSet("Travel")),
            loaded.sets()
        );
        // Only folded collections need the empty-name entry, so unfolded default sets
        // keep the pre-collapse on-disk shape.
        final String json = Files.readString(file);
        assertEquals(1, json.split("\"name\": \"\"", -1).length - 1);
    }

    @Test
    void roundTripsCrossDimensionYAndOmitsItWhenUnset(@TempDir final Path tempDir) throws IOException {
        final Path file = tempDir.resolve("cross-dimension-y.json");
        final Waypoint overridden = Waypoint.create(
            "Fortress gate", DimensionId.OVERWORLD, 1.0, 64.0, 2.0,
            0xFF336699, "Bases", Waypoint.Type.NORMAL
        );
        overridden.crossDimensionVisible = true;
        overridden.crossDimensionY = 120.0;
        final Waypoint plain = Waypoint.create(
            "Home", DimensionId.OVERWORLD, 3.0, 70.0, 4.0,
            0xFF336699, "Bases", Waypoint.Type.NORMAL
        );
        plain.crossDimensionVisible = true;

        WaypointIo.save(file, List.of(overridden, plain), LOGGER);
        final String json = Files.readString(file);
        // Exactly one occurrence: the unset override keeps the pre-override on-disk shape.
        assertEquals(1, json.split("\"crossDimensionY\"", -1).length - 1);
        final List<Waypoint> loaded = WaypointIo.load(file, LOGGER);

        assertEquals(120.0, loaded.get(0).crossDimensionY);
        assertNull(loaded.get(1).crossDimensionY);
    }

    @Test
    void nonFiniteCrossDimensionYFallsBackToTheStoredY(@TempDir final Path tempDir) throws IOException {
        final Path file = tempDir.resolve("nan-override.json");
        Files.writeString(file, "{\n"
            + "  \"schemaVersion\": 2,\n"
            + "  \"waypoints\": [{\n"
            + "    \"id\": \"00000000-0000-0000-0000-000000000001\",\n"
            + "    \"name\": \"Kept\",\n"
            + "    \"dimensionId\": \"minecraft:overworld\",\n"
            + "    \"x\": 1.0, \"y\": 70.0, \"z\": 2.0,\n"
            + "    \"colorArgb\": -13408615,\n"
            + "    \"visible\": true,\n"
            + "    \"crossDimensionVisible\": true,\n"
            + "    \"crossDimensionY\": NaN,\n"
            + "    \"type\": \"NORMAL\",\n"
            + "    \"createdAtEpochMs\": 2\n"
            + "  }]\n"
            + "}\n");

        final Waypoint waypoint = WaypointIo.load(file, LOGGER).get(0);

        assertEquals("Kept", waypoint.name);
        assertNull(waypoint.crossDimensionY);
    }

    @Test
    void newWaypointsDefaultToExactDimensionOnly() {
        final Waypoint waypoint = Waypoint.create(
            "Home", DimensionId.OVERWORLD, 1.0, 64.0, 2.0,
            0xFF336699, "Bases", Waypoint.Type.NORMAL
        );

        assertFalse(waypoint.crossDimensionVisible);
    }

    @Test
    void schemaOneGroupIsMigratedToASetWithoutDataLoss(@TempDir final Path tempDir) throws IOException {
        final Path file = tempDir.resolve("legacy.json");
        Files.writeString(file, "{\n"
            + "  \"schemaVersion\": 1,\n"
            + "  \"waypoints\": [{\n"
            + "    \"id\": \"00000000-0000-0000-0000-000000000001\",\n"
            + "    \"name\": \"Legacy\",\n"
            + "    \"dimensionId\": \"minecraft:the_nether\",\n"
            + "    \"x\": 12.5, \"y\": 70.0, \"z\": -8.0,\n"
            + "    \"colorArgb\": -13408615,\n"
            + "    \"group\": \"Old group\",\n"
            + "    \"visible\": true,\n"
            + "    \"type\": \"NORMAL\",\n"
            + "    \"createdAtEpochMs\": 1234\n"
            + "  }]\n"
            + "}\n");

        final WaypointStore.State loaded = WaypointIo.loadState(file, LOGGER);

        assertEquals(List.of(WaypointSet.DEFAULT, new WaypointSet("Old group")), loaded.sets());
        assertEquals(1, loaded.waypoints().size());
        final Waypoint waypoint = loaded.waypoints().get(0);
        assertEquals("Legacy", waypoint.name);
        assertEquals(DimensionId.NETHER, waypoint.dimensionId);
        assertEquals("Old group", waypoint.group);
        assertEquals(1234L, waypoint.createdAtEpochMs);
        assertFalse(waypoint.crossDimensionVisible);
        assertEquals("", waypoint.iconItemId);
        assertEquals("", waypoint.markerLabel);
    }

    @Test
    void nonFiniteCoordinatesAreDroppedInsteadOfCrashingTheEditScreenLater(
        @TempDir final Path tempDir
    ) throws IOException {
        final Path file = tempDir.resolve("nan.json");
        Files.writeString(file, "{\n"
            + "  \"schemaVersion\": 2,\n"
            + "  \"waypoints\": [{\n"
            + "    \"id\": \"00000000-0000-0000-0000-000000000001\",\n"
            + "    \"name\": \"Broken\",\n"
            + "    \"dimensionId\": \"minecraft:overworld\",\n"
            + "    \"x\": NaN, \"y\": 70.0, \"z\": Infinity,\n"
            + "    \"colorArgb\": -13408615,\n"
            + "    \"visible\": true,\n"
            + "    \"type\": \"NORMAL\",\n"
            + "    \"createdAtEpochMs\": 1\n"
            + "  }, {\n"
            + "    \"id\": \"00000000-0000-0000-0000-000000000002\",\n"
            + "    \"name\": \"Kept\",\n"
            + "    \"dimensionId\": \"minecraft:overworld\",\n"
            + "    \"x\": 1.0, \"y\": 70.0, \"z\": 2.0,\n"
            + "    \"colorArgb\": -13408615,\n"
            + "    \"visible\": true,\n"
            + "    \"type\": \"NORMAL\",\n"
            + "    \"createdAtEpochMs\": 2\n"
            + "  }]\n"
            + "}\n");

        final WaypointStore.State loaded = WaypointIo.loadState(file, LOGGER);

        assertEquals(1, loaded.waypoints().size());
        assertEquals("Kept", loaded.waypoints().get(0).name);
        assertTrue(loaded.persistenceWritable());
    }

    @Test
    void invalidMarkerStyleIsClearedWithoutDroppingTheWaypoint(
        @TempDir final Path tempDir
    ) throws IOException {
        final Path file = tempDir.resolve("invalid-style.json");
        Files.writeString(file, "{\n"
            + "  \"schemaVersion\": 2,\n"
            + "  \"waypoints\": [{\n"
            + "    \"id\": \"00000000-0000-0000-0000-000000000001\",\n"
            + "    \"name\": \"Kept\",\n"
            + "    \"dimensionId\": \"minecraft:overworld\",\n"
            + "    \"x\": 1.0, \"y\": 70.0, \"z\": 2.0,\n"
            + "    \"colorArgb\": -13408615,\n"
            + "    \"visible\": true,\n"
            + "    \"type\": \"NORMAL\",\n"
            + "    \"iconItemId\": \"example:wand\",\n"
            + "    \"markerLabel\": \"FOUR\",\n"
            + "    \"createdAtEpochMs\": 2\n"
            + "  }]\n"
            + "}\n");

        final Waypoint waypoint = WaypointIo.load(file, LOGGER).get(0);

        assertEquals("Kept", waypoint.name);
        assertEquals("", waypoint.iconItemId);
        assertEquals("", waypoint.markerLabel);
    }

    @Test
    void filesWithoutDescriptionFieldsLoadEmptyDescriptionAndImageId(@TempDir final Path tempDir)
        throws IOException {
        final Path file = tempDir.resolve("legacy-v2.json");
        Files.writeString(file, "{\n"
            + "  \"schemaVersion\": 2,\n"
            + "  \"waypoints\": [{\n"
            + "    \"id\": \"00000000-0000-0000-0000-000000000001\",\n"
            + "    \"name\": \"Kept\",\n"
            + "    \"dimensionId\": \"minecraft:overworld\",\n"
            + "    \"x\": 1.0, \"y\": 70.0, \"z\": 2.0,\n"
            + "    \"colorArgb\": -13408615,\n"
            + "    \"visible\": true,\n"
            + "    \"type\": \"NORMAL\",\n"
            + "    \"createdAtEpochMs\": 2\n"
            + "  }]\n"
            + "}\n");

        final Waypoint waypoint = WaypointIo.load(file, LOGGER).get(0);

        assertEquals("Kept", waypoint.name);
        assertEquals("", waypoint.description);
        assertEquals("", waypoint.imageId);
    }

    @Test
    void roundTripPreservesDescriptionAndImageIdWithoutBumpingTheSchema(@TempDir final Path tempDir)
        throws IOException {
        final Path file = tempDir.resolve("description.json");
        final Waypoint waypoint = Waypoint.create(
            "Base", DimensionId.OVERWORLD, 1.0, 64.0, 2.0,
            0xFF336699, "", Waypoint.Type.NORMAL
        );
        waypoint.description = "产物:\n钻石 ×3";
        waypoint.imageId = "0123ABCD-4567-789A-BCDE-F0123456789A";

        WaypointIo.save(file, List.of(waypoint), LOGGER);
        final Waypoint loaded = WaypointIo.load(file, LOGGER).get(0);

        assertEquals("产物:\n钻石 ×3", loaded.description);
        assertEquals("0123abcd-4567-789a-bcde-f0123456789a", loaded.imageId);
        assertTrue(Files.readString(file).contains("\"schemaVersion\": 2"));
    }

    @Test
    void invalidDescriptionOrImageIdIsClearedWithoutDroppingTheWaypoint(@TempDir final Path tempDir)
        throws IOException {
        final Path file = tempDir.resolve("invalid-notes.json");
        Files.writeString(file, "{\n"
            + "  \"schemaVersion\": 2,\n"
            + "  \"waypoints\": [{\n"
            + "    \"id\": \"00000000-0000-0000-0000-000000000001\",\n"
            + "    \"name\": \"Kept\",\n"
            + "    \"dimensionId\": \"minecraft:overworld\",\n"
            + "    \"x\": 1.0, \"y\": 70.0, \"z\": 2.0,\n"
            + "    \"colorArgb\": -13408615,\n"
            + "    \"visible\": true,\n"
            + "    \"type\": \"NORMAL\",\n"
            + "    \"description\": \"bad\\n§cstring\",\n"
            + "    \"imageId\": \"not-a-uuid\",\n"
            + "    \"createdAtEpochMs\": 2\n"
            + "  }]\n"
            + "}\n");

        final Waypoint waypoint = WaypointIo.load(file, LOGGER).get(0);

        assertEquals("Kept", waypoint.name);
        assertEquals("", waypoint.description);
        assertEquals("", waypoint.imageId);
    }

    @Test
    void futureSchemaIsNeverOverwrittenByLaterSave(@TempDir final Path tempDir) throws IOException {
        final Path file = tempDir.resolve("future.json");
        final String futureJson = "{\n"
            + "  \"schemaVersion\": 999999999999999999999999,\n"
            + "  \"sets\": {\"futureShape\": true},\n"
            + "  \"waypoints\": \"futureShape\"\n"
            + "}\n";
        Files.writeString(file, futureJson);

        final WaypointStore.State loaded = WaypointIo.loadState(file, LOGGER);
        final WaypointStore store = new WaypointStore(
            new WorldIdentity("server", "world"), loaded
        );
        store.add(Waypoint.create(
            "Must not persist", DimensionId.OVERWORLD, 0.0, 64.0, 0.0,
            0xFFFFFFFF, "", Waypoint.Type.NORMAL
        ));
        WaypointIo.save(file, store.state(), LOGGER);

        assertFalse(loaded.persistenceWritable());
        assertEquals(futureJson, Files.readString(file));
        assertTrue(Files.notExists(file.resolveSibling("future.json.bad")));
    }

    @Test
    void invalidSchemaVersionsAreQuarantinedBeforeCreatingWritableState(
        @TempDir final Path tempDir
    ) throws IOException {
        final List<String> invalidDocuments = List.of(
            "{\"waypoints\":[]}",
            "{\"schemaVersion\":0,\"waypoints\":[]}",
            "{\"schemaVersion\":1.5,\"waypoints\":[]}"
        );
        for (int i = 0; i < invalidDocuments.size(); i++) {
            final Path file = tempDir.resolve("invalid-schema-" + i + ".json");
            final String document = invalidDocuments.get(i);
            Files.writeString(file, document);

            final WaypointStore.State loaded = WaypointIo.loadState(file, LOGGER);

            assertTrue(loaded.persistenceWritable());
            assertTrue(loaded.waypoints().isEmpty());
            assertTrue(Files.notExists(file));
            assertEquals(document, Files.readString(file.resolveSibling(file.getFileName() + ".bad")));
        }
    }

    @Test
    void batchMoveRoundTripPreservesEverySetAssignment(@TempDir final Path tempDir) {
        final Path file = tempDir.resolve("batch-move.json");
        final Waypoint first = Waypoint.create(
            "First", DimensionId.OVERWORLD, 1.0, 64.0, 2.0,
            0xFF336699, WaypointSet.DEFAULT_NAME, Waypoint.Type.NORMAL
        );
        final Waypoint second = Waypoint.create(
            "Second", DimensionId.NETHER, 3.0, 70.0, 4.0,
            0xFF996633, WaypointSet.DEFAULT_NAME, Waypoint.Type.NORMAL
        );
        final WaypointStore store = new WaypointStore(
            new WorldIdentity("server", "world"), List.of(first, second)
        );
        assertEquals(WaypointStore.MutationResult.APPLIED, store.createSet("Travel"));
        assertEquals(
            new WaypointStore.BatchMoveResult(WaypointStore.MutationResult.APPLIED, 2),
            store.moveToSet(List.of(first.id, second.id), "Travel")
        );

        WaypointIo.save(file, store.state(), LOGGER);
        final WaypointStore.State loaded = WaypointIo.loadState(file, LOGGER);

        assertEquals(List.of(WaypointSet.DEFAULT, new WaypointSet("Travel")), loaded.sets());
        assertEquals(List.of("Travel", "Travel"), loaded.waypoints().stream()
            .map(waypoint -> waypoint.group)
            .toList());
    }
}
