package cn.net.rms.confluxmap.mc.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldObservation;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldPolicy;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldDetectionState;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldProfileDeletionService;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldPosition;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldProfile;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldProfileIo;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldProfileRegistry;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldProfileResolver;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldResolution;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldTrajectory;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldTrajectoryCheckpointIo;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldTrajectorySample;
import cn.net.rms.confluxmap.mc.net.CompanionSession;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientMultiworldServiceTest {
    private static final String ADDRESS = "proxy.example.net:25565";

    @TempDir
    Path tempDir;

    @Test
    void differentSeedProxyJoinStillSelectsASeparateProfile() {
        final ClientMultiworldService service = service(new ClientWorldProfileResolver(
            new ClientWorldProfileRegistry(), ids()
        ));

        service.onGameJoin(11L);
        final WorldIdentity first = service.resolve(ADDRESS).orElseThrow();
        service.onGameJoin(22L);
        final WorldIdentity second = service.resolve(ADDRESS).orElseThrow();

        assertNotEquals(first.worldId(), second.worldId());
    }

    @Test
    void differentRespawnSeedCompletesARepeatedSeedProxyTransition() {
        final ClientMultiworldService service = service(new ClientWorldProfileResolver(
            new ClientWorldProfileRegistry(), ids()
        ));
        service.onGameJoin(11L);
        final WorldIdentity first = service.resolve(ADDRESS).orElseThrow();

        service.onGameJoin(11L);
        assertEquals(ClientWorldResolution.State.COLLECTING, service.resolveProfile(ADDRESS).state());
        service.onRespawn(22L);
        final WorldIdentity second = service.resolve(ADDRESS).orElseThrow();

        assertNotEquals(first.worldId(), second.worldId());
    }

    @Test
    void sameSeedRespawnKeepsTheExistingLogicalProfile() {
        final ClientMultiworldService service = service(new ClientWorldProfileResolver(
            new ClientWorldProfileRegistry(), ids()
        ));
        service.onGameJoin(11L);
        final WorldIdentity first = service.resolve(ADDRESS).orElseThrow();

        service.onRespawn(11L);

        assertEquals(first.worldId(), service.resolve(ADDRESS).orElseThrow().worldId());
    }

    @Test
    void sameSeedRespawnKeepsTheLockedProfileEvenWhenOtherProfilesShareTheSeed() {
        final ClientWorldProfileRegistry registry = new ClientWorldProfileRegistry();
        final ClientWorldProfileResolver resolver = new ClientWorldProfileResolver(registry, ids());
        final ClientMultiworldService service = service(resolver);
        service.onGameJoin(11L);
        service.observeSignals(signals("survival"));
        final WorldIdentity locked = service.resolve(ADDRESS).orElseThrow();
        resolver.createAndSelect(locked.serverId(), "Other", observation(11L, signals("other")));

        service.onRespawn(11L);
        service.observeSignals(signals("other"));

        assertEquals(locked.worldId(), service.resolve(ADDRESS).orElseThrow().worldId());
        assertEquals(ClientWorldDetectionState.STABLE, service.detectionState());
    }

    @Test
    void stableSpawnPositionUpdateDoesNotSuspendTheLockedProfile() {
        final ClientWorldProfileRegistry registry = new ClientWorldProfileRegistry();
        final ClientWorldProfileResolver resolver = new ClientWorldProfileResolver(registry, ids());
        final ClientMultiworldService service = service(resolver);
        service.onGameJoin(11L);
        service.observeSignals(signals("survival"));
        final WorldIdentity locked = service.resolve(ADDRESS).orElseThrow();
        resolver.createAndSelect(locked.serverId(), "Other", observation(11L, signals("other")));

        service.onSpawnPosition(8, 64, 8, 0.0F);

        assertEquals(locked.worldId(), service.resolve(ADDRESS).orElseThrow().worldId());
        assertEquals(ClientWorldDetectionState.STABLE, service.detectionState());
    }

    @Test
    void dimensionTransferDoesNotStartClientWorldRecognition() {
        assertFalse(ClientMultiworldService.shouldProbeAfterWorldReferenceChange(true, true));
        assertTrue(ClientMultiworldService.shouldProbeAfterWorldReferenceChange(true, false));
        assertFalse(ClientMultiworldService.shouldProbeAfterWorldReferenceChange(false, true));
    }

    @Test
    void resolvedVisitDoesNotRotateWhenLateSignalsFavorAnotherProfile() {
        final ClientWorldProfileRegistry registry = new ClientWorldProfileRegistry();
        final ClientWorldProfileResolver resolver = new ClientWorldProfileResolver(registry, ids());
        final ClientMultiworldService service = service(resolver);
        service.onGameJoin(11L);
        final WorldIdentity locked = service.resolve(ADDRESS).orElseThrow();
        final String serverId = locked.serverId();
        final ClientWorldProfile competing = resolver.resolve(
            serverId, observation(22L, Map.of("brand", "other"))
        ).profile();
        final Map<String, String> lateSignals = Map.of(
            "brand", "target", "commands", "target", "dimension", "target"
        );
        resolver.select(serverId, competing.id(), observation(11L, lateSignals));

        service.onRespawn(11L);
        service.observeSignals(lateSignals);

        assertEquals(locked.worldId(), service.resolve(ADDRESS).orElseThrow().worldId());
        assertEquals(ClientWorldDetectionState.STABLE, service.detectionState());
    }

    @Test
    void profileLockDoesNotLeakAcrossServerAddresses() {
        final ClientMultiworldService service = service(new ClientWorldProfileResolver(
            new ClientWorldProfileRegistry(), ids()
        ));
        service.onGameJoin(11L);
        final WorldIdentity first = service.resolve(ADDRESS).orElseThrow();

        final WorldIdentity second = service.resolve("other.example.net:25565").orElseThrow();

        assertNotEquals(first.serverId(), second.serverId());
    }

    @Test
    void explicitAndOmittedDefaultPortDoNotResetTheLogicalServerSession() {
        final ClientMultiworldService service = service(new ClientWorldProfileResolver(
            new ClientWorldProfileRegistry(), ids()
        ));
        service.onGameJoin(11L);
        final WorldIdentity explicit = service.resolve(ADDRESS).orElseThrow();

        final WorldIdentity omitted = service.resolve("proxy.example.net").orElseThrow();

        assertEquals(explicit, omitted);
        assertEquals(1, service.profiles().size());
        assertEquals(ClientWorldDetectionState.STABLE, service.detectionState());
    }

    @Test
    void defaultPortCanonicalIdentityRestoresLegacyTrajectoryCheckpoint() {
        final WorldIdentity identity = WorldIdentity.multiplayer(ADDRESS);
        final String legacyServerId = identity.legacyServerIds().get(0);
        final ClientWorldTrajectoryCheckpointIo checkpoints = new ClientWorldTrajectoryCheckpointIo(
            tempDir.resolve("trajectory-checkpoints"), LogManager.getLogger("test")
        );
        assertTrue(checkpoints.save(legacyServerId, OptionalLong.of(11L), trajectory()).saved());
        final ClientMultiworldService service = service(new ClientWorldProfileResolver(
            new ClientWorldProfileRegistry(), ids()
        ));

        service.onGameJoin(11L);
        final ClientWorldResolution resolved = service.resolveProfile(ADDRESS);

        assertEquals(ClientWorldResolution.State.RESOLVED, resolved.state());
        assertFalse(resolved.profile().visit("minecraft_overworld").trajectorySamples().isEmpty());
    }

    @Test
    void inferredSameSeedTransitionDoesNotRelockTheOnlyKnownProfile() {
        final ClientMultiworldService service = service(new ClientWorldProfileResolver(
            new ClientWorldProfileRegistry(), ids()
        ));
        service.onGameJoin(11L);
        final WorldIdentity first = service.resolve(ADDRESS).orElseThrow();

        service.observeInferredWorldTransition();
        service.observeSignals(signals("other-upstream"));

        assertTrue(service.resolve(ADDRESS).isEmpty());
        assertEquals(ClientWorldDetectionState.WAITING_FOR_USER, service.detectionState());
        assertEquals(first.worldId(), service.profiles().get(0).storageId());
        assertFalse(service.candidates().isEmpty());
        assertTrue(service.candidates().get(0).blockers().contains("same_seed_new_subworld_guard"));
    }

    @Test
    void aNewInferredBoundaryDropsSnapshotsFromThePreviousUnresolvedWorld() {
        final ClientMultiworldService service = service(new ClientWorldProfileResolver(
            new ClientWorldProfileRegistry(), ids()
        ));
        service.onGameJoin(11L);
        service.resolve(ADDRESS).orElseThrow();

        service.observeInferredWorldTransition();
        service.bufferSnapshot(snapshot(1, 1), MapLayer.SURFACE);
        service.observeInferredWorldTransition();
        service.bufferSnapshot(snapshot(2, 2), MapLayer.SURFACE);

        final List<ClientMultiworldService.PendingSnapshot> buffered = service.drainPendingSnapshots();
        assertEquals(1, buffered.size());
        assertEquals(2, buffered.get(0).snapshot().chunkX);
        assertEquals(2, buffered.get(0).snapshot().chunkZ);
    }

    @Test
    void chunkReplacementCountsOnlyCoordinatesSeenInBothWaves() {
        assertEquals(1, ClientMultiworldService.overlappingChunkCount(
            Set.of(1L, 2L, 3L), Set.of(3L, 4L, 5L)
        ));
        assertEquals(0, ClientMultiworldService.overlappingChunkCount(
            Set.of(1L, 2L), Set.of(3L, 4L)
        ));
    }

    @Test
    void provisionalBuffersCommitOnlyForTheSameConfirmedProfile() {
        final ClientWorldProfileResolver resolver = new ClientWorldProfileResolver(
            new ClientWorldProfileRegistry(), ids()
        );
        final ClientWorldProfile profileA = resolver.resolve(
            "example", observation(1L, signals("a"))
        ).profile();
        final ClientWorldProfile profileB = resolver.resolve(
            "example", observation(2L, signals("b"))
        ).profile();
        final ClientWorldResolution provisional = ClientWorldResolution.provisional(profileA);

        assertEquals(
            ClientMultiworldService.ProvisionalBufferAction.KEEP,
            ClientMultiworldService.provisionalBufferAction(
                provisional, ClientWorldResolution.provisional(profileA)
            )
        );
        assertEquals(
            ClientMultiworldService.ProvisionalBufferAction.COMMIT,
            ClientMultiworldService.provisionalBufferAction(
                provisional, ClientWorldResolution.resolved(profileA)
            )
        );
        assertEquals(
            ClientMultiworldService.ProvisionalBufferAction.DISCARD,
            ClientMultiworldService.provisionalBufferAction(
                provisional, ClientWorldResolution.resolved(profileB)
            )
        );
        assertEquals(
            ClientMultiworldService.ProvisionalBufferAction.DISCARD,
            ClientMultiworldService.provisionalBufferAction(
                provisional, ClientWorldResolution.ambiguous()
            )
        );
    }

    @Test
    void manualSelectionIsAvailableWhileTerrainEvidenceIsStillRetrying() {
        assertTrue(ClientMultiworldService.manualSelectionAvailable(
            ClientWorldDetectionState.PROBING, ClientWorldResolution.State.AMBIGUOUS, true
        ));
        assertFalse(ClientMultiworldService.manualSelectionAvailable(
            ClientWorldDetectionState.PROBING, ClientWorldResolution.State.COLLECTING, true
        ));
        assertTrue(ClientMultiworldService.manualSelectionAvailable(
            ClientWorldDetectionState.PROBING, ClientWorldResolution.State.AMBIGUOUS, false
        ));
    }

    @Test
    void stableVisitRefreshRequiresMeaningfulMovementOrTheMaximumInterval() {
        final ClientWorldObservation previous = visitObservation(0, 0);

        assertFalse(ClientMultiworldService.shouldRefreshVisit(
            previous, visitObservation(100, 100), 100L
        ));
        assertTrue(ClientMultiworldService.shouldRefreshVisit(
            previous, visitObservation(300, 0), 100L
        ));
        assertTrue(ClientMultiworldService.shouldRefreshVisit(
            previous, visitObservation(100, 100), 1_200L
        ));
    }

    @Test
    void stableVisitRefreshIgnoresVerticalOnlyMovement() {
        final ClientWorldObservation previous = new ClientWorldObservation(
            OptionalLong.of(11L),
            Map.of("brand", "stable"),
            "minecraft_overworld",
            "SURVIVAL",
            new ClientWorldPosition(0, -32, 0),
            null
        );
        final ClientWorldObservation current = new ClientWorldObservation(
            OptionalLong.of(11L),
            Map.of("brand", "stable"),
            "minecraft_overworld",
            "SURVIVAL",
            new ClientWorldPosition(0, 320, 0),
            null
        );

        assertFalse(ClientMultiworldService.shouldRefreshVisit(previous, current, 100L));
    }

    @Test
    void stableVisitRefreshUsesConfiguredDistanceAndInterval() {
        final ClientWorldObservation previous = visitObservation(0, 0);
        final ClientWorldPolicy policy = new ClientWorldPolicy(128, 64, 10, 20, 512);

        assertFalse(ClientMultiworldService.shouldRefreshVisit(
            previous, visitObservation(300, 0), 399L, policy
        ));
        assertTrue(ClientMultiworldService.shouldRefreshVisit(
            previous, visitObservation(512, 0), 399L, policy
        ));
        assertTrue(ClientMultiworldService.shouldRefreshVisit(
            previous, visitObservation(0, 0), 400L, policy
        ));
    }

    @Test
    void ordinaryDisconnectKeepsAnUncommittedTrajectoryCheckpoint() {
        final ClientWorldTrajectoryCheckpointIo checkpoints = checkpointIo();
        final String serverId = WorldIdentity.multiplayer(ADDRESS).serverId();
        assertTrue(checkpoints.save(serverId, OptionalLong.of(11L), trajectory()).saved());
        final ClientMultiworldService service = service(new ClientWorldProfileResolver(
            new ClientWorldProfileRegistry(), ids()
        ));
        service.onGameJoin(11L);
        assertEquals(ClientWorldResolution.State.RESOLVED, service.resolveProfile(ADDRESS).state());

        service.observeInferredWorldTransition();
        service.onConnectionClosed();

        assertTrue(checkpoints.load(serverId) != null);
    }

    @Test
    void sameConnectionGameJoinClearsTheDepartedWorldTrajectoryCheckpoint() {
        final ClientWorldTrajectoryCheckpointIo checkpoints = checkpointIo();
        final String serverId = WorldIdentity.multiplayer(ADDRESS).serverId();
        assertTrue(checkpoints.save(serverId, OptionalLong.of(11L), trajectory()).saved());
        final ClientMultiworldService service = service(new ClientWorldProfileResolver(
            new ClientWorldProfileRegistry(), ids()
        ));
        service.onGameJoin(11L);
        service.resolveProfile(ADDRESS);

        service.onGameJoin(11L);

        assertTrue(checkpoints.load(serverId) == null);
    }

    @Test
    void sameConnectionGameJoinRetainsCheckpointWhenDepartedProfilePersistenceFails() {
        final ClientWorldTrajectoryCheckpointIo checkpoints = checkpointIo();
        final String serverId = WorldIdentity.multiplayer(ADDRESS).serverId();
        assertTrue(checkpoints.save(serverId, OptionalLong.of(11L), trajectory()).saved());
        final AtomicBoolean failPersistence = new AtomicBoolean();
        final ClientWorldProfileResolver resolver = new ClientWorldProfileResolver(
            new ClientWorldProfileRegistry(), ids(), ignored -> failPersistence.get()
                ? ClientWorldProfileIo.SaveResult.failure("intentional failure")
                : ClientWorldProfileIo.SaveResult.success()
        );
        final ClientMultiworldService service = service(resolver);
        service.onGameJoin(11L);
        assertEquals(ClientWorldResolution.State.RESOLVED, service.resolveProfile(ADDRESS).state());
        failPersistence.set(true);

        service.onGameJoin(11L);

        assertTrue(checkpoints.load(serverId) != null);
        assertTrue(service.persistenceError() != null);
    }

    @Test
    void checkpointFromAnotherSeedIsDiscardedBeforeResolution() {
        final ClientWorldTrajectoryCheckpointIo checkpoints = checkpointIo();
        final String serverId = WorldIdentity.multiplayer(ADDRESS).serverId();
        assertTrue(checkpoints.save(serverId, OptionalLong.of(11L), trajectory()).saved());
        final ClientMultiworldService service = service(new ClientWorldProfileResolver(
            new ClientWorldProfileRegistry(), ids()
        ));
        service.onGameJoin(22L);

        service.resolveProfile(ADDRESS);

        assertTrue(checkpoints.load(serverId) == null);
    }

    @Test
    void restoredCheckpointAlwaysPrecedesTheNewConnectionGeneration() {
        assertEquals(2L, ClientMultiworldService.nextConnectionGenerationAfterCheckpoint(1L, 1L));
        assertEquals(8L, ClientMultiworldService.nextConnectionGenerationAfterCheckpoint(2L, 7L));
    }

    @Test
    void stableVisitCommitMovesRestoredTrajectoryIntoTheProfileAndClearsCheckpoint() {
        final ClientWorldTrajectoryCheckpointIo checkpoints = checkpointIo();
        final String serverId = WorldIdentity.multiplayer(ADDRESS).serverId();
        assertTrue(checkpoints.save(serverId, OptionalLong.of(11L), trajectory()).saved());
        final ClientMultiworldService service = service(new ClientWorldProfileResolver(
            new ClientWorldProfileRegistry(), ids()
        ));
        service.onGameJoin(11L);
        final ClientWorldResolution resolved = service.resolveProfile(ADDRESS);

        service.flushTrajectoryCheckpoint();

        assertTrue(checkpoints.load(serverId) == null);
        assertEquals(1, resolved.profile().visit("minecraft_overworld").trajectorySamples().size());
    }

    @Test
    void stableSessionPeriodicallyRecreatesAnIndependentTrajectoryCheckpoint() {
        final ClientWorldTrajectoryCheckpointIo checkpoints = checkpointIo();
        final String serverId = WorldIdentity.multiplayer(ADDRESS).serverId();
        assertTrue(checkpoints.save(serverId, OptionalLong.of(11L), trajectory()).saved());
        final ClientMultiworldService service = service(new ClientWorldProfileResolver(
            new ClientWorldProfileRegistry(), ids()
        ));
        service.onGameJoin(11L);
        assertEquals(ClientWorldResolution.State.RESOLVED, service.resolveProfile(ADDRESS).state());
        assertTrue(checkpoints.clear(serverId).saved());
        for (int tick = 0; tick < 100; tick++) {
            service.advanceDetectionClock();
        }

        service.persistTrajectoryCheckpointIfDue();

        assertTrue(checkpoints.load(serverId) != null);
    }

    @Test
    void stableVisitPersistenceRunsOffTheClientPathAndPublishesAfterTheWrite() {
        final ClientWorldTrajectoryCheckpointIo checkpoints = checkpointIo();
        final String serverId = WorldIdentity.multiplayer(ADDRESS).serverId();
        assertTrue(checkpoints.save(serverId, OptionalLong.of(11L), trajectory()).saved());
        final AtomicLong saves = new AtomicLong();
        final ClientWorldProfileResolver resolver = new ClientWorldProfileResolver(
            new ClientWorldProfileRegistry(), ids(), ignored -> {
                saves.incrementAndGet();
                return ClientWorldProfileIo.SaveResult.success();
            }
        );
        final ClientMultiworldService service = service(resolver);
        final QueueingExecutor io = new QueueingExecutor();
        service.bindTrajectoryIoExecutor(io);
        service.onGameJoin(11L);
        assertEquals(ClientWorldResolution.State.RESOLVED, service.resolveProfile(ADDRESS).state());
        final long savesBeforeFlush = saves.get();

        service.flushTrajectoryCheckpoint();

        assertEquals(savesBeforeFlush, saves.get());
        assertEquals(2, io.size());
        assertEquals(1, resolver.profiles(serverId).get(0).visit("minecraft_overworld")
            .trajectorySamples().size());

        io.runAll();
        service.advanceDetectionClock();

        assertEquals(savesBeforeFlush + 1L, saves.get());
    }

    @Test
    void departureCandidateTrajectoryIsWrittenOnlyByTheIoQueue() {
        final ClientWorldTrajectoryCheckpointIo checkpoints = checkpointIo();
        final String serverId = WorldIdentity.multiplayer(ADDRESS).serverId();
        assertTrue(checkpoints.save(serverId, OptionalLong.of(11L), trajectory()).saved());
        final ClientMultiworldService service = service(new ClientWorldProfileResolver(
            new ClientWorldProfileRegistry(), ids()
        ));
        service.onGameJoin(11L);
        final ClientWorldProfile profile = service.resolveProfile(ADDRESS).profile();
        final QueueingExecutor io = new QueueingExecutor();
        service.bindTrajectoryIoExecutor(io);

        service.onBeforeGameJoin();

        assertFalse(checkpoints.loadCandidates(serverId).containsKey(profile.id()));
        assertEquals(3, io.size());

        io.runNext();

        assertTrue(checkpoints.loadCandidates(serverId).containsKey(profile.id()));
    }

    @Test
    void deferredVisitCannotOverwriteANewerProfileMutation() {
        final AtomicLong saves = new AtomicLong();
        final ClientWorldProfileResolver resolver = new ClientWorldProfileResolver(
            new ClientWorldProfileRegistry(), ids(), ignored -> {
                saves.incrementAndGet();
                return ClientWorldProfileIo.SaveResult.success();
            }
        );
        final String serverId = WorldIdentity.multiplayer(ADDRESS).serverId();
        final ClientWorldProfile profile = resolver.createAndSelect(
            serverId, "Original", visitObservation(0, 0)
        ).profile();
        final ClientWorldProfileResolver.PreparedVisitMutation pending = resolver.prepareRememberVisit(
            serverId, profile.id(), visitObservation(512, 0)
        );
        final long savesBeforeRename = saves.get();

        assertTrue(resolver.rename(serverId, profile.id(), "Renamed").applied());
        assertFalse(resolver.persistPreparedVisit(pending).applied());
        assertFalse(resolver.publishPreparedVisit(pending).applied());

        assertEquals(savesBeforeRename + 1L, saves.get());
        assertEquals("Renamed", resolver.profiles(serverId).get(0).displayName());
    }

    @Test
    void checkpointWriteFailureDoesNotQuarantineOrFailTheProfileRegistry() throws Exception {
        final ClientWorldTrajectoryCheckpointIo checkpoints = checkpointIo();
        final String serverId = WorldIdentity.multiplayer(ADDRESS).serverId();
        assertTrue(checkpoints.save(serverId, OptionalLong.of(11L), trajectory()).saved());
        final ClientWorldProfileResolver resolver = new ClientWorldProfileResolver(
            new ClientWorldProfileRegistry(), ids()
        );
        final ClientMultiworldService service = service(resolver);
        service.onGameJoin(11L);
        assertEquals(ClientWorldResolution.State.RESOLVED, service.resolveProfile(ADDRESS).state());
        final ClientWorldProfile target = resolver.resolve(
            serverId, observation(22L, signals("checkpoint-target"))
        ).profile();
        resolver.addSwitchCommand(serverId, target.id(), "/server checkpoint-target", false);
        assertTrue(service.onChatSubmitted("/server checkpoint-target"));
        final Path checkpointRoot = tempDir.resolve("trajectory-checkpoints");
        try (var children = Files.list(checkpointRoot)) {
            for (final Path child : children.toList()) {
                Files.delete(child);
            }
        }
        Files.delete(checkpointRoot);
        Files.writeString(checkpointRoot, "not a directory");

        service.flushTrajectoryCheckpoint();

        assertTrue(service.trajectoryCheckpointError() != null);
        assertTrue(service.profileRegistryAvailable());
        assertTrue(service.persistenceError() == null);
    }

    @Test
    void failClosedRegistryCanBeReloadedAfterUserRestoresFile() throws Exception {
        final Path registryFile = tempDir.resolve("client_worlds.json");
        final ClientWorldProfileIo io = new ClientWorldProfileIo(
            registryFile, LogManager.getLogger("ClientMultiworldServiceTest")
        );
        Files.writeString(registryFile, "{not json");
        final ClientWorldProfileResolver resolver = new ClientWorldProfileResolver(
            io.load(), ids()
        );
        final ClientMultiworldService service = service(resolver);
        service.bindProfileRegistryLoader(io::load);

        assertFalse(service.profileRegistryAvailable());
        Files.writeString(registryFile, "{\"schemaVersion\":2,\"servers\":{}}");
        assertTrue(service.retryProfileRegistryLoad().applied());
        assertTrue(service.profileRegistryAvailable());
    }

    @Test
    void registryReloadRecoversInterruptedDeletionForTheExistingConnection() throws Exception {
        final String serverId = WorldIdentity.multiplayer(ADDRESS).serverId();
        final ClientWorldProfileRegistry restoredRegistry = new ClientWorldProfileRegistry();
        final ClientWorldProfileResolver restoredResolver = new ClientWorldProfileResolver(restoredRegistry, ids());
        final ClientWorldProfile profile = restoredResolver.resolve(
            serverId, observation(11L, signals("recovery"))
        ).profile();
        final Path mapRoot = tempDir.resolve("cache");
        final Path mapData = mapRoot.resolve(profile.storageServerId(serverId)).resolve(profile.storageId());
        Files.createDirectories(mapData);
        final Path marker = mapData.resolve("marker.dat");
        Files.writeString(marker, "recover me");

        final ClientWorldProfileDeletionService deletion = new ClientWorldProfileDeletionService(
            mapRoot,
            tempDir.resolve("waypoints"),
            tempDir.resolve("annotations"),
            tempDir.resolve("recovery").resolve("client-worlds")
        );
        final ClientWorldProfileDeletionService.Transaction transaction = deletion.moveToRecovery(
            serverId, profile
        );
        assertTrue(transaction.prepared());
        assertFalse(Files.exists(marker));

        final Path registryFile = tempDir.resolve("reload-client-worlds.json");
        Files.writeString(registryFile, "{not json");
        final ClientWorldProfileIo io = new ClientWorldProfileIo(
            registryFile, LogManager.getLogger("ClientMultiworldServiceTest")
        );
        final ClientMultiworldService service = service(new ClientWorldProfileResolver(io.load(), ids()));
        service.onGameJoin(11L);
        service.resolveProfile(ADDRESS);
        service.bindProfileRegistryLoader(() -> restoredRegistry);

        assertFalse(service.profileRegistryAvailable());
        assertTrue(service.retryProfileRegistryLoad().applied());
        assertEquals(profile.id(), service.profiles().get(0).id());
        assertEquals("recover me", Files.readString(marker));
    }

    @Test
    void invalidProfileManagementInputReturnsVisibleFailuresInsteadOfThrowing() {
        final ClientWorldProfileResolver resolver = new ClientWorldProfileResolver(
            new ClientWorldProfileRegistry(), ids()
        );
        final ClientMultiworldService service = service(resolver);
        service.onGameJoin(11L);
        final ClientWorldProfile profile = service.resolveProfile(ADDRESS).profile();

        assertFalse(service.createAndSelect("   ").applied());
        assertTrue(service.persistenceError().contains("name"));
        assertFalse(service.createAndSelect("x".repeat(129)).applied());
        assertTrue(service.persistenceError().contains("128"));
        assertFalse(service.rename(profile.id(), "   ").applied());
        assertTrue(service.persistenceError().contains("name"));
        final ClientWorldProfileResolver.CommandBindingResult command = service.addSwitchCommand(
            profile.id(), "not a command", false
        );
        assertEquals(ClientWorldProfileResolver.CommandBindingResult.Status.PERSISTENCE_FAILED, command.status());
        assertTrue(command.mutation().error().contains("command"));
        final ClientWorldProfileResolver.CommandBindingResult oversizedCommand = service.addSwitchCommand(
            profile.id(), "/" + "x".repeat(256), false
        );
        assertEquals(
            ClientWorldProfileResolver.CommandBindingResult.Status.PERSISTENCE_FAILED,
            oversizedCommand.status()
        );
        assertTrue(oversizedCommand.mutation().error().contains("256"));
        assertFalse(service.removeSwitchCommand("missing-profile", "/server hub").applied());
        assertTrue(service.persistenceError().contains("unknown client world profile"));
        assertFalse(service.delete("missing-profile").deleted());
        assertTrue(service.persistenceError().contains("unknown client world profile"));
    }

    @Test
    void observationAndRegistryInputLimitsRejectOversizedData() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new ClientWorldObservation(
            OptionalLong.of(11L), Map.of("brand", "x".repeat(257))
        ));
        final Path registryFile = tempDir.resolve("oversized-client-worlds.json");
        Files.write(registryFile, new byte[4 * 1024 * 1024 + 1]);

        final ClientWorldProfileRegistry loaded = new ClientWorldProfileIo(
            registryFile, LogManager.getLogger("ClientMultiworldServiceTest")
        ).load();

        assertFalse(loaded.available());
        assertTrue(Files.exists(registryFile.resolveSibling("oversized-client-worlds.json.blocked")));
    }

    @Test
    void recoveryJournalForAnotherServerNamespaceIsLeftUntouched() throws Exception {
        final String serverId = WorldIdentity.multiplayer(ADDRESS).serverId();
        final ClientWorldProfileResolver resolver = new ClientWorldProfileResolver(
            new ClientWorldProfileRegistry(), ids()
        );
        final ClientMultiworldService service = service(resolver);
        service.onGameJoin(11L);
        final ClientWorldProfile profile = service.resolveProfile(ADDRESS).profile();
        final Path mapData = tempDir.resolve("cache")
            .resolve(profile.storageServerId(serverId)).resolve(profile.storageId());
        Files.createDirectories(mapData);
        final Path marker = mapData.resolve("marker.dat");
        Files.writeString(marker, "do not restore from foreign journal");
        final ClientWorldProfileDeletionService deletion = new ClientWorldProfileDeletionService(
            tempDir.resolve("cache"), tempDir.resolve("waypoints"), tempDir.resolve("annotations"),
            tempDir.resolve("recovery").resolve("client-worlds")
        );
        assertTrue(deletion.moveToRecovery(serverId, profile).prepared());
        final Path journal;
        try (var paths = Files.walk(tempDir.resolve("recovery").resolve("client-worlds").resolve(serverId))) {
            journal = paths.filter(path -> path.getFileName().toString().equals("transaction.properties"))
                .findFirst().orElseThrow();
        }
        Files.writeString(journal, Files.readString(journal).replace(
            "serverId=" + serverId, "serverId=foreign.example"
        ));

        service.onGameJoin(11L);
        service.resolveProfile(ADDRESS);

        assertFalse(Files.exists(marker));
        assertTrue(Files.exists(journal));
    }

    @Test
    void submittedSwitchCommandActivatesItsProfileWithoutChangingTheTextOrLearningOldSignals() {
        final ClientWorldProfileRegistry registry = new ClientWorldProfileRegistry();
        final ClientWorldProfileResolver resolver = new ClientWorldProfileResolver(registry, ids());
        final ClientMultiworldService service = service(resolver);
        service.onGameJoin(11L);
        final WorldIdentity first = service.resolve(ADDRESS).orElseThrow();
        final ClientWorldProfile target = resolver.resolve(
            first.serverId(), observation(22L, signals("creative"))
        ).profile();
        resolver.addSwitchCommand(first.serverId(), target.id(), "/server creative", false);
        final int bindingsBefore = target.bindingCount();
        final String submitted = "/SERVER    CREATIVE";
        final String rawBefore = submitted;

        assertTrue(service.onChatSubmitted(submitted));
        assertEquals(rawBefore, submitted);
        assertTrue(service.resolve(ADDRESS).isEmpty());
        assertEquals(bindingsBefore, target.bindingCount());
        service.onGameJoin(22L);
        service.observeSignals(signals("creative"));

        assertEquals(target.storageId(), service.resolve(ADDRESS).orElseThrow().worldId());
        assertEquals(bindingsBefore, target.bindingCount());
        assertFalse(service.onChatSubmitted("/server creative now"));
        assertFalse(service.onChatSubmitted(" /server creative"));
    }

    @Test
    void submittedSwitchCommandFlushesTheDepartedProfilesLocalTrajectoryFirst() {
        final ClientWorldTrajectoryCheckpointIo checkpoints = checkpointIo();
        final String serverId = WorldIdentity.multiplayer(ADDRESS).serverId();
        assertTrue(checkpoints.save(serverId, OptionalLong.of(11L), trajectory()).saved());
        final ClientWorldProfileRegistry registry = new ClientWorldProfileRegistry();
        final ClientWorldProfileResolver resolver = new ClientWorldProfileResolver(registry, ids());
        final ClientMultiworldService service = service(resolver);
        service.onGameJoin(11L);
        final ClientWorldProfile departed = service.resolveProfile(ADDRESS).profile();
        final ClientWorldProfile target = resolver.resolve(
            serverId, observation(22L, signals("command-target"))
        ).profile();
        resolver.addSwitchCommand(serverId, target.id(), "/server command-target", false);

        assertTrue(service.onChatSubmitted("/server command-target"));

        assertEquals(1, departed.visit("minecraft_overworld").trajectorySamples().size());
        assertTrue(checkpoints.load(serverId) == null);
    }

    @Test
    void commandWaitsThroughRespawnUntilAnActualGameJoinConfirmsTheTarget() {
        final ClientWorldProfileRegistry registry = new ClientWorldProfileRegistry();
        final ClientWorldProfileResolver resolver = new ClientWorldProfileResolver(registry, ids());
        final ClientMultiworldService service = service(resolver);
        service.onGameJoin(11L);
        service.observeSignals(signals("survival"));
        final WorldIdentity first = service.resolve(ADDRESS).orElseThrow();
        final ClientWorldProfile target = resolver.resolve(
            first.serverId(), observation(22L, signals("creative"))
        ).profile();
        resolver.addSwitchCommand(first.serverId(), target.id(), "/server creative", false);
        final int targetBindings = target.bindingCount();

        assertTrue(service.onChatSubmitted("/server creative"));
        service.onRespawn(11L);
        service.observeSignals(signals("survival"));

        assertTrue(service.resolve(ADDRESS).isEmpty());
        assertEquals(targetBindings, target.bindingCount());

        service.onGameJoin(22L);
        service.observeSignals(signals("creative"));

        assertEquals(target.storageId(), service.resolve(ADDRESS).orElseThrow().worldId());
        assertEquals(targetBindings, target.bindingCount());
    }

    @Test
    void weakWorldReplacementNeedsASeedOrIdentitySignalChange() {
        final Map<String, String> before = Map.of(
            "brand", "same",
            "commands", "same",
            "dimension", "overworld"
        );
        final Map<String, String> changedDimension = Map.of(
            "brand", "same",
            "commands", "same",
            "dimension", "the_nether"
        );
        final Map<String, String> changedCommands = Map.of(
            "brand", "same",
            "commands", "different",
            "dimension", "overworld"
        );

        assertFalse(ClientMultiworldService.commandIdentityChanged(
            OptionalLong.of(11L), before, OptionalLong.of(11L), changedDimension
        ));
        assertTrue(ClientMultiworldService.commandIdentityChanged(
            OptionalLong.of(11L), before, OptionalLong.of(11L), changedCommands
        ));
        assertTrue(ClientMultiworldService.commandIdentityChanged(
            OptionalLong.of(11L), before, OptionalLong.of(22L), before
        ));
    }

    @Test
    void commandDoesNotBindAReusedSeedWhenTargetSignalsContradictIt() {
        final ClientWorldProfileRegistry registry = new ClientWorldProfileRegistry();
        final ClientWorldProfileResolver resolver = new ClientWorldProfileResolver(registry, ids());
        final ClientMultiworldService service = service(resolver);
        service.onGameJoin(11L);
        service.observeSignals(signals("survival"));
        final WorldIdentity first = service.resolve(ADDRESS).orElseThrow();
        final ClientWorldProfile target = resolver.createAndSelect(
            first.serverId(), "Creative", observation(11L, signals("creative"))
        ).profile();
        resolver.addSwitchCommand(first.serverId(), target.id(), "/server creative", false);
        final int targetBindings = target.bindingCount();

        assertTrue(service.onChatSubmitted("/server creative"));
        service.onGameJoin(11L);
        service.bufferSnapshot(snapshot(7, 7), MapLayer.SURFACE);
        service.observeSignals(signals("survival"));

        assertEquals(ClientWorldResolution.State.AMBIGUOUS, service.resolveProfile(ADDRESS).state());
        assertEquals(targetBindings, target.bindingCount());
        assertTrue(service.resolve(ADDRESS).isEmpty());
        assertTrue(service.drainPendingSnapshots().isEmpty());
    }

    @Test
    void commandLockSuspendsTheSessionOnlyAfterAnExplicitTargetSeedConflict() {
        final ClientWorldProfileRegistry registry = new ClientWorldProfileRegistry();
        final ClientWorldProfileResolver resolver = new ClientWorldProfileResolver(registry, ids());
        final ClientMultiworldService service = service(resolver);
        service.onGameJoin(11L);
        final WorldIdentity first = service.resolve(ADDRESS).orElseThrow();
        final ClientWorldProfile target = resolver.resolve(
            first.serverId(), observation(22L, Map.of("brand", "creative"))
        ).profile();
        resolver.addSwitchCommand(first.serverId(), target.id(), "/server creative", false);

        assertTrue(service.onChatSubmitted("/server creative"));
        assertTrue(service.resolve(ADDRESS).isEmpty());
        service.onGameJoin(33L);

        assertEquals(ClientWorldResolution.State.AMBIGUOUS, service.resolveProfile(ADDRESS).state());
        assertTrue(service.resolve(ADDRESS).isEmpty());
    }

    @Test
    void commandSelectionLearnsTheNewWorldButIncompleteRevisitStillWaits() {
        final ClientWorldProfileRegistry registry = new ClientWorldProfileRegistry();
        final ClientWorldProfileResolver resolver = new ClientWorldProfileResolver(registry, ids());
        final ClientMultiworldService service = service(resolver);
        service.onGameJoin(11L);
        final WorldIdentity first = service.resolve(ADDRESS).orElseThrow();
        final ClientWorldProfile target = resolver.resolve(
            first.serverId(), observation(22L, Map.of("brand", "creative"))
        ).profile();
        resolver.clearBindings(first.serverId(), target.id());
        resolver.addSwitchCommand(first.serverId(), target.id(), "/server creative", false);

        assertTrue(service.onChatSubmitted("/server creative"));
        service.onGameJoin(22L);
        service.resolveProfile(ADDRESS);
        service.observeSignals(signals("creative"));

        assertEquals(target.storageId(), service.resolve(ADDRESS).orElseThrow().worldId());
        assertEquals(1, target.bindingCount());
        resolver.removeSwitchCommand(first.serverId(), target.id(), "/server creative");
        final ClientMultiworldService nextVisit = service(resolver);
        nextVisit.onGameJoin(22L);
        nextVisit.observeSignals(signals("creative"));

        assertTrue(nextVisit.resolve(ADDRESS).isEmpty());
        assertEquals(1, target.bindingCount());
    }

    @Test
    void unconfirmedSwitchCommandRestoresThePreviousProfileAfterTenSeconds() {
        final ClientWorldProfileRegistry registry = new ClientWorldProfileRegistry();
        final ClientWorldProfileResolver resolver = new ClientWorldProfileResolver(registry, ids());
        final ClientMultiworldService service = service(resolver);
        service.onGameJoin(11L);
        final WorldIdentity first = service.resolve(ADDRESS).orElseThrow();
        final ClientWorldProfile target = resolver.resolve(
            first.serverId(), observation(22L, signals("creative"))
        ).profile();
        resolver.addSwitchCommand(first.serverId(), target.id(), "/server creative", false);

        assertTrue(service.onChatSubmitted("/server creative"));
        assertTrue(service.resolve(ADDRESS).isEmpty());
        service.onRespawn(11L);
        service.bufferSnapshot(snapshot(8, 8), MapLayer.SURFACE);
        service.observeSignals(signals("survival"));
        assertTrue(service.resolve(ADDRESS).isEmpty());
        for (int tick = 0; tick < 200; tick++) {
            service.advanceDetectionClock();
        }

        assertEquals(first, service.resolve(ADDRESS).orElseThrow());
        assertTrue(service.drainPendingSnapshots().isEmpty());
    }

    @Test
    void weakSameSeedTransitionDoesNotRestoreThePreviousProfileAfterCommandTimeout() {
        final ClientWorldProfileRegistry registry = new ClientWorldProfileRegistry();
        final ClientWorldProfileResolver resolver = new ClientWorldProfileResolver(registry, ids());
        final ClientMultiworldService service = service(resolver);
        service.onGameJoin(11L);
        service.observeSignals(signals("same"));
        final WorldIdentity first = service.resolve(ADDRESS).orElseThrow();
        final ClientWorldProfile target = resolver.createAndSelect(
            first.serverId(), "Same signals target", observation(11L, signals("same"))
        ).profile();
        assertNotEquals(first.worldId(), target.storageId());
        resolver.addSwitchCommand(first.serverId(), target.id(), "/server same", false);

        assertTrue(service.onChatSubmitted("/server same"));
        service.observeInferredWorldTransition();
        service.observeSignals(signals("same"));
        for (int tick = 0; tick < 200; tick++) {
            service.advanceDetectionClock();
        }

        assertEquals(ClientWorldDetectionState.WAITING_FOR_USER, service.detectionState());
        assertTrue(service.candidates().stream()
            .anyMatch(candidate -> candidate.blockers().contains("command_timeout_weak_transition")));
        assertTrue(service.resolve(ADDRESS).isEmpty());
        assertTrue(service.drainPendingSnapshots().isEmpty());
    }

    @Test
    void configuredCommandTimeoutControlsWhenThePreviousProfileIsRestored() {
        final ClientWorldProfileRegistry registry = new ClientWorldProfileRegistry();
        final ClientWorldProfileResolver resolver = new ClientWorldProfileResolver(registry, ids());
        final ClientMultiworldService service = new ClientMultiworldService(
            null,
            new CompanionSession(),
            resolver,
            tempDir.resolve("cache"),
            () -> new ClientWorldPolicy(128, 64, 5, 60, 256)
        );
        service.onGameJoin(11L);
        final WorldIdentity first = service.resolve(ADDRESS).orElseThrow();
        final ClientWorldProfile target = resolver.resolve(
            first.serverId(), observation(22L, signals("creative"))
        ).profile();
        resolver.addSwitchCommand(first.serverId(), target.id(), "/server creative", false);

        assertTrue(service.onChatSubmitted("/server creative"));
        for (int tick = 0; tick < 99; tick++) {
            service.advanceDetectionClock();
        }
        assertTrue(service.resolve(ADDRESS).isEmpty());
        service.advanceDetectionClock();

        assertEquals(first, service.resolve(ADDRESS).orElseThrow());
    }

    private ClientMultiworldService service(final ClientWorldProfileResolver resolver) {
        return new ClientMultiworldService(
            null, new CompanionSession(), resolver, tempDir.resolve("cache")
        );
    }

    private ClientWorldTrajectoryCheckpointIo checkpointIo() {
        return new ClientWorldTrajectoryCheckpointIo(
            tempDir.resolve("trajectory-checkpoints"),
            LogManager.getLogger("ClientMultiworldServiceTest")
        );
    }

    private static ClientWorldTrajectory trajectory() {
        final ClientWorldTrajectory trajectory = new ClientWorldTrajectory();
        trajectory.append(ClientWorldTrajectorySample.observed(
            128.0D, 64.0D, -32.0D,
            12.0D, 0.0D,
            -90.0D, 0.0D,
            1_000L, 20L,
            "minecraft_overworld", 20L,
            ClientWorldTrajectorySample.NO_SERVER_ACK, 1L
        ));
        return trajectory;
    }

    private static ClientWorldObservation observation(final long seed, final Map<String, String> signals) {
        return new ClientWorldObservation(OptionalLong.of(seed), signals);
    }

    private static Map<String, String> signals(final String suffix) {
        return Map.of(
            "brand", "brand-" + suffix,
            "commands", "commands-" + suffix,
            "dimension", "dimension-" + suffix
        );
    }

    private static ChunkSnapshot snapshot(final int chunkX, final int chunkZ) {
        return new ChunkSnapshot(
            chunkX,
            chunkZ,
            0L,
            new short[ChunkSnapshot.COLUMNS],
            new String[ChunkSnapshot.COLUMNS],
            new byte[ChunkSnapshot.COLUMNS],
            new int[ChunkSnapshot.COLUMNS],
            new int[ChunkSnapshot.COLUMNS],
            new int[ChunkSnapshot.COLUMNS],
            new byte[ChunkSnapshot.COLUMNS],
            new byte[ChunkSnapshot.COLUMNS]
        );
    }

    private static ClientWorldObservation visitObservation(final int x, final int z) {
        return new ClientWorldObservation(
            OptionalLong.of(11L),
            Map.of("brand", "stable"),
            "minecraft_overworld",
            "SURVIVAL",
            new ClientWorldPosition(x, 64, z),
            null
        );
    }

    private static final class QueueingExecutor implements Executor {
        private final Deque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(final Runnable task) {
            tasks.addLast(task);
        }

        int size() {
            return tasks.size();
        }

        void runAll() {
            while (!tasks.isEmpty()) {
                tasks.removeFirst().run();
            }
        }

        void runNext() {
            tasks.removeFirst().run();
        }
    }

    private static java.util.function.Supplier<UUID> ids() {
        final AtomicLong value = new AtomicLong(1L);
        return () -> new UUID(0L, value.getAndIncrement());
    }
}
