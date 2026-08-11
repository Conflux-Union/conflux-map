package cn.net.rms.confluxmap.core.multiworld;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientWorldProfileIoTest {
    @TempDir
    Path tempDir;

    @Test
    void savesAndLoadsProfilesWithTheirRecognitionBindings() {
        final Path file = tempDir.resolve("client_worlds.json");
        final ClientWorldProfileIo io = new ClientWorldProfileIo(
            file, LogManager.getLogger("ClientWorldProfileIoTest")
        );
        final ClientWorldProfileRegistry registry = new ClientWorldProfileRegistry();
        final ClientWorldProfileResolver resolver = new ClientWorldProfileResolver(
            registry,
            () -> UUID.fromString("00000000-0000-0000-0000-000000000001")
        );
        final ClientWorldProfile original = resolver.resolveVelocityServer(
            "example.net_25565",
            "Survival",
            new ClientWorldObservation(OptionalLong.of(42L), Map.of("brand", "hashed-brand")),
            null,
            false
        ).profile();
        resolver.rename("example.net_25565", original.id(), "Survival");

        io.save(registry);
        final ClientWorldProfileRegistry loaded = io.load();

        final ClientWorldProfile restored = loaded.profiles("example.net_25565").get(0);
        assertEquals("world", restored.storageId());
        assertEquals("Survival", restored.displayName());
        assertEquals(1, restored.bindingCount());
        assertEquals("survival", restored.velocityServerName().orElseThrow());
    }

    @Test
    void quarantinesCorruptJsonAndReturnsAnEmptyRegistry() throws Exception {
        final Path file = tempDir.resolve("client_worlds.json");
        Files.writeString(file, "{not json");
        final ClientWorldProfileIo io = new ClientWorldProfileIo(
            file, LogManager.getLogger("ClientWorldProfileIoTest")
        );

        final ClientWorldProfileRegistry loaded = io.load();

        assertTrue(loaded.profiles("example.net_25565").isEmpty());
        assertTrue(Files.exists(file.resolveSibling("client_worlds.json.blocked")));
        try (var files = Files.list(tempDir)) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().startsWith(
                "client_worlds.json.bad."
            )));
        }
    }

    @Test
    void fallsBackToReplaceWhenTheFilesystemDoesNotSupportAtomicMoves() throws Exception {
        final Path file = tempDir.resolve("client_worlds.json");
        final List<Boolean> atomicAttempts = new ArrayList<>();
        final ClientWorldProfileIo io = new ClientWorldProfileIo(
            file,
            LogManager.getLogger("ClientWorldProfileIoTest"),
            (source, target, atomic) -> {
                atomicAttempts.add(atomic);
                if (atomic) {
                    throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "test filesystem");
                }
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        );

        assertTrue(io.save(new ClientWorldProfileRegistry()).saved());

        assertEquals(List.of(true, false), atomicAttempts);
        assertTrue(io.load().available());
    }

    @Test
    void ignoresAnInterruptedTemporaryWriteWhenTheLastRegistryIsComplete() throws Exception {
        final Path file = tempDir.resolve("client_worlds.json");
        Files.writeString(file, "{\"schemaVersion\":3,\"servers\":{}}");
        final Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, "{incomplete");

        final ClientWorldProfileRegistry loaded = new ClientWorldProfileIo(
            file, LogManager.getLogger("ClientWorldProfileIoTest")
        ).load();

        assertTrue(loaded.available());
        assertTrue(loaded.profiles("example.net").isEmpty());
        assertTrue(Files.exists(temporary));
        assertFalse(Files.exists(file.resolveSibling("client_worlds.json.blocked")));
    }

    @Test
    void persistsAnUnboundProfileAsRequiringManualSelection() {
        final Path file = tempDir.resolve("client_worlds.json");
        final ClientWorldProfileIo io = new ClientWorldProfileIo(
            file, LogManager.getLogger("ClientWorldProfileIoTest")
        );
        final ClientWorldProfileRegistry registry = new ClientWorldProfileRegistry();
        final ClientWorldProfileResolver resolver = new ClientWorldProfileResolver(registry, UUID::randomUUID);
        final ClientWorldObservation observation = new ClientWorldObservation(
            OptionalLong.of(42L), Map.of("brand", "hashed-brand")
        );
        final ClientWorldProfile profile = resolver.resolve("example.net_25565", observation).profile();
        resolver.clearBindings("example.net_25565", profile.id());
        io.save(registry);

        final ClientWorldProfileResolver restored = new ClientWorldProfileResolver(io.load(), UUID::randomUUID);

        assertEquals(
            ClientWorldResolution.State.AMBIGUOUS,
            restored.resolve("example.net_25565", observation).state()
        );
    }

    @Test
    void schemaThreePersistsLastStableProfileTrajectoryAndConnectionGeneration() {
        final Path file = tempDir.resolve("client_worlds.json");
        final ClientWorldProfileIo io = new ClientWorldProfileIo(
            file, LogManager.getLogger("ClientWorldProfileIoTest")
        );
        final ClientWorldProfileRegistry registry = new ClientWorldProfileRegistry();
        final ClientWorldProfileResolver resolver = new ClientWorldProfileResolver(registry, UUID::randomUUID);
        final ClientWorldTrajectory trajectory = new ClientWorldTrajectory();
        trajectory.append(ClientWorldTrajectorySample.observed(
            10, 64, 20, 4, 0, -90, 0, 1_000L, 20L,
            "minecraft_overworld", 20L, ClientWorldTrajectorySample.NO_SERVER_ACK, 7L
        ));
        final ClientWorldObservation observation = new ClientWorldObservation(
            OptionalLong.of(42L),
            Map.of("brand", "brand", "commands", "commands"),
            "minecraft_overworld", "SURVIVAL", new ClientWorldPosition(10, 64, 20),
            null, Map.of(), trajectory
        );
        final ClientWorldProfile profile = resolver.resolve("example.net_25565", observation).profile();

        io.save(registry);
        final ClientWorldProfileRegistry loaded = io.load();

        assertEquals(3, ClientWorldProfileRegistry.SCHEMA_VERSION);
        assertEquals(profile.id(), loaded.lastStableProfileId("example.net_25565"));
        assertEquals(7L, loaded.lastStableProfile("example.net_25565").connectionGeneration());
        assertTrue(loaded.lastStableProfile("example.net_25565").hasSeed());
        assertEquals(42L, loaded.lastStableProfile("example.net_25565").seedHash());
        assertEquals("brand", loaded.lastStableProfile("example.net_25565")
            .stableSignals().get("brand"));
        final ClientWorldVisit visit = loaded.profiles("example.net_25565").get(0)
            .visit("minecraft_overworld");
        assertEquals(7L, visit.connectionGeneration());
        assertEquals(20L, visit.trajectorySamples().get(0).sequence());
    }

    @Test
    void schemaTwoUpgradePreservesProfilesAndDropsMalformedStablePointer() throws Exception {
        final Path file = tempDir.resolve("client_worlds.json");
        Files.writeString(file, """
            {
              "schemaVersion": 2,
              "servers": {
                "example.net_25565": [{
                  "id": "legacy",
                  "storageId": "world",
                  "displayName": "Legacy",
                  "bindings": [],
                  "switchCommands": [],
                  "visits": {}
                }]
              },
              "lastStableProfiles": {
                "example.net_25565": {
                  "profileId": "missing",
                  "confirmedAtEpochMs": -1,
                  "connectionGeneration": 0
                }
              }
            }
            """);

        final ClientWorldProfileRegistry loaded = new ClientWorldProfileIo(
            file, LogManager.getLogger("ClientWorldProfileIoTest")
        ).load();

        assertEquals("legacy", loaded.profiles("example.net_25565").get(0).id());
        assertEquals(null, loaded.lastStableProfile("example.net_25565"));
    }

    @Test
    void loadMigratesPerDimensionPositionsToOneNewestEndpoint() throws Exception {
        final Path file = tempDir.resolve("client_worlds.json");
        Files.writeString(file, """
            {
              "schemaVersion": 3,
              "servers": {
                "example.net_25565": [{
                  "id": "profile",
                  "storageId": "world",
                  "displayName": "World",
                  "bindings": [],
                  "switchCommands": [],
                  "visits": {
                    "minecraft_overworld": {
                      "dimensionId": "minecraft_overworld",
                      "gameMode": "SURVIVAL",
                      "lastPosition": {"x": 20, "y": 70, "z": -150},
                      "lastVisitedAtEpochMs": 100,
                      "terrainFingerprint": null,
                      "terrainAnchors": [],
                      "trajectorySamples": [],
                      "lastServerAckTimeMs": -1,
                      "connectionGeneration": 0,
                      "contextSignals": {}
                    },
                    "minecraft_the_nether": {
                      "dimensionId": "minecraft_the_nether",
                      "gameMode": "SURVIVAL",
                      "lastPosition": {"x": 4, "y": 91, "z": -33},
                      "lastVisitedAtEpochMs": 200,
                      "terrainFingerprint": null,
                      "terrainAnchors": [],
                      "trajectorySamples": [],
                      "lastServerAckTimeMs": -1,
                      "connectionGeneration": 0,
                      "contextSignals": {}
                    }
                  },
                  "recognitionDisabled": false
                }]
              },
              "lastStableProfiles": {}
            }
            """);

        final ClientWorldProfile profile = new ClientWorldProfileIo(
            file, LogManager.getLogger("ClientWorldProfileIoTest")
        ).load().profiles("example.net_25565").get(0);

        assertEquals("minecraft_the_nether", profile.lastObservedVisit().dimensionId());
        assertEquals(91, profile.lastObservedVisit().lastPosition().y());
        assertEquals(null, profile.visit("minecraft_overworld").lastPosition());
        assertEquals(2, profile.visits().size());
    }

    @Test
    void dropsMalformedTerrainAnchorsAndTrajectorySamplesDuringLoad() throws Exception {
        final Path file = tempDir.resolve("client_worlds.json");
        Files.writeString(file, """
            {
              "schemaVersion": 3,
              "servers": {
                "example.net_25565": [{
                  "id": "profile",
                  "storageId": "world",
                  "displayName": "World",
                  "bindings": [],
                  "switchCommands": [],
                  "visits": {
                    "minecraft_overworld": {
                      "dimensionId": "minecraft_overworld",
                      "gameMode": "SURVIVAL",
                      "lastPosition": {"x": 0, "y": 64, "z": 0},
                      "lastVisitedAtEpochMs": 1,
                      "terrainFingerprint": null,
                      "terrainAnchors": [{
                        "position": {"x": 0, "y": 64, "z": 0},
                        "fingerprint": null,
                        "capturedAtEpochMs": -1
                      }],
                      "trajectorySamples": [{
                        "x": 0.0,
                        "y": 64.0,
                        "z": 0.0,
                        "horizontalVelocityX": 0.0,
                        "horizontalVelocityZ": 0.0,
                        "yawDegrees": 0.0,
                        "pitchDegrees": 0.0,
                        "clientTimeMs": -1,
                        "clientTick": 0,
                        "dimensionId": "minecraft_overworld",
                        "sequence": 1,
                        "serverAckTimeMs": -1,
                        "connectionGeneration": 0,
                        "evidenceSource": "CLIENT_OBSERVED"
                      }],
                      "lastServerAckTimeMs": -1,
                      "connectionGeneration": 0,
                      "contextSignals": {}
                    }
                  },
                  "recognitionDisabled": false
                }]
              },
              "lastStableProfiles": {}
            }
            """);

        final ClientWorldProfileRegistry loaded = new ClientWorldProfileIo(
            file, LogManager.getLogger("ClientWorldProfileIoTest")
        ).load();

        final ClientWorldVisit visit = loaded.profiles("example.net_25565").get(0)
            .visit("minecraft_overworld");
        assertFalse(loaded.profiles("example.net_25565").isEmpty());
        assertTrue(visit.terrainAnchors().isEmpty());
        assertTrue(visit.trajectorySamples().isEmpty());
    }
}
