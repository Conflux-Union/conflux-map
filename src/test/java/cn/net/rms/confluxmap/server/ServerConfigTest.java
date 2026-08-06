package cn.net.rms.confluxmap.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.net.HelloPolicyS2C;
import cn.net.rms.confluxmap.core.net.CorrectionProfile;
import cn.net.rms.confluxmap.core.net.MapSyncCapability;
import cn.net.rms.confluxmap.core.net.NegotiatedMapSync;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointProto;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
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
        assertTrue(c.allowBiomeMap);
        assertTrue(c.allowStructureSearch);
        assertTrue(c.enabled);
        assertTrue(c.shareCorrections);
        assertFalse(c.shareChunkLoadState);
        assertTrue(c.allowEntityRadar);
        assertFalse(c.shareWaypoints);
        assertEquals(SharedWaypointProto.MAX_SNAPSHOT_WAYPOINTS, c.maxSharedWaypointsPerWorld);
        assertEquals(64, c.maxSharedWaypointsPerPlayer);
        assertEquals(30, c.sharedWaypointMutationsPerMinute);
        assertEquals(8, c.maxTilesPerRequest);
        assertEquals(
            Proto.MAX_MAP_SYNC_VIEW_TILES,
            c.maxPendingTilesPerPlayer,
            "one normal subscribed viewport must fit without queue rate limiting"
        );
        assertEquals(256 * 1024, c.maxBytesPerSecondPerPlayer);
        assertEquals(100, c.minRequestIntervalMs);
    }

    @Test
    void loadStateCapabilityIsAdvertisedOnlyWhenExplicitlyEnabled() {
        final ServerConfig config = new ServerConfig();
        assertFalse(ServerNetworking.policyFlags(config).chunkLoadStateEnabled());

        config.shareChunkLoadState = true;
        assertTrue(ServerNetworking.policyFlags(config).chunkLoadStateEnabled());

        config.enabled = false;
        assertFalse(ServerNetworking.policyFlags(config).chunkLoadStateEnabled());
    }

    @Test
    void correctionInvalidationsFollowTheCorrectionCapability() {
        final ServerConfig config = new ServerConfig();
        assertTrue(ServerNetworking.policyFlags(config).correctionInvalidationEnabled());

        config.shareCorrections = false;
        assertFalse(ServerNetworking.policyFlags(config).correctionInvalidationEnabled());

        config.shareCorrections = true;
        config.enabled = false;
        assertFalse(ServerNetworking.policyFlags(config).correctionInvalidationEnabled());
    }

    @Test
    void chunkRangeCorrectionsFollowTheCorrectionCapability() {
        final ServerConfig config = new ServerConfig();
        assertTrue(ServerNetworking.policyFlags(config).chunkRangeCorrectionEnabled());

        config.shareCorrections = false;
        assertFalse(ServerNetworking.policyFlags(config).chunkRangeCorrectionEnabled());

        config.shareCorrections = true;
        config.enabled = false;
        assertFalse(ServerNetworking.policyFlags(config).chunkRangeCorrectionEnabled());
    }

    @Test
    void incompatiblePeerMasksOnlyCorrectionCapabilities() {
        final ServerConfig config = new ServerConfig();
        config.shareSeed = true;
        config.shareChunkLoadState = true;
        config.allowEntityRadar = false;
        final HelloPolicyS2C.Flags configured = ServerNetworking.policyFlags(config);
        final HelloPolicyS2C.Flags masked = ServerNetworking.compatibleFlags(
            configured,
            NegotiatedMapSync.server(
                CorrectionProfile.SOURCE_LIGHT_V2,
                NegotiatedMapSync.CorrectionMode.DISABLED,
                "",
                MapSyncCapability.all()
            )
        );

        assertFalse(masked.correctionsEnabled());
        assertFalse(masked.correctionInvalidationEnabled());
        assertFalse(masked.chunkRangeCorrectionEnabled());
        assertTrue(masked.seedGranted());
        assertTrue(masked.chunkLoadStateEnabled());
        assertTrue(masked.entityRadarForbidden());
    }

    @Test
    void regionPolicyRequiresBothRequestAndInvalidationCapabilities() {
        final HelloPolicyS2C.Flags configured = ServerNetworking.policyFlags(new ServerConfig());
        final NegotiatedMapSync requestOnly = NegotiatedMapSync.server(
            CorrectionProfile.SOURCE_LIGHT_V2,
            NegotiatedMapSync.CorrectionMode.RESIDUAL,
            "baseline",
            Map.of(MapSyncCapability.REGION_CORRECTION, 1)
        );

        assertFalse(
            ServerNetworking.compatibleFlags(configured, requestOnly)
                .chunkRangeCorrectionEnabled()
        );
    }

    @Test
    void radarIsAllowedUnlessAnEnabledCompanionForbidsIt() {
        final ServerConfig config = new ServerConfig();
        assertFalse(ServerNetworking.policyFlags(config).entityRadarForbidden());

        config.allowEntityRadar = false;
        assertTrue(ServerNetworking.policyFlags(config).entityRadarForbidden());

        config.enabled = false;
        assertFalse(ServerNetworking.policyFlags(config).entityRadarForbidden());
    }

    @Test
    void seedFeaturesAreAllowedUnlessSeedSharingExplicitlyForbidsThem() {
        final ServerConfig config = new ServerConfig();
        config.allowBiomeMap = false;
        config.allowStructureSearch = false;

        assertFalse(ServerNetworking.policyFlags(config).biomeMapForbidden());
        assertFalse(ServerNetworking.policyFlags(config).structureSearchForbidden());

        config.shareSeed = true;
        assertTrue(ServerNetworking.policyFlags(config).biomeMapForbidden());
        assertTrue(ServerNetworking.policyFlags(config).structureSearchForbidden());

        config.enabled = false;
        assertFalse(ServerNetworking.policyFlags(config).biomeMapForbidden());
        assertFalse(ServerNetworking.policyFlags(config).structureSearchForbidden());
    }

    @Test
    void normalizeClampsOutliers() {
        final ServerConfig c = new ServerConfig();
        c.maxTilesPerRequest = -5;
        c.maxPendingTilesPerPlayer = 1_000_000;
        c.maxBytesPerSecondPerPlayer = 1;
        c.minRequestIntervalMs = -100;
        c.maxChunkSummariesPerSecond = 0;
        c.maxSharedWaypointsPerWorld = 0;
        c.maxSharedWaypointsPerPlayer = 50_000;
        c.sharedWaypointMutationsPerMinute = 50_000;
        c.normalize();
        assertEquals(1, c.maxTilesPerRequest);
        assertEquals(1024, c.maxPendingTilesPerPlayer);
        assertEquals(1024, c.maxBytesPerSecondPerPlayer);
        assertEquals(0, c.minRequestIntervalMs);
        assertEquals(1, c.maxChunkSummariesPerSecond);
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
        original.allowBiomeMap = false;
        original.allowStructureSearch = false;
        original.shareChunkLoadState = true;
        original.allowEntityRadar = false;
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
        assertEquals(original.allowBiomeMap, loaded.allowBiomeMap);
        assertEquals(original.allowStructureSearch, loaded.allowStructureSearch);
        assertEquals(original.shareCorrections, loaded.shareCorrections);
        assertEquals(original.shareChunkLoadState, loaded.shareChunkLoadState);
        assertEquals(original.allowEntityRadar, loaded.allowEntityRadar);
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
    void loadFillsMissingFieldsAndRewritesFile(@TempDir final Path tmp) throws IOException {
        final Path file = tmp.resolve("server.json");
        Files.writeString(
            file,
            "{\"schemaVersion\":1,\"shareSeed\":true,\"shareStructureInfo\":true,"
                + "\"maxPatchLod\":2,\"maxPresenceLod\":4}",
            StandardCharsets.UTF_8
        );

        final ServerConfig loaded = new ServerConfigIo(file, LOGGER).load();

        assertTrue(loaded.shareSeed);
        assertTrue(loaded.allowEntityRadar);
        assertTrue(loaded.allowBiomeMap);
        assertTrue(loaded.allowStructureSearch);
        // The upgrade is persisted so the on-disk file now carries the full schema.
        final String rewritten = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(rewritten.contains("\"sharedWaypointMutationsPerMinute\""));
        assertTrue(rewritten.contains("\"allowEntityRadar\": true"));
        assertTrue(rewritten.contains("\"shareSeed\": true"));
        assertFalse(rewritten.contains("shareStructureInfo"));
        assertFalse(rewritten.contains("maxPatchLod"), "LOD sync is no longer operator-capped");
        assertFalse(rewritten.contains("maxPresenceLod"));
    }

    @Test
    void legacyDefaultSyncLimitsUpgradeToUsableDefaults(@TempDir final Path tmp) throws IOException {
        final Path file = tmp.resolve("server.json");
        Files.writeString(
            file,
            "{\"schemaVersion\":2,\"maxPendingTilesPerPlayer\":16,"
                + "\"maxBytesPerSecondPerPlayer\":65536,\"minRequestIntervalMs\":300}",
            StandardCharsets.UTF_8
        );

        final ServerConfig loaded = new ServerConfigIo(file, LOGGER).load();

        assertEquals(ServerConfig.SCHEMA_VERSION, loaded.schemaVersion);
        assertEquals(Proto.MAX_MAP_SYNC_VIEW_TILES, loaded.maxPendingTilesPerPlayer);
        assertEquals(256 * 1024, loaded.maxBytesPerSecondPerPlayer);
        assertEquals(100, loaded.minRequestIntervalMs);
        final String rewritten = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(rewritten.contains("\"schemaVersion\": " + ServerConfig.SCHEMA_VERSION));
        assertTrue(rewritten.contains("\"maxPendingTilesPerPlayer\": 256"));
    }

    @Test
    void legacyOperatorSyncLimitsArePreserved(@TempDir final Path tmp) throws IOException {
        final Path file = tmp.resolve("server.json");
        Files.writeString(
            file,
            "{\"schemaVersion\":2,\"maxPendingTilesPerPlayer\":48,"
                + "\"maxBytesPerSecondPerPlayer\":131072,\"minRequestIntervalMs\":500}",
            StandardCharsets.UTF_8
        );

        final ServerConfig loaded = new ServerConfigIo(file, LOGGER).load();

        assertEquals(48, loaded.maxPendingTilesPerPlayer);
        assertEquals(131_072, loaded.maxBytesPerSecondPerPlayer);
        assertEquals(500, loaded.minRequestIntervalMs);
    }

    @Test
    void loadKeepsNewerSchemaFileIntact(@TempDir final Path tmp) throws IOException {
        final Path file = tmp.resolve("server.json");
        final int futureSchema = ServerConfig.SCHEMA_VERSION + 1;
        final String futureJson = "{\"schemaVersion\": " + futureSchema + ", \"futureField\": true}";
        Files.writeString(file, futureJson, StandardCharsets.UTF_8);

        final ServerConfig loaded = new ServerConfigIo(file, LOGGER).load();

        assertEquals(futureSchema, loaded.schemaVersion);
        assertEquals(futureJson, Files.readString(file, StandardCharsets.UTF_8));
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
