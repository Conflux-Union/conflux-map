package cn.net.rms.confluxmap.mc.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldObservation;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldPolicy;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldDetectionState;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldPosition;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldProfile;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldProfileIo;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldProfileRegistry;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldProfileResolver;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldResolution;
import cn.net.rms.confluxmap.mc.net.CompanionSession;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
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
        service.observeSignals(signals("survival"));

        assertEquals(ClientWorldResolution.State.AMBIGUOUS, service.resolveProfile(ADDRESS).state());
        assertEquals(targetBindings, target.bindingCount());
        assertTrue(service.resolve(ADDRESS).isEmpty());
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
    void commandSelectionLearnsTheNewWorldBeforeTheCommandIsRemoved() {
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

        assertEquals(target.storageId(), nextVisit.resolve(ADDRESS).orElseThrow().worldId());
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
        service.observeSignals(signals("survival"));
        assertTrue(service.resolve(ADDRESS).isEmpty());
        for (int tick = 0; tick < 200; tick++) {
            service.advanceDetectionClock();
        }

        assertEquals(first, service.resolve(ADDRESS).orElseThrow());
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

    private static java.util.function.Supplier<UUID> ids() {
        final AtomicLong value = new AtomicLong(1L);
        return () -> new UUID(0L, value.getAndIncrement());
    }
}
