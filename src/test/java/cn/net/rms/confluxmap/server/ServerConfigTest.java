package cn.net.rms.confluxmap.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.net.shared.SharedWaypointProto;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Round-trip and normalize coverage for {@link ServerConfig} / {@link ServerConfigIo}. */
class ServerConfigTest {

    private static final Logger LOGGER = LogManager.getLogger("ServerConfigTest");

    @Test
    void defaultsAreSafe() {
        final ServerConfig c = new ServerConfig();
        assertEquals(ServerConfig.SCHEMA_VERSION, c.schemaVersion);
        // shareSeed defaults OFF for security (plan requirement).
        assertEquals(false, c.shareSeed);
        assertTrue(c.enabled);
        assertTrue(c.shareCorrections);
        assertFalse(c.shareStructureInfo);
        assertFalse(c.shareWaypoints);
        assertEquals(SharedWaypointProto.MAX_SNAPSHOT_WAYPOINTS, c.maxSharedWaypointsPerWorld);
        assertEquals(64, c.maxSharedWaypointsPerPlayer);
        assertEquals(30, c.sharedWaypointMutationsPerMinute);
        assertEquals(2, c.maxPatchLod);
        assertEquals(8, c.maxTilesPerRequest);
    }

    @Test
    void normalizeClampsOutliers() {
        final ServerConfig c = new ServerConfig();
        c.maxPatchLod = 99;
        c.maxTilesPerRequest = -5;
        c.maxPendingTilesPerPlayer = 1_000_000;
        c.maxBytesPerSecondPerPlayer = 1;
        c.minRequestIntervalMs = -100;
        c.maxChunkSummariesPerSecond = 0;
        c.shareStructureInfo = true;
        c.maxSharedWaypointsPerWorld = 0;
        c.maxSharedWaypointsPerPlayer = 50_000;
        c.sharedWaypointMutationsPerMinute = 50_000;
        c.normalize();
        assertEquals(4, c.maxPatchLod);
        assertEquals(1, c.maxTilesPerRequest);
        assertEquals(1024, c.maxPendingTilesPerPlayer);
        assertEquals(1024, c.maxBytesPerSecondPerPlayer);
        assertEquals(0, c.minRequestIntervalMs);
        assertEquals(1, c.maxChunkSummariesPerSecond);
        assertFalse(c.shareStructureInfo);
        assertEquals(1, c.maxSharedWaypointsPerWorld);
        assertEquals(1, c.maxSharedWaypointsPerPlayer);
        assertEquals(6_000, c.sharedWaypointMutationsPerMinute);
    }

    @Test
    void sharedWaypointQuotaNeverExceedsProtocolSnapshotCap() {
        final ServerConfig c = new ServerConfig();
        c.maxSharedWaypointsPerWorld = 50_000;
        c.maxSharedWaypointsPerPlayer = 50_000;

        c.normalize();

        assertEquals(SharedWaypointProto.MAX_SNAPSHOT_WAYPOINTS, c.maxSharedWaypointsPerWorld);
        assertEquals(SharedWaypointProto.MAX_SNAPSHOT_WAYPOINTS, c.maxSharedWaypointsPerPlayer);
    }

    @Test
    void ioRoundTripsEveryField(@TempDir final Path tmp) throws IOException {
        final ServerConfigIo io = new ServerConfigIo(tmp.resolve("server.json"), LOGGER);
        final ServerConfig original = new ServerConfig();
        original.shareSeed = true;
        original.maxPatchLod = 3;
        original.maxTilesPerRequest = 5;
        original.maxBytesPerSecondPerPlayer = 131_072;
        original.minRequestIntervalMs = 500;
        original.shareWaypoints = true;
        original.maxSharedWaypointsPerWorld = 500;
        original.maxSharedWaypointsPerPlayer = 70;
        original.sharedWaypointMutationsPerMinute = 45;

        assertTrue(io.saveAtomically(original));
        final ServerConfig loaded = io.load();

        assertEquals(original.schemaVersion, loaded.schemaVersion);
        assertEquals(original.enabled, loaded.enabled);
        assertEquals(original.shareSeed, loaded.shareSeed);
        assertEquals(original.shareCorrections, loaded.shareCorrections);
        assertEquals(original.shareStructureInfo, loaded.shareStructureInfo);
        assertEquals(original.maxPatchLod, loaded.maxPatchLod);
        assertEquals(original.maxTilesPerRequest, loaded.maxTilesPerRequest);
        assertEquals(original.maxPendingTilesPerPlayer, loaded.maxPendingTilesPerPlayer);
        assertEquals(original.maxBytesPerSecondPerPlayer, loaded.maxBytesPerSecondPerPlayer);
        assertEquals(original.minRequestIntervalMs, loaded.minRequestIntervalMs);
        assertEquals(original.maxChunkSummariesPerSecond, loaded.maxChunkSummariesPerSecond);
        assertEquals(original.shareWaypoints, loaded.shareWaypoints);
        assertEquals(original.maxSharedWaypointsPerWorld, loaded.maxSharedWaypointsPerWorld);
        assertEquals(original.maxSharedWaypointsPerPlayer, loaded.maxSharedWaypointsPerPlayer);
        assertEquals(original.sharedWaypointMutationsPerMinute, loaded.sharedWaypointMutationsPerMinute);
    }

    @Test
    void ioQuarantinesCorruptJsonAndWritesDefaults(@TempDir final Path tmp) throws IOException {
        final Path file = tmp.resolve("server.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{ this is not json", StandardCharsets.UTF_8);

        final ServerConfigIo io = new ServerConfigIo(file, LOGGER);
        final ServerConfig loaded = io.load();
        // Defaults are returned so the server can still start.
        assertEquals(new ServerConfig().shareSeed, loaded.shareSeed);
        // The original corrupt file has been moved aside as *.bad so the next write can succeed.
        assertTrue(Files.exists(file.resolveSibling("server.json.bad")));
    }

    @Test
    void missingFileWritesDefaultsOnFirstLoad(@TempDir final Path tmp) throws IOException {
        final Path file = tmp.resolve("server.json");
        final ServerConfigIo io = new ServerConfigIo(file, LOGGER);
        final ServerConfig loaded = io.load();
        assertEquals(new ServerConfig().schemaVersion, loaded.schemaVersion);
        assertTrue(Files.exists(file));
    }

    @Test
    void saveReportsAtomicWriteFailure(@TempDir final Path tmp) throws IOException {
        final Path blockingParent = tmp.resolve("not-a-directory");
        Files.writeString(blockingParent, "block", StandardCharsets.UTF_8);
        final ServerConfigIo io = new ServerConfigIo(blockingParent.resolve("server.json"), LOGGER);

        assertFalse(io.saveAtomically(new ServerConfig()));
    }

    @Test
    void repeatedDisableRetriesPersistenceAfterAnIoFailure(@TempDir final Path tmp) throws IOException {
        final Path configParent = tmp.resolve("blocked-parent");
        Files.writeString(configParent, "block", StandardCharsets.UTF_8);
        final Path configFile = configParent.resolve("server.json");
        final ConfluxMapCompanion companion = new ConfluxMapCompanion(
            new ServerConfigIo(configFile, LOGGER)
        );

        assertEquals(
            ConfluxMapCompanion.SharedWaypointToggleResult.DISABLED_SAVE_FAILED,
            companion.disableSharedWaypoints(null)
        );

        Files.delete(configParent);
        Files.createDirectories(configParent);
        assertEquals(
            ConfluxMapCompanion.SharedWaypointToggleResult.ALREADY_DISABLED,
            companion.disableSharedWaypoints(null)
        );
        assertTrue(Files.exists(configFile));
        assertFalse(new ServerConfigIo(configFile, LOGGER).load().shareWaypoints);
    }
}
