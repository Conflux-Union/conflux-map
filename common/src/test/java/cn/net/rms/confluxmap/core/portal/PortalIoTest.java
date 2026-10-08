package cn.net.rms.confluxmap.core.portal;

import cn.net.rms.confluxmap.core.model.DimensionId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalIoTest {
    private static final Logger LOGGER = LogManager.getLogger(PortalIoTest.class);

    @TempDir
    Path tempDir;

    @Test
    void roundTripsMarkersPerDimension() throws IOException {
        final Path file = tempDir.resolve("portals.json");
        final Map<DimensionId, List<PortalMarker>> state = new LinkedHashMap<>();
        state.put(DimensionId.OVERWORLD, List.of(
            new PortalMarker(DimensionId.OVERWORLD, 3, -4, PortalKind.NETHER_PORTAL, 55, -60),
            new PortalMarker(DimensionId.OVERWORLD, 3, -4, PortalKind.END_PORTAL, 58, -62)
        ));
        state.put(DimensionId.END, List.of(
            new PortalMarker(DimensionId.END, 10, 10, PortalKind.END_GATEWAY, 160, 160)
        ));
        PortalIo.saveChecked(file, state);

        final Map<DimensionId, List<PortalMarker>> loaded = PortalIo.load(file, LOGGER);
        assertEquals(state, loaded);
    }

    @Test
    void missingFileLoadsEmpty() {
        assertTrue(PortalIo.load(tempDir.resolve("absent.json"), LOGGER).isEmpty());
    }

    @Test
    void corruptFileIsQuarantinedAndLoadsEmpty() throws IOException {
        final Path file = tempDir.resolve("portals.json");
        Files.writeString(file, "not json at all");
        assertTrue(PortalIo.load(file, LOGGER).isEmpty());
        assertFalse(Files.exists(file));
        assertTrue(Files.exists(tempDir.resolve("portals.json.bad")));
    }

    @Test
    void unknownKindsAndDimensionsAreDroppedSilently() throws IOException {
        final Path file = tempDir.resolve("portals.json");
        Files.writeString(file, """
            {
              "schemaVersion": 1,
              "dimensions": {
                "minecraft:overworld": [
                  {"kind": "nether_portal", "chunkX": 1, "chunkZ": 2, "x": 20, "z": 40},
                  {"kind": "end_portal_frame", "chunkX": 1, "chunkZ": 2, "x": 21, "z": 41},
                  null
                ]
              }
            }
            """);
        final Map<DimensionId, List<PortalMarker>> loaded = PortalIo.load(file, LOGGER);
        assertEquals(1, loaded.size());
        assertEquals(
            List.of(new PortalMarker(DimensionId.OVERWORLD, 1, 2, PortalKind.NETHER_PORTAL, 20, 40)),
            loaded.get(DimensionId.OVERWORLD)
        );
    }

    @Test
    void futureSchemaLoadsEmptyWithoutClobbering() throws IOException {
        final Path file = tempDir.resolve("portals.json");
        Files.writeString(file, """
            {
              "schemaVersion": 99,
              "dimensions": {"minecraft:overworld": [
                {"kind": "nether_portal", "chunkX": 1, "chunkZ": 2, "x": 20, "z": 40}
              ]}
            }
            """);
        assertTrue(PortalIo.load(file, LOGGER).isEmpty());
        assertTrue(Files.exists(file), "a future-schema file must be left untouched");
    }
}
