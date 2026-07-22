package cn.net.rms.confluxmap.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertEquals(256, loaded.minimapSize);
        // The upgrade is persisted so the on-disk file now carries the full schema.
        final String rewritten = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(rewritten.contains("\"predictionDebounceMs\""));
        assertTrue(rewritten.contains("\"minimapSize\": 256"));
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
