package cn.net.rms.confluxmap.server.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.shared.SharedWaypoint;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SharedWaypointIoTest {
    private static final Logger LOGGER = LogManager.getLogger("SharedWaypointIoTest");

    @Test
    void roundTripsExplicitSchemaAtWorldPath(@TempDir final Path worldRoot) throws Exception {
        final SharedWaypointIo io = new SharedWaypointIo(worldRoot, LOGGER);
        final SharedWaypoint waypoint = new SharedWaypoint(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            UUID.fromString("00000000-0000-0000-0000-000000000002"),
            "PlayerOne", "村庄", DimensionId.OVERWORLD,
            12.5d, 64d, -8.25d, 0xFF33AA66, Waypoint.Type.NORMAL, true, 1_234L, 3L
        );

        io.save(new SharedWaypointStore.Snapshot(3L, List.of(waypoint)));
        final SharedWaypointStore.Snapshot loaded = io.load();

        assertEquals(3L, loaded.revision());
        assertEquals(List.of(waypoint), loaded.waypoints());
        assertEquals(worldRoot.resolve("confluxmap/shared_waypoints.json"), io.file());
        assertFalse(Files.exists(io.file().resolveSibling("shared_waypoints.json.tmp")));
        final String json = Files.readString(io.file(), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"schemaVersion\": 1"));
        assertTrue(json.contains("\"publisherId\""));
        assertTrue(json.contains("村庄"));
    }

    @Test
    void corruptFileIsQuarantinedAndReturnsEmpty(@TempDir final Path worldRoot) throws Exception {
        final SharedWaypointIo io = new SharedWaypointIo(worldRoot, LOGGER);
        Files.createDirectories(io.file().getParent());
        Files.writeString(io.file(), "{not-json", StandardCharsets.UTF_8);

        final SharedWaypointStore.Snapshot loaded = io.load();

        assertEquals(0L, loaded.revision());
        assertTrue(loaded.waypoints().isEmpty());
        assertFalse(Files.exists(io.file()));
        assertTrue(Files.exists(io.file().resolveSibling("shared_waypoints.json.bad")));
    }

    @Test
    void futureSchemaIsRejectedWithoutQuarantine(@TempDir final Path worldRoot) throws Exception {
        final SharedWaypointIo io = new SharedWaypointIo(worldRoot, LOGGER);
        Files.createDirectories(io.file().getParent());
        Files.writeString(io.file(), "{\"schemaVersion\":2,\"revision\":0,\"waypoints\":[]}", StandardCharsets.UTF_8);

        final SharedWaypointIo.UnsupportedSchemaVersionException error = assertThrows(
            SharedWaypointIo.UnsupportedSchemaVersionException.class,
            io::load
        );

        assertEquals(2, error.schemaVersion());
        assertTrue(Files.exists(io.file()));
        assertFalse(Files.exists(io.file().resolveSibling("shared_waypoints.json.bad")));
    }

    @Test
    void largeFutureSchemaIsRejectedWithoutIntegerOverflow(@TempDir final Path worldRoot) throws Exception {
        final SharedWaypointIo io = new SharedWaypointIo(worldRoot, LOGGER);
        Files.createDirectories(io.file().getParent());
        Files.writeString(
            io.file(),
            "{\"schemaVersion\":2147483648,\"revision\":0,\"waypoints\":[]}",
            StandardCharsets.UTF_8
        );

        final SharedWaypointIo.UnsupportedSchemaVersionException error = assertThrows(
            SharedWaypointIo.UnsupportedSchemaVersionException.class,
            io::load
        );

        assertEquals(2_147_483_648L, error.schemaVersion());
        assertTrue(Files.exists(io.file()));
        assertFalse(Files.exists(io.file().resolveSibling("shared_waypoints.json.bad")));
    }

    @Test
    void fractionalSchemaIsQuarantinedInsteadOfBeingTruncated(@TempDir final Path worldRoot) throws Exception {
        final SharedWaypointIo io = new SharedWaypointIo(worldRoot, LOGGER);
        Files.createDirectories(io.file().getParent());
        Files.writeString(
            io.file(),
            "{\"schemaVersion\":1.5,\"revision\":0,\"waypoints\":[]}",
            StandardCharsets.UTF_8
        );

        assertTrue(io.load().waypoints().isEmpty());
        assertFalse(Files.exists(io.file()));
        assertTrue(Files.exists(io.file().resolveSibling("shared_waypoints.json.bad")));
    }

    @Test
    void malformedSchemaOneEntryIsQuarantined(@TempDir final Path worldRoot) throws Exception {
        final SharedWaypointIo io = new SharedWaypointIo(worldRoot, LOGGER);
        Files.createDirectories(io.file().getParent());
        Files.writeString(
            io.file(),
            "{\"schemaVersion\":1,\"revision\":1,\"waypoints\":[{\"id\":\"invalid\"}]}",
            StandardCharsets.UTF_8
        );

        assertTrue(io.load().waypoints().isEmpty());
        assertTrue(Files.exists(io.file().resolveSibling("shared_waypoints.json.bad")));
    }

    @Test
    void unpairedSurrogateNameIsQuarantinedBeforeProtocolEncoding(@TempDir final Path worldRoot)
        throws Exception {
        final SharedWaypointIo io = new SharedWaypointIo(worldRoot, LOGGER);
        Files.createDirectories(io.file().getParent());
        Files.writeString(
            io.file(),
            "{\"schemaVersion\":1,\"revision\":1,\"waypoints\":[{"
                + "\"id\":\"00000000-0000-0000-0000-000000000001\","
                + "\"publisherId\":\"00000000-0000-0000-0000-000000000002\","
                + "\"publisherName\":\"Player\",\"name\":\"\\uD800\","
                + "\"dimensionId\":\"minecraft:overworld\","
                + "\"x\":0,\"y\":64,\"z\":0,\"colorArgb\":-1,"
                + "\"type\":\"NORMAL\",\"locked\":false,"
                + "\"createdAtEpochMs\":1,\"revision\":1}]}",
            StandardCharsets.UTF_8
        );

        assertTrue(io.load().waypoints().isEmpty());
        assertFalse(Files.exists(io.file()));
        assertTrue(Files.exists(io.file().resolveSibling("shared_waypoints.json.bad")));
    }

    @Test
    void quarantineFailurePreservesCorruptFileAndAbortsLoad(@TempDir final Path worldRoot) throws Exception {
        final SharedWaypointIo io = new SharedWaypointIo(worldRoot, LOGGER);
        Files.createDirectories(io.file().getParent());
        final String corruptJson = "{not-json";
        Files.writeString(io.file(), corruptJson, StandardCharsets.UTF_8);
        final Path blockedQuarantine = io.file().resolveSibling("shared_waypoints.json.bad");
        Files.createDirectories(blockedQuarantine);
        Files.writeString(blockedQuarantine.resolve("keep"), "keep", StandardCharsets.UTF_8);

        assertThrows(IOException.class, io::load);

        assertEquals(corruptJson, Files.readString(io.file(), StandardCharsets.UTF_8));
        assertTrue(Files.isDirectory(blockedQuarantine));
        assertTrue(Files.exists(blockedQuarantine.resolve("keep")));
    }
}
