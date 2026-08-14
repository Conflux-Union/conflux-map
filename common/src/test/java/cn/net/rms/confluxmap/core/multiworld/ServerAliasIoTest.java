package cn.net.rms.confluxmap.core.multiworld;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerAliasIoTest {
    private static final String WORLD_UUID = "3f2504e0-4f89-11d3-9a0c-0305e82c3301";

    @TempDir
    Path tempDir;

    @Test
    void survivesARestartWithItsAddressesAndLearnedWorlds() {
        final ServerAliasIo io = io();
        final ServerAliasRegistry registry = new ServerAliasRegistry();
        final ServerAliasResolver resolver = resolver(registry, Set.of());
        final String canonical = resolver.resolve("mc.example.com", WORLD_UUID).canonicalId();
        resolver.link(canonical, "192.0.2.10");

        io.save(registry);
        final ServerAliasResolver restored = resolver(io.load(), Set.of(canonical));

        assertEquals(canonical, restored.resolve("192.0.2.10"));
        assertEquals(canonical, restored.resolve("play.example.com", WORLD_UUID).canonicalId());
    }

    @Test
    void aDetachedAddressStaysDetachedAcrossRestarts() {
        final ServerAliasIo io = io();
        final ServerAliasRegistry registry = new ServerAliasRegistry();
        final ServerAliasResolver resolver = resolver(registry, Set.of());
        final String canonical = resolver.resolve("mc.example.com", WORLD_UUID).canonicalId();
        resolver.resolve("192.0.2.10", WORLD_UUID);
        resolver.unlink("192.0.2.10");

        io.save(registry);
        final ServerAliasResolver restored = resolver(io.load(), Set.of(canonical));

        assertEquals("192.0.2.10", restored.resolve("192.0.2.10", WORLD_UUID).canonicalId());
    }

    @Test
    void quarantinesCorruptJsonAndReturnsAnEmptyRegistry() throws Exception {
        final Path file = tempDir.resolve("server_aliases.json");
        Files.writeString(file, "{not json");

        final ServerAliasRegistry loaded = io().load();

        assertTrue(loaded.canonicalIds().isEmpty());
        assertTrue(Files.exists(file.resolveSibling("server_aliases.json.bad")));
    }

    @Test
    void aHandEditedFileCannotMakeOneAddressResolveTwoWays() throws Exception {
        Files.writeString(tempDir.resolve("server_aliases.json"), """
            {
              "schemaVersion": 1,
              "servers": {
                "mc.example.com": { "addresses": ["mc.example.com", "192.0.2.10"] },
                "other.example.com": { "addresses": ["other.example.com", "192.0.2.10"] }
              }
            }
            """);

        final ServerAliasResolver restored = resolver(io().load(), Set.of());

        assertEquals("mc.example.com", restored.resolve("192.0.2.10"));
        assertTrue(!restored.addresses("other.example.com").contains("192.0.2.10"));
    }

    private ServerAliasIo io() {
        return new ServerAliasIo(
            tempDir.resolve("server_aliases.json"), LogManager.getLogger("ServerAliasIoTest")
        );
    }

    private static ServerAliasResolver resolver(
        final ServerAliasRegistry registry,
        final Set<String> storage
    ) {
        return new ServerAliasResolver(registry, storage::contains, () -> { });
    }
}
