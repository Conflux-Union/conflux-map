package cn.net.rms.confluxmap.server.waypointimage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.net.image.WaypointImageProto;
import cn.net.rms.confluxmap.core.waypoint.image.WaypointImageStore;
import java.nio.file.Path;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SharedWaypointImageStoreTest {
    private static final Logger LOGGER = LogManager.getLogger("SharedWaypointImageStoreTest");
    private static final UUID PLAYER = UUID.fromString("0123abcd-4567-789a-bcde-f0123456789a");
    private static final UUID OTHER = UUID.fromString("fedcba98-7654-3210-ba98-76543210fedc");

    private static SharedWaypointImageStore.Limits limits() {
        return new SharedWaypointImageStore.Limits(
            2 * 1024 * 1024, 512 * 1024, 10
        );
    }

    private static byte[] payload(final int size) {
        final byte[] bytes = new byte[size];
        bytes[0] = 1;
        return bytes;
    }

    @Test
    void beginChunkCompleteStoresTheImageAndReportsOk(@TempDir final Path tempDir) throws Exception {
        final SharedWaypointImageStore store = new SharedWaypointImageStore(
            new WaypointImageStore(tempDir.resolve("images")), limits(), LOGGER
        );
        final UUID imageId = UUID.randomUUID();
        final long now = 1_000_000L;

        assertEquals(
            WaypointImageProto.STATUS_OK,
            store.begin(PLAYER, imageId, WaypointImageProto.FORMAT_PNG, 320, 180, 48, now)
        );
        // Intermediate chunks get no reply; only the completing chunk reports OK.
        assertNull(store.chunk(PLAYER, imageId, 0, payload(16), now));
        assertEquals(
            WaypointImageProto.STATUS_OK,
            store.chunk(PLAYER, imageId, 1, payload(32), now)
        );

        assertTrue(store.imageStore().exists(imageId));
        assertEquals(48, store.imageStore().load(imageId).orElseThrow().png().length);
        assertEquals(PLAYER, store.imageStore().load(imageId).orElseThrow().owner());
        // The completed session is gone; a tight per-player budget now rejects
        // the next upload even though the byte count fits one image.
        final SharedWaypointImageStore tight = new SharedWaypointImageStore(
            new WaypointImageStore(tempDir.resolve("images-tight")),
            new SharedWaypointImageStore.Limits(2 * 1024 * 1024, 100, 10),
            LOGGER
        );
        tight.begin(PLAYER, imageId, WaypointImageProto.FORMAT_PNG, 320, 180, 48, now);
        tight.chunk(PLAYER, imageId, 0, payload(48), now);
        assertEquals(
            WaypointImageProto.STATUS_QUOTA_EXCEEDED,
            tight.begin(PLAYER, UUID.randomUUID(), WaypointImageProto.FORMAT_PNG, 320, 180,
                WaypointImageProto.MAX_IMAGE_BYTES, now)
        );
    }

    @Test
    void beginIsIdempotentForTheSameOwnerAndConflictsForOthers(@TempDir final Path tempDir)
        throws Exception {
        final SharedWaypointImageStore store = new SharedWaypointImageStore(
            new WaypointImageStore(tempDir.resolve("images")), limits(), LOGGER
        );
        final UUID imageId = UUID.randomUUID();
        final long now = 1_000_000L;

        assertEquals(
            WaypointImageProto.STATUS_OK,
            store.begin(PLAYER, imageId, WaypointImageProto.FORMAT_PNG, 320, 180, 64, now)
        );
        store.chunk(PLAYER, imageId, 0, payload(64), now);
        assertTrue(store.imageStore().exists(imageId));

        // A resend of BEGIN after completion is safe for the same owner.
        assertEquals(
            WaypointImageProto.STATUS_OK,
            store.begin(PLAYER, imageId, WaypointImageProto.FORMAT_PNG, 320, 180, 64, now)
        );
        // A different owner may never overwrite someone else's image.
        assertEquals(
            WaypointImageProto.STATUS_DUPLICATE_OWNER_CONFLICT,
            store.begin(OTHER, imageId, WaypointImageProto.FORMAT_PNG, 320, 180, 64, now)
        );
    }

    @Test
    void ownerTableIsRebuiltFromTheFileHeaders(@TempDir final Path tempDir) throws Exception {
        final WaypointImageStore images = new WaypointImageStore(tempDir.resolve("images"));
        final UUID imageId = UUID.randomUUID();
        images.save(imageId, PLAYER, WaypointImageProto.FORMAT_PNG, 8, 8, payload(32));

        final SharedWaypointImageStore store = new SharedWaypointImageStore(
            images, limits(), LOGGER
        );
        store.rebuildOwnerTable();

        assertEquals(
            WaypointImageProto.STATUS_DUPLICATE_OWNER_CONFLICT,
            store.begin(OTHER, imageId, WaypointImageProto.FORMAT_PNG, 320, 180, 32, 1_000_000L)
        );
    }

    @Test
    void oversizedTotalsAndPendingCapsAreRejected(@TempDir final Path tempDir) throws Exception {
        final SharedWaypointImageStore store = new SharedWaypointImageStore(
            new WaypointImageStore(tempDir.resolve("images")), limits(), LOGGER
        );
        final long now = 1_000_000L;

        assertEquals(
            WaypointImageProto.STATUS_TOO_LARGE,
            store.begin(PLAYER, UUID.randomUUID(), WaypointImageProto.FORMAT_PNG,
                320, 180, WaypointImageProto.MAX_IMAGE_BYTES + 1, now)
        );

        // Four concurrent pending sessions are allowed, the fifth is rate-limited.
        for (int i = 0; i < 4; i++) {
            assertEquals(
                WaypointImageProto.STATUS_OK,
                store.begin(PLAYER, UUID.randomUUID(), WaypointImageProto.FORMAT_PNG, 320, 180, 1024, now)
            );
        }
        assertEquals(
            WaypointImageProto.STATUS_RATE_LIMITED,
            store.begin(PLAYER, UUID.randomUUID(), WaypointImageProto.FORMAT_PNG, 320, 180, 1024, now)
        );
    }

    @Test
    void pendingByteCapIsEnforcedPerPlayer(@TempDir final Path tempDir) throws Exception {
        final SharedWaypointImageStore store = new SharedWaypointImageStore(
            new WaypointImageStore(tempDir.resolve("images")), limits(), LOGGER
        );
        final long now = 1_000_000L;
        // Three pending sessions use 96 KiB; a fourth crossing 128 KiB is quota-denied
        // while the session count is still below its own cap.
        for (int i = 0; i < 3; i++) {
            assertEquals(
                WaypointImageProto.STATUS_OK,
                store.begin(PLAYER, UUID.randomUUID(), WaypointImageProto.FORMAT_PNG,
                    320, 180, 32 * 1024, now)
            );
        }
        assertEquals(
            WaypointImageProto.STATUS_QUOTA_EXCEEDED,
            store.begin(PLAYER, UUID.randomUUID(), WaypointImageProto.FORMAT_PNG,
                320, 180, 33 * 1024, now)
        );
    }

    @Test
    void idleSessionsExpireAfterThirtySeconds(@TempDir final Path tempDir) throws Exception {
        final SharedWaypointImageStore store = new SharedWaypointImageStore(
            new WaypointImageStore(tempDir.resolve("images")), limits(), LOGGER
        );
        final UUID imageId = UUID.randomUUID();
        final long now = 1_000_000L;
        store.begin(PLAYER, imageId, WaypointImageProto.FORMAT_PNG, 320, 180, 32, now);
        assertNull(store.chunk(PLAYER, imageId, 0, payload(16), now + 1_000));

        // After 30s idle the session is gone: chunks are ignored and a new
        // BEGIN no longer counts the stale session against the player.
        assertNull(store.chunk(PLAYER, imageId, 0, payload(16), now + 31_000));
        assertEquals(
            WaypointImageProto.STATUS_OK,
            store.begin(PLAYER, UUID.randomUUID(), WaypointImageProto.FORMAT_PNG,
                320, 180, 32 * 1024, now + 31_000)
        );
        assertFalse(store.imageStore().exists(imageId));
    }

    @Test
    void chunksWithoutASessionAreIgnored(@TempDir final Path tempDir) throws Exception {
        final SharedWaypointImageStore store = new SharedWaypointImageStore(
            new WaypointImageStore(tempDir.resolve("images")), limits(), LOGGER
        );
        // A stray chunk without a session is silently ignored (no reply).
        assertNull(store.chunk(PLAYER, UUID.randomUUID(), 0, payload(8), 1_000_000L));
        assertTrue(store.imageStore().load(UUID.randomUUID()).isEmpty());
    }
}
