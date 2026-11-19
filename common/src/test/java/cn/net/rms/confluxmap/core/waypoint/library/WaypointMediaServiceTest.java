package cn.net.rms.confluxmap.core.waypoint.library;

import static org.junit.jupiter.api.Assertions.*;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WaypointMediaServiceTest {
    @TempDir Path temp;
    private WaypointMediaService service() { return new WaypointMediaService(temp.resolve("media"), Runnable::run); }
    private WaypointMediaService.Key key(boolean shared) { return new WaypointMediaService.Key(new WorldIdentity("local", "test"), shared, new UUID(0, 1)); }
    private Path image(String name, String format, int w, int h) throws Exception {
        Path file = temp.resolve(name); BufferedImage pixels = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        pixels.setRGB(0, 0, 0xFFCC8844); assertTrue(ImageIO.write(pixels, format, file.toFile())); return file;
    }
    @Test void importsOwnedCopyThumbnailAndFullImageWithoutChangingExternalFile() throws Exception {
        Path external = image("\u4e2d\u6587.png", "png", 1200, 600); byte[] before = Files.readAllBytes(external);
        var media = service(); var photo = media.add(key(false), external).join();
        assertEquals(1200, photo.width()); assertEquals(600, photo.height());
        Files.delete(external);
        var thumbnail = media.pixels(key(false), photo.id(), true).join(); assertEquals(256, thumbnail.width()); assertEquals(128, thumbnail.height());
        var full = media.pixels(key(false), photo.id(), false).join(); assertEquals(1200, full.width()); assertEquals(0xFFCC8844, full.argb()[0]);
        assertArrayEquals(before, Files.readAllBytes(temp.resolve("media/local/test/" + photo.id() + ".png")));
    }
    @Test void sameUuidHasIndependentLocalSharedAndWorldBindings() throws Exception {
        Path file = image("photo.jpg", "jpeg", 20, 10); var media = service(); var local = key(false);
        var photo = media.add(local, file).join();
        assertTrue(media.list(key(true)).join().isEmpty());
        assertTrue(media.list(new WaypointMediaService.Key(new WorldIdentity("server", "other"), false, local.waypoint())).join().isEmpty());
        assertThrows(CompletionException.class, () -> media.pixels(key(true), photo.id(), false).join());
    }
    @Test void adoptsOwnedPhotoAndThumbnailUnderTheNewInstance() throws Exception {
        final var legacy = key(false);
        final var world = new WorldIdentity(legacy.world().serverId(), "instance");
        final var adopted = new WaypointMediaService.Key(world, false, legacy.waypoint());
        final var media = service();
        final var photo = media.add(legacy, image("wide.png", "png", 2400, 1200)).join();
        media.adoptNamespace(world, legacy.world().worldId(), org.apache.logging.log4j.LogManager.getLogger());
        assertTrue(media.list(legacy).join().isEmpty());
        assertEquals(photo, media.list(adopted).join().get(0));
        assertEquals(2400, photo.width());
        assertEquals(256, media.pixels(adopted, photo.id(), true).join().width());
        assertEquals(2048, media.pixels(adopted, photo.id(), false).join().width());
    }
    @Test void captionOrderRemoveAndRestartPreserveOtherPhotosAndExternalFile() throws Exception {
        Path file = image("photo.png", "png", 16, 16); var media = service(); var key = key(false);
        var a = media.add(key, file).join(); var b = media.add(key, file).join();
        Path index = temp.resolve("media/local/test/index.json");
        var legacy = new com.google.gson.Gson().fromJson(Files.readString(index), com.google.gson.JsonObject.class);
        var photos = legacy.getAsJsonObject("bindings").getAsJsonArray(key.binding());
        photos.forEach(photo -> photo.getAsJsonObject().remove("rotation"));
        Files.writeString(index, legacy.toString());
        assertEquals(0, media.list(key).join().get(0).rotation());
        Path original = temp.resolve("media/local/test/" + b.id() + ".png");
        byte[] bytes = Files.readAllBytes(original);
        media.saveRotation(key, b.id(), -135.5).join();
        assertEquals(0, media.list(key).join().get(0).rotation());
        media.edit(key, b.id(), "caption", -1, false).join();
        assertEquals(List.of(b.id(), a.id()), media.list(key).join().stream().map(WaypointMediaService.Photo::id).toList());
        media.edit(key, a.id(), "", 0, true).join();
        var restart = service(); assertEquals("caption", restart.list(key).join().get(0).caption()); assertTrue(Files.exists(file));
        assertThrows(CompletionException.class, () -> restart.edit(key, b.id(), "x".repeat(513), 0, false).join());
        assertEquals("caption", restart.list(key).join().get(0).caption());
        assertEquals(224.5, restart.list(key).join().get(0).rotation());
        assertArrayEquals(bytes, Files.readAllBytes(original));
    }
    @Test void corruptOrFutureIndexAndInvalidInputArePreserved() throws Exception {
        var media = service(); Path external = image("valid.png", "png", 8, 8);
        Path index = temp.resolve("media/local/test/index.json"); Files.createDirectories(index.getParent());
        for (String invalid : List.of("{broken", "{\"schemaVersion\":2,\"bindings\":{}}")) {
            Files.writeString(index, invalid);
            assertThrows(CompletionException.class, () -> media.add(key(false), external).join());
            assertEquals(invalid, Files.readString(index));
        }
        Files.delete(index); Path text = temp.resolve("fake.png"); Files.writeString(text, "not a PNG");
        assertThrows(CompletionException.class, () -> media.add(key(false), text).join());
        assertTrue(media.list(key(false)).join().isEmpty());
        try (var files = Files.list(index.getParent())) { assertEquals(0, files.count()); }
    }
    @Test void traversalWorldIsRejected() {
        assertThrows(CompletionException.class, () -> service().list(new WaypointMediaService.Key(new WorldIdentity("../..", "outside"), false, UUID.randomUUID())).join());
    }
    @Test void oversizedFileAndImageHeaderAreRejectedBeforeDecodeOrCopyCommit() throws Exception {
        var media = service(); Path hugeFile = temp.resolve("huge.png");
        try (var sparse = new java.io.RandomAccessFile(hugeFile.toFile(), "rw")) { sparse.setLength(32L * 1024 * 1024 + 1); }
        assertThrows(CompletionException.class, () -> media.add(key(false), hugeFile).join());
        Path header = temp.resolve("huge-header.png");
        var chunk = new java.io.ByteArrayOutputStream();
        try (var data = new java.io.DataOutputStream(chunk)) {
            data.writeBytes("IHDR"); data.writeInt(100000); data.writeInt(100000);
            data.write(new byte[]{8, 2, 0, 0, 0});
        }
        var crc = new java.util.zip.CRC32(); crc.update(chunk.toByteArray());
        try (var data = new java.io.DataOutputStream(Files.newOutputStream(header))) {
            data.writeLong(0x89504e470d0a1a0aL); data.writeInt(13); data.write(chunk.toByteArray()); data.writeInt((int) crc.getValue());
        }
        CompletionException error = assertThrows(CompletionException.class, () -> media.add(key(false), header).join());
        assertTrue(error.getCause().getMessage().contains("40 million"));
        assertTrue(media.list(key(false)).join().isEmpty());
    }
    @Test void photoLimitDoesNotOverwriteExistingBindings() throws Exception {
        var media = service(); Path file = image("tiny.png", "png", 1, 1);
        for (int i = 0; i < 64; i++) media.add(key(false), file).join();
        var before = media.list(key(false)).join();
        assertThrows(CompletionException.class, () -> media.add(key(false), file).join());
        assertEquals(before, media.list(key(false)).join());
    }
    @Test void concurrentImportsDoNotLoseBindings() throws Exception {
        Path file = image("parallel.png", "png", 8, 8); ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            var media = new WaypointMediaService(temp.resolve("media"), pool);
            List<CompletableFuture<WaypointMediaService.Photo>> requests = new ArrayList<>();
            for (int i = 0; i < 8; i++) requests.add(media.add(key(false), file));
            CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).join();
            assertEquals(8, media.list(key(false)).join().size());
            assertEquals(8, media.list(key(false)).join().stream().map(WaypointMediaService.Photo::id).distinct().count());
        } finally { pool.shutdownNow(); }
    }
}
