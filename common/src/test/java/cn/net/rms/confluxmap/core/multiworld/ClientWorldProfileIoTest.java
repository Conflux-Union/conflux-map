package cn.net.rms.confluxmap.core.multiworld;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
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
    void namesGivenToCompanionOwnedWorldsSurviveARestart() {
        final Path file = tempDir.resolve("client_worlds.json");
        final ClientWorldProfileIo io = new ClientWorldProfileIo(
            file, LogManager.getLogger("ClientWorldProfileIoTest")
        );
        final ClientWorldProfileRegistry registry = new ClientWorldProfileRegistry();
        final ClientWorldProfileResolver resolver = new ClientWorldProfileResolver(
            registry, UUID::randomUUID
        );
        resolver.nameServerWorld("example.net", "world-uuid-1", "  Survival  ");
        resolver.nameServerWorld("example.net", "world-uuid-2", "Creative");

        io.save(registry);
        final ClientWorldProfileRegistry loaded = io.load();

        assertEquals("Survival", loaded.serverWorldName("example.net", "world-uuid-1").orElseThrow());
        assertEquals("Creative", loaded.serverWorldName("example.net", "world-uuid-2").orElseThrow());
        assertTrue(loaded.serverWorldName("other.net", "world-uuid-1").isEmpty());
    }

    @Test
    void clearingACompanionWorldNameLeavesNothingBehind() {
        final ClientWorldProfileRegistry registry = new ClientWorldProfileRegistry();
        final ClientWorldProfileResolver resolver = new ClientWorldProfileResolver(
            registry, UUID::randomUUID
        );
        resolver.nameServerWorld("example.net", "world-uuid-1", "Survival");

        resolver.nameServerWorld("example.net", "world-uuid-1", "   ");
        registry.normalize();

        assertTrue(registry.serverWorldName("example.net", "world-uuid-1").isEmpty());
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
        assertTrue(Files.exists(file.resolveSibling("client_worlds.json.bad")));
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
}
