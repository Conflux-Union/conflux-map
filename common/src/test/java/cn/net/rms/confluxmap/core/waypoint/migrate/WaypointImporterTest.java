package cn.net.rms.confluxmap.core.waypoint.migrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import cn.net.rms.confluxmap.core.waypoint.WaypointIo;
import cn.net.rms.confluxmap.core.waypoint.WaypointSet;
import cn.net.rms.confluxmap.core.waypoint.WaypointStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WaypointImporterTest {
    private static final WorldIdentity WORLD = new WorldIdentity("server", "world");
    private static final Logger LOGGER = LogManager.getLogger("WaypointImporterTest");

    @Test
    void importsWaypointsCreatingSetsWithOneNotification() {
        final WaypointStore store = new WaypointStore(WORLD, List.of());
        final AtomicInteger notifications = new AtomicInteger();
        store.addListener(ignored -> notifications.incrementAndGet());

        final WaypointImporter.Result result = WaypointImporter.importInto(store, List.of(
            imported("Home", DimensionId.OVERWORLD, 10, 64, -10, "Bases", true, Waypoint.Type.NORMAL),
            imported("Hidden", DimensionId.NETHER, 5, 40, 5, "", false, Waypoint.Type.NORMAL),
            imported("Died here", DimensionId.OVERWORLD, 1, 2, 3, "", true, Waypoint.Type.DEATH)
        ));

        assertEquals(new WaypointImporter.Result(3, 0), result);
        assertEquals(1, notifications.get());
        assertEquals(List.of(WaypointSet.DEFAULT, new WaypointSet("Bases")), store.sets());
        final List<Waypoint> stored = store.list();
        assertEquals("Home", stored.get(0).name);
        assertEquals("Bases", stored.get(0).group);
        assertTrue(stored.get(0).visible);
        assertFalse(stored.get(1).visible);
        assertEquals(DimensionId.NETHER, stored.get(1).dimensionId);
        assertEquals(Waypoint.Type.DEATH, stored.get(2).type);
    }

    @Test
    void duplicateBlockPositionsAreSkippedAgainstStoreAndWithinBatch() {
        final Waypoint existing = Waypoint.create(
            "Renamed later", DimensionId.OVERWORLD, 10.0, 64.0, -10.0, 0xFFFFFFFF, "", Waypoint.Type.NORMAL
        );
        final WaypointStore store = new WaypointStore(WORLD, List.of(existing));

        final WaypointImporter.Result result = WaypointImporter.importInto(store, List.of(
            // Same block as the existing waypoint even though the name differs.
            imported("Home", DimensionId.OVERWORLD, 10.9, 64.2, -10.0, "", true, Waypoint.Type.NORMAL),
            imported("Mine", DimensionId.OVERWORLD, 100, 12, 0, "", true, Waypoint.Type.NORMAL),
            // In-batch duplicate of the line above from the other mod's file.
            imported("Mine again", DimensionId.OVERWORLD, 100, 12, 0, "", true, Waypoint.Type.NORMAL),
            // Same coordinates in another dimension are a distinct location.
            imported("Nether mine", DimensionId.NETHER, 100, 12, 0, "", true, Waypoint.Type.NORMAL)
        ));

        assertEquals(new WaypointImporter.Result(2, 2), result);
        assertEquals(3, store.size());
    }

    @Test
    void unusableStoresImportNothing(@TempDir final Path tempDir) throws IOException {
        assertEquals(WaypointImporter.Result.EMPTY, WaypointImporter.importInto(null, List.of(
            imported("Home", DimensionId.OVERWORLD, 0, 0, 0, "", true, Waypoint.Type.NORMAL)
        )));

        // A future-schema file is the one real source of read-only stores.
        final Path file = tempDir.resolve("future.json");
        Files.writeString(file, "{\"schemaVersion\": 9999, \"sets\": [], \"waypoints\": []}");
        final WaypointStore readOnly = new WaypointStore(WORLD, WaypointIo.loadState(file, LOGGER));
        assertEquals(WaypointImporter.Result.EMPTY, WaypointImporter.importInto(readOnly, List.of(
            imported("Home", DimensionId.OVERWORLD, 0, 0, 0, "", true, Waypoint.Type.NORMAL)
        )));
        assertEquals(0, readOnly.size());
    }

    @Test
    void namesAndSetNamesAreNormalizedToLocalRules() {
        final WaypointStore store = new WaypointStore(WORLD, List.of());

        WaypointImporter.importInto(store, List.of(
            imported("    ", DimensionId.OVERWORLD, 0, 0, 0, "x".repeat(40), true, Waypoint.Type.NORMAL),
            imported("n".repeat(80), DimensionId.OVERWORLD, 1, 0, 0, " \t ", true, Waypoint.Type.NORMAL)
        ));

        final List<Waypoint> stored = store.list();
        assertEquals("Waypoint", stored.get(0).name);
        assertEquals("x".repeat(WaypointSet.MAX_NAME_LENGTH), stored.get(0).group);
        assertEquals(64, stored.get(1).name.length());
        assertEquals(WaypointSet.DEFAULT_NAME, stored.get(1).group);
    }

    private static ImportedWaypoint imported(
        final String name,
        final DimensionId dimension,
        final double x,
        final double y,
        final double z,
        final String setName,
        final boolean visible,
        final Waypoint.Type type
    ) {
        return new ImportedWaypoint(name, dimension, x, y, z, 0xFF8844CC, setName, visible, type);
    }
}
