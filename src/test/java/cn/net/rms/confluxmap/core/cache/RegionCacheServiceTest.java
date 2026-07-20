package cn.net.rms.confluxmap.core.cache;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.color.DaylightModel;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.store.MapWorldService;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.tile.TileService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegionCacheServiceTest {
    private static final Logger LOGGER = LogManager.getLogger("RegionCacheServiceTest");

    @Test
    void firstSessionMigratesLegacySingleplayerCacheDirectory(@TempDir final Path tempDir) throws IOException {
        final Path saveRoot = createSave(tempDir.resolve("saves").resolve("New World"));
        final WorldIdentity world = WorldIdentity.singleplayerSave(saveRoot);
        final Path legacyDir = tempDir.resolve("local").resolve(world.legacyWorldId());
        final Path currentDir = tempDir.resolve("local").resolve(world.worldId());
        Files.createDirectories(legacyDir);
        Files.writeString(legacyDir.resolve("marker"), "legacy cache");

        final MapExecutors executors = new MapExecutors();
        try {
            final MapWorldService mapWorlds = new MapWorldService();
            final TileService tiles = new TileService(mapWorlds, executors, new ConfluxConfig(), new DaylightModel());
            final RegionCacheService cache = new RegionCacheService(tempDir, mapWorlds, executors, tiles, LOGGER);

            cache.onSessionChanged(new SessionGuard.Session(1L, world, DimensionId.OVERWORLD));

            assertTrue(Files.isRegularFile(currentDir.resolve("marker")));
            assertFalse(Files.exists(legacyDir));
        } finally {
            executors.shutdown(1000L);
        }
    }

    @Test
    void collidingLegacyCacheIsNotAssignedToEitherSave(@TempDir final Path tempDir) throws IOException {
        final Path saves = tempDir.resolve("saves");
        final Path firstSave = createSave(saves.resolve("A B"));
        createSave(saves.resolve("a_b"));
        final WorldIdentity world = WorldIdentity.singleplayerSave(firstSave);
        final Path legacyDir = tempDir.resolve("local").resolve("a_b");
        final Path currentDir = tempDir.resolve("local").resolve(world.worldId());
        Files.createDirectories(legacyDir);
        Files.writeString(legacyDir.resolve("marker"), "mixed legacy cache");

        final MapExecutors executors = new MapExecutors();
        try {
            final MapWorldService mapWorlds = new MapWorldService();
            final TileService tiles = new TileService(mapWorlds, executors, new ConfluxConfig(), new DaylightModel());
            final RegionCacheService cache = new RegionCacheService(tempDir, mapWorlds, executors, tiles, LOGGER);

            cache.onSessionChanged(new SessionGuard.Session(1L, world, DimensionId.OVERWORLD));

            assertFalse(Files.exists(currentDir.resolve("marker")));
            assertTrue(Files.isRegularFile(legacyDir.resolve("marker")));
        } finally {
            executors.shutdown(1000L);
        }
    }

    private static Path createSave(final Path root) throws IOException {
        Files.createDirectories(root);
        Files.writeString(root.resolve("level.dat"), "test save");
        return root;
    }
}
