package cn.net.rms.confluxmap.core.waypoint.library;

import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class MapScreenshotIndexTest {
    @TempDir Path directory;

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
