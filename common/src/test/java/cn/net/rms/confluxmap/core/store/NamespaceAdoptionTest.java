package cn.net.rms.confluxmap.core.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.WorldIdentity;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Upgrading a server to advertise an instance id moves its namespace key from the world UUID to
 * the instance id. The data already stored under the old key has to come along, exactly once.
 */
class NamespaceAdoptionTest {
    private static final Logger LOGGER = LogManager.getLogger("NamespaceAdoptionTest");
    private static final String ADDRESS = "play.example.com";
    private static final String WORLD_UUID = "11111111-2222-3333-4444-555555555555";
    private static final String SURVIVAL = "aaaaaaaa-0000-0000-0000-000000000000";
    private static final String MIRROR = "bbbbbbbb-0000-0000-0000-000000000000";

    @TempDir
    Path root;

    @Test
    void directoryNamespaceMovesToTheInstanceKey() throws IOException {
        final Path cache = root.resolve("cache");
        writeDirectory(cache, WORLD_UUID, "r.0.0.cfr");

        assertTrue(adopt(List.of(directory(cache)), SURVIVAL));

        assertTrue(Files.exists(serverDir(cache).resolve(SURVIVAL).resolve("r.0.0.cfr")));
        assertFalse(Files.exists(serverDir(cache).resolve(WORLD_UUID)));
    }

    @Test
    void fileNamespaceMovesToTheInstanceKey() throws IOException {
        final Path waypoints = root.resolve("waypoints");
        writeFile(waypoints, WORLD_UUID + ".json");

        assertTrue(adopt(List.of(file(waypoints)), SURVIVAL));

        assertEquals("data", Files.readString(
            serverDir(waypoints).resolve(SURVIVAL + ".json"), StandardCharsets.UTF_8
        ));
        assertFalse(Files.exists(serverDir(waypoints).resolve(WORLD_UUID + ".json")));
    }

    /** Two histories must never be folded together implicitly; that stays an explicit migration. */
    @Test
    void anOccupiedTargetIsLeftAlone() throws IOException {
        final Path cache = root.resolve("cache");
        writeDirectory(cache, WORLD_UUID, "old.cfr");
        writeDirectory(cache, SURVIVAL, "new.cfr");

        assertFalse(adopt(List.of(directory(cache)), SURVIVAL));

        assertTrue(Files.exists(serverDir(cache).resolve(WORLD_UUID).resolve("old.cfr")));
        assertFalse(Files.exists(serverDir(cache).resolve(SURVIVAL).resolve("old.cfr")));
    }

    @Test
    void nothingToAdoptIsNotAnError() {
        assertFalse(adopt(List.of(directory(root.resolve("cache"))), SURVIVAL));
    }

    /**
     * The mirror and survival servers share the copied world UUID, so both would claim the same
     * old namespace. The first one to connect takes it and the move itself stops the second.
     */
    @Test
    void onlyTheFirstInstanceToConnectAdoptsTheOldNamespace() throws IOException {
        final Path cache = root.resolve("cache");
        writeDirectory(cache, WORLD_UUID, "r.0.0.cfr");

        assertTrue(adopt(List.of(directory(cache)), SURVIVAL));
        assertFalse(adopt(List.of(directory(cache)), MIRROR));

        assertTrue(Files.exists(serverDir(cache).resolve(SURVIVAL).resolve("r.0.0.cfr")));
        assertFalse(Files.exists(serverDir(cache).resolve(MIRROR)));
    }

    @Test
    void everyStoreMovesTogether() throws IOException {
        final Path cache = root.resolve("cache");
        final Path waypoints = root.resolve("waypoints");
        writeDirectory(cache, WORLD_UUID, "r.0.0.cfr");
        writeFile(waypoints, WORLD_UUID + ".json");

        assertTrue(adopt(List.of(directory(cache), file(waypoints)), SURVIVAL));

        assertTrue(Files.exists(serverDir(cache).resolve(SURVIVAL).resolve("r.0.0.cfr")));
        assertTrue(Files.exists(serverDir(waypoints).resolve(SURVIVAL + ".json")));
    }

    private boolean adopt(final List<NamespaceAdoption.Store> stores, final String instanceId) {
        return NamespaceAdoption.adopt(
            stores,
            WorldIdentity.companionMultiplayer(ADDRESS, instanceId, WORLD_UUID),
            WORLD_UUID,
            LOGGER
        );
    }

    private static NamespaceAdoption.Store directory(final Path storeRoot) {
        return new NamespaceAdoption.Store(storeRoot, "");
    }

    private static NamespaceAdoption.Store file(final Path storeRoot) {
        return new NamespaceAdoption.Store(storeRoot, ".json");
    }

    private static Path serverDir(final Path storeRoot) {
        return storeRoot.resolve(WorldIdentity.serverId(ADDRESS));
    }

    private void writeDirectory(final Path storeRoot, final String worldId, final String child)
        throws IOException {
        final Path dir = serverDir(storeRoot).resolve(worldId);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(child), "data", StandardCharsets.UTF_8);
    }

    private void writeFile(final Path storeRoot, final String name) throws IOException {
        Files.createDirectories(serverDir(storeRoot));
        Files.writeString(serverDir(storeRoot).resolve(name), "data", StandardCharsets.UTF_8);
    }
}
