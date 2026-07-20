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
    void singleplayerUnaffectedByCompanionWork() {
        // Sanity check that the SP factory still produces its documented shape.
        final WorldIdentity id = WorldIdentity.singleplayer("New World");
        assertEquals("local", id.serverId());
        assertEquals("new_world", id.worldId());
    }

    @Test
    void sameDisplayNameSavesUseTheirDistinctFolders(@TempDir final Path tempDir) throws IOException {
        final Path firstSave = createSave(tempDir.resolve("New World"));
        final Path secondSave = createSave(tempDir.resolve("New World-1"));
        final WorldIdentity first = WorldIdentity.singleplayerSave(firstSave);
        final WorldIdentity second = WorldIdentity.singleplayerSave(secondSave);

        assertNotEquals(first, second);
        assertEquals("new_world--411b5590ae9bc2b58edb8ffc8605bff8d6d0ff0e69e245963a04b06e59b0e053", first.worldId());
        assertEquals("new_world-1--8da26504d4ec2c157fe3000e9d02cd97d28dce6d6d9ccfa3b276755deb4290a1", second.worldId());
        assertEquals("new_world", first.legacyWorldId());
        assertEquals("new_world-1", second.legacyWorldId());
    }

    @Test
    void collidingLegacyNamesNeverShareIdentityOrMigrationSource(@TempDir final Path tempDir) throws IOException {
        final Path firstSave = createSave(tempDir.resolve("A B"));
        final Path secondSave = createSave(tempDir.resolve("a_b"));
        final WorldIdentity first = WorldIdentity.singleplayerSave(firstSave);
        final WorldIdentity second = WorldIdentity.singleplayerSave(secondSave);

        assertNotEquals(first, second);
        assertNotEquals(first.worldId(), second.worldId());
        assertFalse(first.hasLegacyStorageId());
        assertFalse(second.hasLegacyStorageId());
    }

    @Test
    void migrationMetadataDoesNotChangeWorldEquality(@TempDir final Path tempDir) throws IOException {
        final Path uniqueSave = createSave(tempDir.resolve("unique").resolve("New World"));
        final Path collidingParent = tempDir.resolve("colliding");
        final Path collidingSave = createSave(collidingParent.resolve("New World"));
        createSave(collidingParent.resolve("new_world"));

        final WorldIdentity migratable = WorldIdentity.singleplayerSave(uniqueSave);
        final WorldIdentity isolated = WorldIdentity.singleplayerSave(collidingSave);

        assertTrue(migratable.hasLegacyStorageId());
        assertFalse(isolated.hasLegacyStorageId());
        assertEquals(migratable, isolated);
        assertEquals(migratable.hashCode(), isolated.hashCode());
    }

    @Test
    void unrelatedDirectoryDoesNotBlockLegacyMigration(@TempDir final Path tempDir) throws IOException {
        final Path save = createSave(tempDir.resolve("New World"));
        Files.createDirectories(tempDir.resolve("new_world"));

        assertTrue(WorldIdentity.singleplayerSave(save).hasLegacyStorageId());
    }

    private static Path createSave(final Path root) throws IOException {
        Files.createDirectories(root);
        Files.writeString(root.resolve("level.dat"), "test save");
        return root;
    }

}
