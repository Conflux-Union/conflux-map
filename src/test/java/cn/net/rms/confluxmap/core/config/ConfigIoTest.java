package cn.net.rms.confluxmap.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.predict.StructureIndex;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Load-time schema upgrade and rewrite behavior of {@link ConfigIo}. */
class ConfigIoTest {

    private static final Logger LOGGER = LogManager.getLogger("ConfigIoTest");

    @Test
    void loadFillsMissingFieldsAndRewritesFile(@TempDir final Path tmp) throws IOException {
        final Path file = tmp.resolve("config.json");
        Files.writeString(file, "{\"schemaVersion\":1,\"minimapSize\":300}", StandardCharsets.UTF_8);

        final ConfluxConfig loaded = new ConfigIo(file, LOGGER).load();

        // Absent fields keep their defaults; out-of-range values are clamped.
        assertEquals(new ConfluxConfig().predictionDebounceMs, loaded.predictionDebounceMs);
        assertTrue(loaded.annotationsOnHud);
        assertEquals(ConfluxConfig.DEFAULT_ANNOTATION_ERASER_SIZE, loaded.annotationEraserSize);
        assertEquals(new ConfluxConfig().fullscreenDisplayMode, loaded.fullscreenDisplayMode);
        assertEquals(new ConfluxConfig().chunkLoadDetailMode, loaded.chunkLoadDetailMode);
        assertEquals(256, loaded.minimapSize);
        // The upgrade is persisted so the on-disk file now carries the full schema.
        final String rewritten = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(rewritten.contains("\"predictionDebounceMs\""));
        assertTrue(rewritten.contains("\"predictionStructureVisibility\""));
        assertTrue(rewritten.contains("\"annotationsOnHud\""));
        assertTrue(rewritten.contains("\"annotationEraserSize\""));
        assertTrue(rewritten.contains("\"fullscreenDisplayMode\""));
        assertTrue(rewritten.contains("\"chunkLoadDetailMode\""));
        assertTrue(rewritten.contains("\"minimapSize\": 256"));
    }

    @Test
    void structureVisibilityProfilesRoundTrip(@TempDir final Path tmp) {
        final Path file = tmp.resolve("config.json");
        final ConfigIo io = new ConfigIo(file, LOGGER);
        final ConfluxConfig config = new ConfluxConfig();
        config.predictionStructureVisibility.setVisible(
            21, DimensionId.OVERWORLD, StructureIndex.StructureType.VILLAGE, false
        );

        io.save(config);
        final ConfluxConfig loaded = io.load();

        assertFalse(loaded.predictionStructureVisibility.isVisible(
            21, DimensionId.OVERWORLD, StructureIndex.StructureType.VILLAGE
        ));
        assertTrue(loaded.predictionStructureVisibility.isVisible(
            30, DimensionId.OVERWORLD, StructureIndex.StructureType.VILLAGE
        ));
    }

    @Test
    void loadLeavesUpToDateFileUntouched(@TempDir final Path tmp) throws IOException {
        final Path file = tmp.resolve("config.json");
        final ConfigIo io = new ConfigIo(file, LOGGER);
        io.save(new ConfluxConfig());
        final FileTime stamp = FileTime.fromMillis(1_000_000L);
        Files.setLastModifiedTime(file, stamp);

        io.load();

        assertEquals(stamp, Files.getLastModifiedTime(file));
    }

    @Test
    void loadKeepsNewerSchemaFileIntact(@TempDir final Path tmp) throws IOException {
        final Path file = tmp.resolve("config.json");
        final String futureJson = "{\"schemaVersion\": 2, \"futureField\": true}";
        Files.writeString(file, futureJson, StandardCharsets.UTF_8);

        final ConfluxConfig loaded = new ConfigIo(file, LOGGER).load();

        assertEquals(2, loaded.schemaVersion);
        assertEquals(futureJson, Files.readString(file, StandardCharsets.UTF_8));
    }
}
