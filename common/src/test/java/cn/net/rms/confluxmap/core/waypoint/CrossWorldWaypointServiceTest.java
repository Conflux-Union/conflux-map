package cn.net.rms.confluxmap.core.waypoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldObservation;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldProfileRegistry;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldProfileResolver;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CrossWorldWaypointServiceTest {
    private static final Logger LOGGER = LogManager.getLogger("CrossWorldWaypointServiceTest");
    private static final String SERVER = "play.example.net";

    @Test
    void recordsSeedObservationsAndSavesOnlyWhenTheyChange(@TempDir final Path tempDir) {
        final ConfluxConfig config = new ConfluxConfig();
        final int[] saves = {0};
        final CrossWorldWaypointService service = service(tempDir, new ClientWorldProfileRegistry(), config, saves);
        final WorldIdentity world = WorldIdentity.multiplayer(SERVER, "client-a");

        service.update(world, OptionalLong.of(11L));
        assertEquals(11L, config.crossWorldSeedObservations.observations(SERVER).get("client-a").seedHash());
        assertEquals(1, saves[0]);
        service.update(world, OptionalLong.of(11L));
        assertEquals(1, saves[0]);
        service.update(world, OptionalLong.of(12L));
        assertEquals(2, saves[0]);
        assertEquals(12L, config.crossWorldSeedObservations.observations(SERVER).get("client-a").seedHash());
    }

    @Test
    void loadsWaypointsFromSameSeedObservationSiblings(@TempDir final Path tempDir) {
        final ConfluxConfig config = new ConfluxConfig();
        final CrossWorldWaypointService service = service(tempDir, new ClientWorldProfileRegistry(), config, new int[1]);
        saveWaypoints(tempDir, "client-b", waypoint("Copper mine"), deathWaypoint("Death"));
        saveWaypoints(tempDir, "client-c", waypoint("Different seed home"));

        service.update(WorldIdentity.multiplayer(SERVER, "client-b"), OptionalLong.of(11L));
        service.update(WorldIdentity.multiplayer(SERVER, "client-c"), OptionalLong.of(99L));
        service.update(WorldIdentity.multiplayer(SERVER, "client-a"), OptionalLong.of(11L));

        final List<SiblingWaypoint> siblings = service.siblings();
        assertEquals(List.of("Copper mine"), siblings.stream()
            .map(sibling -> sibling.waypoint().name)
            .toList());
        assertEquals("client-b", siblings.get(0).worldLabel());
    }

    @Test
    void excludesCurrentAndLegacyNamespaces(@TempDir final Path tempDir) {
        final ConfluxConfig config = new ConfluxConfig();
        final CrossWorldWaypointService service = service(tempDir, new ClientWorldProfileRegistry(), config, new int[1]);
        // inst-1 adopted the uuid-1 and world namespaces when its companion first advertised it.
        final WorldIdentity current = WorldIdentity.companionMultiplayer(SERVER, "inst-1", "uuid-1");
        saveWaypoints(tempDir, "inst-1", waypoint("Own home"));
        saveWaypoints(tempDir, "uuid-1", waypoint("Legacy copy"));
        saveWaypoints(tempDir, "world", waypoint("Address-wide copy"));
        saveWaypoints(tempDir, "inst-2", waypoint("Sibling home"));

        for (final String namespace : new String[] {"inst-1", "uuid-1", "world", "inst-2"}) {
            service.update(WorldIdentity.multiplayer(SERVER, namespace), OptionalLong.of(11L));
        }
        service.update(current, OptionalLong.of(11L));

        assertEquals(List.of("Sibling home"), service.siblings().stream()
            .map(sibling -> sibling.waypoint().name)
            .toList());
        assertEquals("inst-2", service.siblings().get(0).worldLabel());
    }

    @Test
    void matchesClientProfilesBySeedAndLabelsThemWithTheirDisplayName(@TempDir final Path tempDir) {
        final ClientWorldProfileRegistry registry = new ClientWorldProfileRegistry();
        final ClientWorldProfileResolver resolver =
            new ClientWorldProfileResolver(registry, () -> java.util.UUID.randomUUID());
        final String sameSeedStorage = resolver.resolve(
            SERVER, observation(11L)
        ).profile().storageId();
        final String otherSeedStorage = resolver.resolve(
            SERVER, observation(99L)
        ).profile().storageId();
        final ConfluxConfig config = new ConfluxConfig();
        final CrossWorldWaypointService service = service(tempDir, registry, config, new int[1]);
        saveWaypoints(tempDir, sameSeedStorage, waypoint("Same seed point"));
        saveWaypoints(tempDir, otherSeedStorage, waypoint("Other seed point"));

        service.update(WorldIdentity.multiplayer(SERVER, "inst-1"), OptionalLong.of(11L));

        assertEquals(List.of("Same seed point"), service.siblings().stream()
            .map(sibling -> sibling.waypoint().name)
            .toList());
        assertTrue(service.siblings().get(0).worldLabel().startsWith("World "));
    }

    @Test
    void prefersServerWorldNamesAndTruncatesUnnamedLongIds(@TempDir final Path tempDir) {
        final ClientWorldProfileRegistry registry = new ClientWorldProfileRegistry();
        registry.nameServerWorld(SERVER, "1234567890abcdef", "资源服");
        final ConfluxConfig config = new ConfluxConfig();
        final CrossWorldWaypointService service = service(tempDir, registry, config, new int[1]);
        saveWaypoints(tempDir, "1234567890abcdef", waypoint("Named point"));
        saveWaypoints(tempDir, "ffffffffffffffff", waypoint("Unnamed point"));

        service.update(WorldIdentity.multiplayer(SERVER, "1234567890abcdef"), OptionalLong.of(11L));
        service.update(WorldIdentity.multiplayer(SERVER, "ffffffffffffffff"), OptionalLong.of(11L));
        service.update(WorldIdentity.multiplayer(SERVER, "inst-1"), OptionalLong.of(11L));

        // ASCII sorts before CJK, so the unnamed truncated id leads.
        assertEquals(
            List.of("ffffffff", "资源服"),
            service.siblings().stream().map(SiblingWaypoint::worldLabel).toList()
        );
    }

    @Test
    void resolvesCompanionWorldNamesThroughTheLegacyWorldUuid(@TempDir final Path tempDir) {
        final ClientWorldProfileRegistry registry = new ClientWorldProfileRegistry();
        // The player named the companion world by its world UUID; storage keys on the instance id.
        registry.nameServerWorld(SERVER, "uuid-1111-2222", "矿洞服");
        final ConfluxConfig config = new ConfluxConfig();
        final CrossWorldWaypointService service = service(tempDir, registry, config, new int[1]);
        saveWaypoints(tempDir, "inst-9", waypoint("Companion point"));

        service.update(
            WorldIdentity.companionMultiplayer(SERVER, "inst-9", "uuid-1111-2222"),
            OptionalLong.of(11L)
        );
        service.update(WorldIdentity.multiplayer(SERVER, "client-a"), OptionalLong.of(11L));

        assertEquals(
            List.of("矿洞服"),
            service.siblings().stream().map(SiblingWaypoint::worldLabel).toList()
        );
    }

    @Test
    void ignoresSingleplayerSessionsAndUnknownSeeds(@TempDir final Path tempDir) {
        final ConfluxConfig config = new ConfluxConfig();
        final CrossWorldWaypointService service = service(tempDir, new ClientWorldProfileRegistry(), config, new int[1]);

        service.update(WorldIdentity.singleplayer("New World"), OptionalLong.of(11L));
        service.update(WorldIdentity.multiplayer(SERVER, "client-a"), OptionalLong.empty());
        service.update(null, OptionalLong.of(11L));

        assertTrue(service.siblings().isEmpty());
        assertTrue(config.crossWorldSeedObservations.observations(SERVER).isEmpty());
    }

    @Test
    void missingSiblingFilesSimplyYieldNoWaypoints(@TempDir final Path tempDir) {
        final ConfluxConfig config = new ConfluxConfig();
        final CrossWorldWaypointService service = service(tempDir, new ClientWorldProfileRegistry(), config, new int[1]);

        service.update(WorldIdentity.multiplayer(SERVER, "client-b"), OptionalLong.of(11L));
        service.update(WorldIdentity.multiplayer(SERVER, "client-a"), OptionalLong.of(11L));

        assertTrue(service.siblings().isEmpty());
    }

    private static CrossWorldWaypointService service(
        final Path tempDir,
        final ClientWorldProfileRegistry registry,
        final ConfluxConfig config,
        final int[] saves
    ) {
        return new CrossWorldWaypointService(
            tempDir, registry, config, () -> saves[0]++, LOGGER
        );
    }

    private static ClientWorldObservation observation(final long seedHash) {
        return new ClientWorldObservation(OptionalLong.of(seedHash), Map.of());
    }

    private static void saveWaypoints(
        final Path root,
        final String worldId,
        final Waypoint... waypoints
    ) {
        WaypointIo.save(
            root.resolve(SERVER).resolve(worldId + ".json"),
            new WaypointStore.State(List.of(), List.of(waypoints)),
            LOGGER
        );
    }

    private static Waypoint waypoint(final String name) {
        return Waypoint.create(name, DimensionId.OVERWORLD, 1.0, 64.0, 2.0, 0xFF22AA44, "", Waypoint.Type.NORMAL);
    }

    private static Waypoint deathWaypoint(final String name) {
        return Waypoint.create(name, DimensionId.OVERWORLD, 9.0, 30.0, 9.0, 0xFFAA2222, "", Waypoint.Type.DEATH);
    }
}
