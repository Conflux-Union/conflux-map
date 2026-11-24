package cn.net.rms.confluxmap.core.waypoint.library;

import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class MapScreenshotIndexTest {
    @TempDir Path directory;

    @Test void adoptsScreenshotScopesWithoutOverwritingAnotherWorld() throws Exception {
        Path screenshots = Files.createDirectory(directory.resolve("screenshots"));
        final var index = new MapScreenshotIndex(directory.resolve("locations.json"), screenshots,
            Runnable::run, LogManager.getLogger());
        final var source = new MapScreenshotIndex.Scope("server", "uuid", "minecraft:the_nether");
        final var target = new MapScreenshotIndex.Scope("server", "instance", source.dimension());
        final var shot = Files.createFile(screenshots.resolve("capture.png"));
        index.save(new MapScreenshotIndex.Location(source, 1, 2, 3), shot, 1);
        index.saveRotation(index.list(source).get(0), 90).join();
        index.adoptNamespace(new cn.net.rms.confluxmap.core.model.WorldIdentity("server", target.world()), source.world());
        assertTrue(index.list(source).isEmpty());
        assertEquals(90, index.list(target).get(0).rotation());
        assertEquals(shot, index.file(index.list(target).get(0)));

        final var second = Files.createFile(screenshots.resolve("second.png"));
        index.save(new MapScreenshotIndex.Location(source, 4, 5, 6), second, 2);
        index.adoptNamespace(new cn.net.rms.confluxmap.core.model.WorldIdentity("server", target.world()), source.world());
        assertEquals("second.png", index.list(source).get(0).file());
        assertEquals("capture.png", index.list(target).get(0).file());
    }

    @Test void captureScopeSurvivesRestartAndFilesOutsideScreenshotDirectoryAreIgnored() throws Exception {
        Path screenshots = Files.createDirectory(directory.resolve("screenshots"));
        Path index = directory.resolve("locations.json");
        var overworld = new MapScreenshotIndex.Scope("server", "world", "minecraft:overworld");
        var nether = new MapScreenshotIndex.Scope("server", "world", "minecraft:the_nether");
        var capture = new MapScreenshotIndex.Location(overworld, -100.75, 64, 12.125);
        Path screenshot = Files.createFile(screenshots.resolve("capture.png"));
        var writer = new MapScreenshotIndex(index, screenshots, Runnable::run, LogManager.getLogger());
        writer.save(capture, Files.createFile(directory.resolve("outside.png")), 1);
        writer.save(capture, screenshot, 2);

        var reader = new MapScreenshotIndex(index, screenshots, Runnable::run, LogManager.getLogger());
        assertEquals(new MapScreenshotIndex.Shot(capture, "capture.png", 2), reader.list(overworld).get(0));
        assertEquals(1, reader.list(overworld).size());
        assertTrue(reader.list(nether).isEmpty());
        assertEquals(screenshot, reader.file(reader.list(overworld).get(0)));
        Files.writeString(screenshot, "original");
        var oldDocument = new com.google.gson.Gson().fromJson(Files.readString(index), com.google.gson.JsonObject.class);
        oldDocument.getAsJsonArray("shots").get(0).getAsJsonObject().remove("rotation");
        Files.writeString(index, oldDocument.toString());
        var oldReader = new MapScreenshotIndex(index, screenshots, Runnable::run, LogManager.getLogger());
        assertEquals(0, oldReader.list(overworld).get(0).rotation());
        oldReader.saveRotation(oldReader.list(overworld).get(0), -90).join();
        var reopened = new MapScreenshotIndex(index, screenshots, Runnable::run, LogManager.getLogger());
        assertEquals(270, reopened.list(overworld).get(0).rotation());
        assertEquals("original", Files.readString(screenshot));
        assertThrows(IllegalArgumentException.class, () -> new MapScreenshotIndex.Shot(capture, "../capture.png", 3));
    }
}
