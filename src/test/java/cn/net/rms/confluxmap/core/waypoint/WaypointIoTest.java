package cn.net.rms.confluxmap.core.waypoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void roundTripPreservesEmptyCustomSets(@TempDir final Path tempDir) throws IOException {
        final Path file = tempDir.resolve("waypoints.json");
        final Waypoint waypoint = Waypoint.create(
            "Home", DimensionId.OVERWORLD, 1.0, 64.0, 2.0,
            0xFF336699, "Bases", Waypoint.Type.NORMAL
        );
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
        assertTrue(Files.readString(file).contains("\"schemaVersion\": 2"));
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
