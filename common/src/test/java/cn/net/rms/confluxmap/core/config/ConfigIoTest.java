package cn.net.rms.confluxmap.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.color.MapColorStyle;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.predict.StructureIndex;
import cn.net.rms.confluxmap.core.survey.SurveyReminderSchedule;
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
    void mapColorStyleRoundTripsCopiesAndDefaultsSafely(@TempDir final Path tmp) throws IOException {
        final ConfigIo io = new ConfigIo(tmp.resolve("config.json"), LOGGER);
        final ConfluxConfig config = new ConfluxConfig();
        config.mapColorStyle = MapColorStyle.XAERO;

        io.save(config);

        assertEquals(MapColorStyle.XAERO, io.load().mapColorStyle);
        assertEquals(MapColorStyle.XAERO, config.copy().mapColorStyle);
        config.mapColorStyle = null;
        config.normalize();
        assertEquals(MapColorStyle.CONFLUX, config.mapColorStyle);
    }

    @Test
    void loadCreatesNewConfigWithSmallerDefaultMinimap(@TempDir final Path tmp) throws IOException {
        final Path file = tmp.resolve("config.json");

        final ConfluxConfig loaded = new ConfigIo(file, LOGGER).load();

        assertEquals(90, ConfluxConfig.DEFAULT_MINIMAP_SIZE);
        assertEquals(ConfluxConfig.DEFAULT_MINIMAP_SIZE, loaded.minimapSize);
        assertEquals(ConfluxConfig.DEFAULT_RADAR_ICON_SIZE, loaded.radarIconSize);
        assertTrue(loaded.minimapHudAvoidance);
        assertEquals(MapColorStyle.CONFLUX, loaded.mapColorStyle);
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("\"minimapSize\": 90"));
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("\"minimapHudAvoidance\": true"));
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("\"radarIconSize\": 10"));
    }

    @Test
    void loadFillsMissingFieldsAndRewritesFile(@TempDir final Path tmp) throws IOException {
        final Path file = tmp.resolve("config.json");
        Files.writeString(file, "{\"schemaVersion\":1,\"minimapSize\":300}", StandardCharsets.UTF_8);

        final ConfluxConfig loaded = new ConfigIo(file, LOGGER).load();

        // Absent fields keep their defaults; out-of-range values are clamped.
        assertEquals(new ConfluxConfig().predictionDebounceMs, loaded.predictionDebounceMs);
        assertEquals(
            ConfluxConfig.DEFAULT_PREDICTION_STRUCTURE_ICON_HIDE_ZOOM,
            loaded.predictionStructureIconHideZoom
        );
        assertTrue(loaded.annotationsOnHud);
        assertEquals(ConfluxConfig.DEFAULT_ANNOTATION_ERASER_SIZE, loaded.annotationEraserSize);
        assertEquals(new ConfluxConfig().fullscreenDisplayMode, loaded.fullscreenDisplayMode);
        assertEquals(ConfluxConfig.DEFAULT_TELEPORT_COMMAND, loaded.teleportCommand);
        assertEquals(new ConfluxConfig().chunkLoadDetailMode, loaded.chunkLoadDetailMode);
        assertEquals(MapColorStyle.CONFLUX, loaded.mapColorStyle);
        assertEquals(ConfluxConfig.DEFAULT_RADAR_ICON_SIZE, loaded.radarIconSize);
        assertTrue(loaded.playerTrailEnabled);
        assertEquals(
            ConfluxConfig.DEFAULT_PLAYER_TRAIL_DURATION_SECONDS,
            loaded.playerTrailDurationSeconds
        );
        assertEquals(ConfluxConfig.DEFAULT_PLAYER_TRAIL_DOT_SIZE, loaded.playerTrailDotSize);
        assertEquals(0L, loaded.surveyReminderGameOpenMillis);
        assertEquals(
            SurveyReminderSchedule.FIRST_DELAY_MILLIS,
            loaded.surveyReminderNextPromptAtMillis
        );
        assertFalse(loaded.surveyReminderDismissed);
        assertEquals(1.0, loaded.minimapPositionX);
        assertEquals(0.0, loaded.minimapPositionY);
        assertTrue(loaded.minimapHudAvoidance);
        assertEquals(ConfluxConfig.SCHEMA_VERSION, loaded.schemaVersion);
        assertEquals(256, loaded.minimapSize);
        // The upgrade is persisted so the on-disk file now carries the full schema.
        final String rewritten = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(rewritten.contains("\"predictionDebounceMs\""));
        assertTrue(rewritten.contains("\"predictionStructureIconHideZoom\""));
        assertFalse(rewritten.contains("\"predictionStructureIconHideScale\""));
        assertFalse(rewritten.contains("\"predictionStructureMaxLod\""));
        assertTrue(rewritten.contains("\"predictionStructureVisibility\""));
        assertTrue(rewritten.contains("\"predictionManualSeeds\""));
        assertTrue(rewritten.contains("\"annotationsOnHud\""));
        assertTrue(rewritten.contains("\"annotationEraserSize\""));
        assertTrue(rewritten.contains("\"fullscreenDisplayMode\""));
        assertTrue(rewritten.contains("\"teleportCommand\""));
        assertTrue(rewritten.contains("\"chunkLoadDetailMode\""));
        assertTrue(rewritten.contains("\"mapColorStyle\""));
        assertTrue(rewritten.contains("\"radarIconSize\""));
        assertTrue(rewritten.contains("\"playerTrailEnabled\""));
        assertTrue(rewritten.contains("\"playerTrailDurationSeconds\""));
        assertFalse(rewritten.contains("\"playerTrailDurationMinutes\""));
        assertTrue(rewritten.contains("\"playerTrailDotSize\""));
        assertTrue(rewritten.contains("\"surveyReminderGameOpenMillis\""));
        assertTrue(rewritten.contains("\"surveyReminderNextPromptAtMillis\""));
        assertTrue(rewritten.contains("\"surveyReminderDismissed\""));
        assertTrue(rewritten.contains("\"minimapPositionX\": 1.0"));
        assertTrue(rewritten.contains("\"minimapPositionY\": 0.0"));
        assertTrue(rewritten.contains("\"minimapHudAvoidance\": true"));
        assertTrue(rewritten.contains("\"minimapSize\": 256"));
    }

    @Test
    void loadMigratesEveryLegacyCornerToTheEquivalentFreePosition(@TempDir final Path tmp) throws IOException {
        final Path file = tmp.resolve("config.json");

        assertLegacyCornerMigration(file, "TOP_LEFT", 0.0, 0.0);
        assertLegacyCornerMigration(file, "TOP_RIGHT", 1.0, 0.0);
        assertLegacyCornerMigration(file, "BOTTOM_LEFT", 0.0, 1.0);
        assertLegacyCornerMigration(file, "BOTTOM_RIGHT", 1.0, 1.0);
    }

    @Test
    void freePositionRoundTripsAndNormalizesInvalidValues(@TempDir final Path tmp) throws IOException {
        final Path file = tmp.resolve("config.json");
        final ConfigIo io = new ConfigIo(file, LOGGER);
        final ConfluxConfig config = new ConfluxConfig();
        config.minimapPositionX = 0.375;
        config.minimapPositionY = 0.625;

        io.save(config);
        final ConfluxConfig loaded = io.load();

        assertEquals(0.375, loaded.minimapPositionX);
        assertEquals(0.625, loaded.minimapPositionY);

        Files.writeString(
            file,
            "{\"schemaVersion\":2,\"minimapPositionX\":-3,\"minimapPositionY\":4}",
            StandardCharsets.UTF_8
        );
        final ConfluxConfig clamped = io.load();
        assertEquals(0.0, clamped.minimapPositionX);
        assertEquals(1.0, clamped.minimapPositionY);
    }

    @Test
    void radarIconSizeRoundTripsAndClampsInvalidValues(@TempDir final Path tmp) throws IOException {
        final Path file = tmp.resolve("config.json");
        final ConfigIo io = new ConfigIo(file, LOGGER);
        final ConfluxConfig config = new ConfluxConfig();
        config.radarIconSize = 6;

        io.save(config);
        assertEquals(6, io.load().radarIconSize);

        Files.writeString(
            file,
            "{\"schemaVersion\":2,\"radarIconSize\":100}",
            StandardCharsets.UTF_8
        );
        assertEquals(ConfluxConfig.MAX_RADAR_ICON_SIZE, io.load().radarIconSize);
    }

    @Test
    void waypointLabelScaleRoundTripsAndClampsInvalidValues(@TempDir final Path tmp) throws IOException {
        final Path file = tmp.resolve("config.json");
        final ConfigIo io = new ConfigIo(file, LOGGER);
        final ConfluxConfig config = new ConfluxConfig();
        assertEquals(ConfluxConfig.DEFAULT_WAYPOINT_LABEL_SCALE_PERCENT, config.waypointLabelScalePercent);
        config.waypointLabelScalePercent = 175;

        io.save(config);
        assertEquals(175, io.load().waypointLabelScalePercent);

        // A file written before this setting existed must land on the default, not on 0.
        Files.writeString(file, "{\"schemaVersion\":5}", StandardCharsets.UTF_8);
        assertEquals(
            ConfluxConfig.DEFAULT_WAYPOINT_LABEL_SCALE_PERCENT,
            io.load().waypointLabelScalePercent
        );

        Files.writeString(
            file,
            "{\"schemaVersion\":6,\"waypointLabelScalePercent\":5000}",
            StandardCharsets.UTF_8
        );
        assertEquals(
            ConfluxConfig.MAX_WAYPOINT_LABEL_SCALE_PERCENT,
            io.load().waypointLabelScalePercent
        );

        Files.writeString(
            file,
            "{\"schemaVersion\":6,\"waypointLabelScalePercent\":0}",
            StandardCharsets.UTF_8
        );
        assertEquals(
            ConfluxConfig.MIN_WAYPOINT_LABEL_SCALE_PERCENT,
            io.load().waypointLabelScalePercent
        );
    }

    @Test
    void playerTrailSettingsRoundTripAndClampInvalidValues(@TempDir final Path tmp) throws IOException {
        final Path file = tmp.resolve("config.json");
        final ConfigIo io = new ConfigIo(file, LOGGER);
        final ConfluxConfig config = new ConfluxConfig();
        config.playerTrailEnabled = false;
        config.playerTrailDurationSeconds = 45;
        config.playerTrailDotSize = 6;

        io.save(config);
        final ConfluxConfig loaded = io.load();
        assertFalse(loaded.playerTrailEnabled);
        assertEquals(45, loaded.playerTrailDurationSeconds);
        assertEquals(6, loaded.playerTrailDotSize);
        assertEquals(1, ConfluxConfig.MIN_PLAYER_TRAIL_DURATION_SECONDS);
        assertEquals(120, ConfluxConfig.MAX_PLAYER_TRAIL_DURATION_SECONDS);

        Files.writeString(
            file,
            "{\"schemaVersion\":3,\"playerTrailDurationSeconds\":1000,\"playerTrailDotSize\":100}",
            StandardCharsets.UTF_8
        );
        final ConfluxConfig clamped = io.load();
        assertEquals(ConfluxConfig.MAX_PLAYER_TRAIL_DURATION_SECONDS, clamped.playerTrailDurationSeconds);
        assertEquals(ConfluxConfig.MAX_PLAYER_TRAIL_DOT_SIZE, clamped.playerTrailDotSize);

        Files.writeString(
            file,
            "{\"schemaVersion\":3,\"playerTrailDurationSeconds\":0}",
            StandardCharsets.UTF_8
        );
        assertEquals(ConfluxConfig.MIN_PLAYER_TRAIL_DURATION_SECONDS, io.load().playerTrailDurationSeconds);
    }

    @Test
    void loadMigratesPlayerTrailDurationFromMinutesToSeconds(@TempDir final Path tmp) throws IOException {
        final Path file = tmp.resolve("config.json");
        Files.writeString(
            file,
            "{\"schemaVersion\":2,\"playerTrailDurationMinutes\":1}",
            StandardCharsets.UTF_8
        );

        final ConfluxConfig loaded = new ConfigIo(file, LOGGER).load();

        assertEquals(60, loaded.playerTrailDurationSeconds);
        final String rewritten = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(rewritten.contains("\"schemaVersion\": " + ConfluxConfig.SCHEMA_VERSION));
        assertTrue(rewritten.contains("\"playerTrailDurationSeconds\": 60"));
        assertFalse(rewritten.contains("\"playerTrailDurationMinutes\""));
    }

    @Test
    void surveyReminderStateRoundTrips(@TempDir final Path tmp) {
        final ConfigIo io = new ConfigIo(tmp.resolve("config.json"), LOGGER);
        final ConfluxConfig config = new ConfluxConfig();
        config.surveyReminderGameOpenMillis = 12_345L;
        config.surveyReminderNextPromptAtMillis = 67_890L;
        config.surveyReminderDismissed = true;

        io.save(config);
        final ConfluxConfig loaded = io.load();

        assertEquals(12_345L, loaded.surveyReminderGameOpenMillis);
        assertEquals(67_890L, loaded.surveyReminderNextPromptAtMillis);
        assertTrue(loaded.surveyReminderDismissed);
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
    void manualSeedProfilesRoundTripByWorldIdentity(@TempDir final Path tmp) {
        final Path file = tmp.resolve("config.json");
        final ConfigIo io = new ConfigIo(file, LOGGER);
        final ConfluxConfig config = new ConfluxConfig();
        final cn.net.rms.confluxmap.core.model.WorldIdentity world =
            cn.net.rms.confluxmap.core.model.WorldIdentity.multiplayer("play.example.net", "survival");
        config.predictionManualSeeds.set(world, "Conflux Map", "1.21.1");

        io.save(config);
        final ManualSeedConfig.Entry loaded = io.load().predictionManualSeeds.get(world).orElseThrow();

        assertEquals("Conflux Map", loaded.seedInput());
        assertEquals(474293735L, loaded.seed());
        assertEquals("1.21.1", loaded.worldgenVersion());
    }

    @Test
    void structureIconHideZoomRoundTripsMigratesAndClampsInvalidValues(@TempDir final Path tmp) throws IOException {
        final Path file = tmp.resolve("config.json");
        final ConfigIo io = new ConfigIo(file, LOGGER);
        final ConfluxConfig config = new ConfluxConfig();
        config.predictionStructureIconHideZoom = 0.14;

        io.save(config);
        assertEquals(0.14, io.load().predictionStructureIconHideZoom);

        Files.writeString(
            file,
            "{\"schemaVersion\":3,\"predictionStructureMaxLod\":1}",
            StandardCharsets.UTF_8
        );
        assertEquals(0.25, io.load().predictionStructureIconHideZoom);

        Files.writeString(
            file,
            "{\"schemaVersion\":4,\"predictionStructureIconHideScale\":0.25}",
            StandardCharsets.UTF_8
        );
        assertEquals(4.0, io.load().predictionStructureIconHideZoom);

        Files.writeString(
            file,
            "{\"schemaVersion\":4,\"predictionStructureIconHideScale\":9.75}",
            StandardCharsets.UTF_8
        );
        assertEquals(1.0 / 9.75, io.load().predictionStructureIconHideZoom);

        Files.writeString(
            file,
            "{\"schemaVersion\":4,\"predictionStructureIconHideScale\":99}",
            StandardCharsets.UTF_8
        );
        assertEquals(0.0625, io.load().predictionStructureIconHideZoom);

        Files.writeString(
            file,
            "{\"schemaVersion\":4,\"predictionStructureIconHideScale\":0}",
            StandardCharsets.UTF_8
        );
        assertEquals(4.0, io.load().predictionStructureIconHideZoom);

        Files.writeString(
            file,
            "{\"schemaVersion\":5,\"predictionStructureIconHideZoom\":99}",
            StandardCharsets.UTF_8
        );
        assertEquals(
            ConfluxConfig.MAX_PREDICTION_STRUCTURE_ICON_HIDE_ZOOM,
            io.load().predictionStructureIconHideZoom
        );

        Files.writeString(
            file,
            "{\"schemaVersion\":5,\"predictionStructureIconHideZoom\":0}",
            StandardCharsets.UTF_8
        );
        assertEquals(
            ConfluxConfig.MIN_PREDICTION_STRUCTURE_ICON_HIDE_ZOOM,
            io.load().predictionStructureIconHideZoom
        );
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
        final int futureSchema = ConfluxConfig.SCHEMA_VERSION + 1;
        final String futureJson = "{\"schemaVersion\": " + futureSchema + ", \"futureField\": true}";
        Files.writeString(file, futureJson, StandardCharsets.UTF_8);

        final ConfluxConfig loaded = new ConfigIo(file, LOGGER).load();

        assertEquals(futureSchema, loaded.schemaVersion);
        assertEquals(futureJson, Files.readString(file, StandardCharsets.UTF_8));
    }

    private static void assertLegacyCornerMigration(
        final Path file,
        final String corner,
        final double expectedX,
        final double expectedY
    ) throws IOException {
        Files.writeString(
            file,
            "{\"schemaVersion\":1,\"minimapCorner\":\"" + corner + "\"}",
            StandardCharsets.UTF_8
        );

        final ConfluxConfig loaded = new ConfigIo(file, LOGGER).load();

        assertEquals(expectedX, loaded.minimapPositionX);
        assertEquals(expectedY, loaded.minimapPositionY);
        assertEquals(ConfluxConfig.SCHEMA_VERSION, loaded.schemaVersion);
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("\"minimapPositionX\": " + expectedX));
    }
}
