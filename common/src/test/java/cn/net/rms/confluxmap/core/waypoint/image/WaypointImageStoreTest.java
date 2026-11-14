package cn.net.rms.confluxmap.core.waypoint.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.net.image.WaypointImageProto;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WaypointImageStoreTest {
    private static final UUID OWNER = UUID.fromString("0123abcd-4567-789a-bcde-f0123456789a");

    private static void save(final WaypointImageStore store, final UUID imageId) throws IOException {
        store.save(imageId, OWNER, WaypointImageProto.FORMAT_PNG, 64, 48, new byte[256]);
    }

    @Test
    void saveLoadExistsRoundTrip(@TempDir final Path tempDir) throws IOException {
        final WaypointImageStore store = new WaypointImageStore(tempDir.resolve("images"));
        final UUID imageId = UUID.randomUUID();
        assertFalse(store.exists(imageId));
        assertTrue(store.load(imageId).isEmpty());

        save(store, imageId);

        assertTrue(store.exists(imageId));
        final StoredImage loaded = store.load(imageId).orElseThrow();
        assertEquals(64, loaded.width());
        assertEquals(OWNER, loaded.owner());
        assertTrue(store.fileFor(imageId).getFileName().toString().startsWith(imageId.toString()));
    }

    @Test
    void sweepDeletesUnreferencedFilesOlderThanMinAge(@TempDir final Path tempDir) throws IOException {
        final WaypointImageStore store = new WaypointImageStore(tempDir.resolve("images"));
        final UUID referenced = UUID.randomUUID();
        final UUID unreferenced = UUID.randomUUID();
        save(store, referenced);
        save(store, unreferenced);

        final long now = System.currentTimeMillis();
        // Fresh files stay regardless of references (24h-style grace).
        assertEquals(
            0,
            store.sweep(Set.of(referenced.toString()), 24L * 3600 * 1000, now)
        );
        assertTrue(store.exists(unreferenced));

        // Age both files past the grace window.
        final long old = now + 25L * 3600 * 1000;
        assertEquals(
            1,
            store.sweep(Set.of(referenced.toString()), 24L * 3600 * 1000, old)
        );
        assertTrue(store.exists(referenced));
        assertFalse(store.exists(unreferenced));

        // Everything referenced is never swept, however old.
        assertEquals(0, store.sweep(Set.of(referenced.toString()), 24L * 3600 * 1000, old));
        // An empty reference set sweeps every aged file: the previously
        // referenced image and the freshly saved one have both aged out.
        save(store, UUID.randomUUID());
        assertEquals(2, store.sweep(Set.of(), 24L * 3600 * 1000, old));
    }

    @Test
    void sweepReportsOwnerAndBytesForEveryDeletedFile(@TempDir final Path tempDir) throws IOException {
        final WaypointImageStore store = new WaypointImageStore(tempDir.resolve("images"));
        final UUID kept = UUID.randomUUID();
        final UUID deleted = UUID.randomUUID();
        final UUID owner = UUID.randomUUID();
        save(store, kept);
        store.save(deleted, owner, WaypointImageProto.FORMAT_PNG, 64, 48, new byte[256]);

        final java.util.Map<UUID, Long> freed = new java.util.LinkedHashMap<>();
        final long old = System.currentTimeMillis() + 25L * 3600 * 1000;
        assertEquals(1, store.sweep(
            Set.of(kept.toString()), 24L * 3600 * 1000, old, freed::put
        ));
        assertEquals(java.util.Map.of(owner, 256L), freed);
    }

    @Test
    void unknownFilesInTheDirectoryAreIgnored(@TempDir final Path tempDir) throws IOException {
        final WaypointImageStore store = new WaypointImageStore(tempDir.resolve("images"));
        java.nio.file.Files.createDirectories(tempDir.resolve("images"));
        java.nio.file.Files.writeString(tempDir.resolve("images").resolve("notes.txt"), "junk");
        java.nio.file.Files.writeString(
            tempDir.resolve("images").resolve("not-a-uuid.img"), "junk"
        );

        assertEquals(0, store.sweep(Set.of(), 0L, System.currentTimeMillis()));
        assertTrue(store.load(UUID.randomUUID()).isEmpty());
    }

    @Test
    void sweepAlsoRemovesAgedTmpResidue(@TempDir final Path tempDir) throws IOException {
        final WaypointImageStore store = new WaypointImageStore(tempDir.resolve("images"));
        // Crash residue: the tmp sibling WaypointImageFile.save writes before its atomic move.
        final Path tmp = store.directory().resolve(
            UUID.randomUUID() + WaypointImageStore.EXTENSION + ".tmp"
        );
        java.nio.file.Files.createDirectories(store.directory());
        java.nio.file.Files.writeString(tmp, "partial");

        final long now = System.currentTimeMillis();
        assertEquals(0, store.sweep(Set.of(), 24L * 3600 * 1000, now));
        assertTrue(java.nio.file.Files.exists(tmp));

        assertEquals(1, store.sweep(Set.of(), 24L * 3600 * 1000, now + 25L * 3600 * 1000));
        assertFalse(java.nio.file.Files.exists(tmp));
    }
}
