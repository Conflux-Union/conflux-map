package cn.net.rms.confluxmap.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * MC-free coverage of {@link WorldIds.Io}: generate-on-first-access, persist, read-back
 * stability, and resilience against an unreadable file. The {@link WorldIds} outer class is a
 * thin {@link net.minecraft.server.MinecraftServer}-keyed cache wrapper; its only logic lives in
 * {@link WorldIds.Io} so that is what we exercise here.
 */
class WorldIdsTest {

    @Test
    void firstAccessGeneratesAndPersists(@TempDir final Path tmp) throws IOException {
        final Path worldRoot = tmp.resolve("world");
        final java.util.UUID first = WorldIds.Io.loadOrCreate(worldRoot);

        assertNotEquals(java.util.UUID.randomUUID(), first, "sanity: not a freshly-drawn uuid");
        // File landed at the documented location.
        final Path file = worldRoot.resolve("confluxmap").resolve("world_uuid.json");
        assertTrue(Files.exists(file), "world_uuid.json should exist after first access");
    }

    @Test
    void secondAccessReadsBackTheSameUuid(@TempDir final Path tmp) throws IOException {
        final Path worldRoot = tmp.resolve("world");
        final java.util.UUID first = WorldIds.Io.loadOrCreate(worldRoot);
        // Re-read: must be the same UUID, not a freshly generated one.
        final java.util.UUID second = WorldIds.Io.loadOrCreate(worldRoot);
        assertEquals(first, second);
    }

    @Test
    void freshRootsGetDistinctUuids(@TempDir final Path tmp) throws IOException {
        // Two unrelated world roots must not collide - the whole point of the namespace.
        final java.util.UUID a = WorldIds.Io.loadOrCreate(tmp.resolve("worldA"));
        final java.util.UUID b = WorldIds.Io.loadOrCreate(tmp.resolve("worldB"));
        assertNotEquals(a, b);
    }

    @Test
    void unreadableFileTriggersRegeneration(@TempDir final Path tmp) throws IOException {
        final Path worldRoot = tmp.resolve("world");
        final Path file = worldRoot.resolve("confluxmap").resolve("world_uuid.json");
        Files.createDirectories(file.getParent());
        // Garbage that GSON cannot bind to {uuid:"..."}.
        Files.writeString(file, "this is not json at all", StandardCharsets.UTF_8);

        final java.util.UUID regenerated = WorldIds.Io.loadOrCreate(worldRoot);
        // The corrupt file was overwritten in-place with a fresh UUID.
        final java.util.UUID reread = WorldIds.Io.loadOrCreate(worldRoot);
        assertEquals(regenerated, reread);
    }

    @Test
    void parseAcceptsCanonicalForm() {
        // Round-trip through parse() to lock the JSON shape external operators might hand-edit.
        final java.util.UUID src = java.util.UUID.fromString("11111111-2222-3333-4444-555555555555");
        assertEquals(src, WorldIds.Io.parse("{\"uuid\":\"" + src + "\"}"));
    }

    @Test
    void parseRejectsMissingField() {
        // An object that parses but has no `uuid` field returns null (caller regenerates).
        assertEquals(null, WorldIds.Io.parse("{\"notUuid\":\"" + "x" + "\"}"));
    }

    @Test
    void parseRejectsMalformedUuid() {
        assertEquals(null, WorldIds.Io.parse("{\"uuid\":\"not-a-uuid\"}"));
    }

    @Test
    void parseRejectsBlankAndNull() {
        assertEquals(null, WorldIds.Io.parse(null));
        assertEquals(null, WorldIds.Io.parse(""));
        assertEquals(null, WorldIds.Io.parse("   "));
    }
}
