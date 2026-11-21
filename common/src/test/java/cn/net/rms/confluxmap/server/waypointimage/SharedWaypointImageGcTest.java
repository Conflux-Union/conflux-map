package cn.net.rms.confluxmap.server.waypointimage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.net.image.WaypointImageProto;
import cn.net.rms.confluxmap.core.waypoint.image.WaypointImageStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SharedWaypointImageGcTest {
    private static final Logger LOGGER = LogManager.getLogger("SharedWaypointImageGcTest");

    private static Path writeWaypointsFile(final Path file, final String imageIdsJson)
        throws IOException {
        Files.writeString(file, "{\n"
            + "  \"schemaVersion\": 3,\n"
            + "  \"revision\": 1,\n"
            + "  \"waypoints\": " + imageIdsJson + "\n"
            + "}\n");
        return file;
    }

    @Test
    void collectsReferencesFromTheWaypointFileAndSweepsTheRest(@TempDir final Path tempDir)
        throws Exception {
        final WaypointImageStore images = new WaypointImageStore(tempDir.resolve("images"));
        final UUID referenced = UUID.randomUUID();
        final UUID stale = UUID.randomUUID();
        final UUID fresh = UUID.randomUUID();
        images.save(referenced, UUID.randomUUID(), WaypointImageProto.FORMAT_PNG, 8, 8, new byte[32]);
        images.save(stale, UUID.randomUUID(), WaypointImageProto.FORMAT_PNG, 8, 8, new byte[32]);
        images.save(fresh, UUID.randomUUID(), WaypointImageProto.FORMAT_PNG, 8, 8, new byte[32]);

        final Path waypoints = writeWaypointsFile(tempDir.resolve("shared_waypoints.json"), "[{"
            + "\"id\": \"" + UUID.randomUUID() + "\","
            + "\"publisherId\": \"" + UUID.randomUUID() + "\","
            + "\"publisherName\": \"Builder\","
            + "\"name\": \"Base\","
            + "\"dimensionId\": \"minecraft:overworld\","
            + "\"x\": 1.0, \"y\": 64.0, \"z\": 2.0,"
            + "\"colorArgb\": -13408615,"
            + "\"type\": \"NORMAL\","
            + "\"iconItemId\": \"\","
            + "\"markerLabel\": \"\","
            + "\"description\": \"\","
            + "\"imageId\": \"" + referenced + "\","
            + "\"createdAtEpochMs\": 1,"
            + "\"revision\": 1"
            + "}]");

        final SharedWaypointImageGc gc = new SharedWaypointImageGc(
            images, () -> java.util.Optional.of(waypoints), LOGGER
        );
        final long collectTime = System.currentTimeMillis() + 25L * 3600 * 1000;
        // The fresh image keeps an mtime inside the 24h grace window at collect time.
        Files.setLastModifiedTime(
            images.fileFor(fresh), java.nio.file.attribute.FileTime.fromMillis(collectTime - 12L * 3600 * 1000)
        );
        assertEquals(1, gc.collect(collectTime));
        assertTrue(images.exists(referenced));
        assertFalse(images.exists(stale));
        // The fresh unreferenced image survives within the 24h grace window.
        assertTrue(images.exists(fresh));
    }

    @Test
    void aMissingOrUnreadableWaypointFileSweepsNothing(@TempDir final Path tempDir) throws Exception {
        final WaypointImageStore images = new WaypointImageStore(tempDir.resolve("images"));
        final UUID imageId = UUID.randomUUID();
        images.save(imageId, UUID.randomUUID(), WaypointImageProto.FORMAT_PNG, 8, 8, new byte[32]);

        final SharedWaypointImageGc missingFile = new SharedWaypointImageGc(
            images, java.util.Optional::empty, LOGGER
        );
        assertEquals(0, missingFile.collect(System.currentTimeMillis() + 25L * 3600 * 1000));
        assertTrue(images.exists(imageId));

        final Path garbage = tempDir.resolve("shared_waypoints.json");
        Files.writeString(garbage, "not json");
        final SharedWaypointImageGc unreadable = new SharedWaypointImageGc(
            images, () -> java.util.Optional.of(garbage), LOGGER
        );
        assertEquals(0, unreadable.collect(System.currentTimeMillis() + 25L * 3600 * 1000));
        assertTrue(images.exists(imageId));
    }
}
