package cn.net.rms.confluxmap.core.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerInstanceIdStoreTest {
    @TempDir
    Path configDir;

    @Test
    void generatesAndPersistsAnIdOnFirstUse() {
        final UUID created = ServerInstanceIdStore.loadOrCreate(configDir);

        assertNotNull(created);
        assertTrue(Files.exists(configDir.resolve("server_instance.json")));
    }

    @Test
    void keepsTheSameIdAcrossRestarts() {
        final UUID first = ServerInstanceIdStore.loadOrCreate(configDir);

        assertEquals(first, ServerInstanceIdStore.loadOrCreate(configDir));
    }

    @Test
    void regeneratesWhenTheFileIsUnreadable() throws IOException {
        final UUID first = ServerInstanceIdStore.loadOrCreate(configDir);
        Files.writeString(
            configDir.resolve("server_instance.json"), "{ not json", StandardCharsets.UTF_8
        );

        final UUID replacement = ServerInstanceIdStore.loadOrCreate(configDir);

        assertNotEquals(first, replacement);
        assertEquals(replacement, ServerInstanceIdStore.loadOrCreate(configDir));
    }

    @Test
    void createsTheDirectoryWhenMissing() {
        final Path missing = configDir.resolve("confluxmap");

        assertNotNull(ServerInstanceIdStore.loadOrCreate(missing));
        assertTrue(Files.exists(missing.resolve("server_instance.json")));
    }

    /**
     * A copied world folder must not carry the instance identity with it, so the id lives beside
     * the config rather than inside the save.
     */
    @Test
    void keepsSeparateIdsForSeparateConfigDirectories() {
        final UUID survival = ServerInstanceIdStore.loadOrCreate(configDir.resolve("survival"));
        final UUID mirror = ServerInstanceIdStore.loadOrCreate(configDir.resolve("mirror"));

        assertNotEquals(survival, mirror);
    }
}
