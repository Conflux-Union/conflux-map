package cn.net.rms.confluxmap.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pure-Java coverage of the S3 companion-aware identity factories. Verifies the compatibility
 * contract: {@code multiplayer(address)} stays byte-identical to today's non-companion path
 * (worldId="world"), while {@code multiplayer(address, worldId)} takes the companion's stable UUID.
 */
class WorldIdentityTest {

    @Test
    void multiplayerWithoutOverrideUsesLiteralWorldId() {
        // Bit-for-bit identical to the pre-companion path - this is the compatibility guarantee.
        final WorldIdentity id = WorldIdentity.multiplayer("example.net:25565");
        assertEquals("example.net_25565", id.serverId());
        assertEquals("world", id.worldId());
    }

    @Test
    void multiplayerWithOverrideAdoptsCompanionUuid() {
        final WorldIdentity id = WorldIdentity.multiplayer("example.net:25565", "11111111-2222-3333-4444-555555555555");
        assertEquals("example.net_25565", id.serverId());
        // UUIDs contain only [0-9a-f-], all already in the safe set, so they pass through unchanged.
        assertEquals("11111111-2222-3333-4444-555555555555", id.worldId());
    }

    @Test
    void companionOverrideChangesWorldIdButNotServerId() {
        final WorldIdentity without = WorldIdentity.multiplayer("example.net:25565");
        final WorldIdentity with = WorldIdentity.multiplayer("example.net:25565", "deadbeef-0000-0000-0000-000000000000");
        assertEquals(without.serverId(), with.serverId());
        assertNotEquals(without.worldId(), with.worldId());
    }

    @Test
    void multiplayerWithUnsafeWorldIdSanitizes() {
        // Path separators in a server-provided worldId must be neutralized before the string
        // becomes a cache directory name. The result is a single flat folder whose dots are
        // cosmetic (kept for IP/version-style UUIDs) and whose slashes collapsed to underscores,
        // so it cannot escape its parent directory.
        final WorldIdentity id = WorldIdentity.multiplayer("example.net", "../../etc/passwd");
        assertEquals("example.net", id.serverId());
        assertEquals("__.._etc_passwd", id.worldId());
    }

    @Test
    void multiplayerWithEmptyWorldIdFallsBackToUnknown() {
        final WorldIdentity id = WorldIdentity.multiplayer("example.net", "");
        assertEquals("unknown", id.worldId());
    }

    @Test
    void multiplayerWithAllUnsafeWorldIdProducesSanitizedFolderName() {
        // Sanitize is a 1:1 char replacement, so three slashes become three underscores - not the
        // "unknown" fallback (that only triggers when the post-sanitize string is empty, which a
        // non-empty input can never produce). The result is still a flat, path-safe folder name.
        final WorldIdentity id = WorldIdentity.multiplayer("example.net", "///");
        assertEquals("___", id.worldId());
    }

    @Test
    void syntheticSingleplayerIdentityUsesSanitizedName() {
        // Tests and compatibility callers can still construct a non-persistent synthetic identity.
        final WorldIdentity id = WorldIdentity.singleplayer("New World");
        assertEquals("local", id.serverId());
        assertEquals("new_world", id.worldId());
    }

    @Test
    void distinctSaveFoldersGetDistinctPersistentIds(@TempDir final Path tempDir) throws IOException {
        final Path firstSave = createSave(tempDir.resolve("New World"));
        final Path secondSave = createSave(tempDir.resolve("New World-1"));
        final WorldIdentity first = WorldIdentity.singleplayerSave(firstSave);
        final WorldIdentity second = WorldIdentity.singleplayerSave(secondSave);

        assertNotEquals(first, second);
        assertTrue(Files.isRegularFile(firstSave.resolve("confluxmap").resolve("world_uuid.json")));
        assertTrue(Files.isRegularFile(secondSave.resolve("confluxmap").resolve("world_uuid.json")));
        assertTrue(first.legacyStorageIds().contains("new_world"));
        assertTrue(second.legacyStorageIds().contains("new_world-1"));
    }

    @Test
    void collidingLegacyNamesNeverShareIdentityOrMigrationSource(@TempDir final Path tempDir) throws IOException {
        final Path firstSave = createSave(tempDir.resolve("A B"));
        final Path secondSave = createSave(tempDir.resolve("a_b"));
        final WorldIdentity first = WorldIdentity.singleplayerSave(firstSave);
        final WorldIdentity second = WorldIdentity.singleplayerSave(secondSave);

        assertNotEquals(first, second);
        assertNotEquals(first.worldId(), second.worldId());
        assertFalse(first.legacyStorageIds().contains("a_b"));
        assertFalse(second.legacyStorageIds().contains("a_b"));
    }

    @Test
    void renamingSaveFolderKeepsIdentity(@TempDir final Path tempDir) throws IOException {
        final Path original = createSave(tempDir.resolve("New World"));
        final WorldIdentity beforeRename = WorldIdentity.singleplayerSave(original);
        final Path renamed = Files.move(original, tempDir.resolve("Renamed World"));
        final WorldIdentity afterRename = WorldIdentity.singleplayerSave(renamed);

        assertEquals(beforeRename, afterRename);
        assertEquals(beforeRename.hashCode(), afterRename.hashCode());
        assertNotEquals(beforeRename.legacyStorageIds(), afterRename.legacyStorageIds());
    }

    @Test
    void recreatedSaveAtTheSamePathGetsANewIdentity(@TempDir final Path tempDir) throws IOException {
        final Path save = createSave(tempDir.resolve("New World"));
        final WorldIdentity deleted = WorldIdentity.singleplayerSave(save);
        deleteSave(save);

        final WorldIdentity recreated = WorldIdentity.singleplayerSave(createSave(save));

        assertNotEquals(deleted, recreated);
    }

    @Test
    void unrelatedDirectoryDoesNotBlockLegacyMigration(@TempDir final Path tempDir) throws IOException {
        final Path save = createSave(tempDir.resolve("New World"));
        Files.createDirectories(tempDir.resolve("new_world"));

        assertTrue(WorldIdentity.singleplayerSave(save).legacyStorageIds().contains("new_world"));
    }

    private static Path createSave(final Path root) throws IOException {
        Files.createDirectories(root);
        Files.writeString(root.resolve("level.dat"), "test save");
        return root;
    }

    private static void deleteSave(final Path root) throws IOException {
        Files.delete(root.resolve("confluxmap").resolve("world_uuid.json"));
        Files.delete(root.resolve("confluxmap"));
        Files.delete(root.resolve("level.dat"));
        Files.delete(root);
    }

}
