package cn.net.rms.confluxmap.core.store;

import cn.net.rms.confluxmap.core.model.WorldIdentity;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.apache.logging.log4j.Logger;

/** Migrates world-scoped files and directories from older singleplayer identity formats. */
public final class WorldStorageMigration {
    private WorldStorageMigration() {
    }

    public static Path directory(final Path root, final WorldIdentity world, final Logger logger) {
        return scopedPath(root, world, "", logger);
    }

    public static Path file(
        final Path root,
        final WorldIdentity world,
        final String suffix,
        final Logger logger
    ) {
        return scopedPath(root, world, suffix, logger);
    }

    private static Path scopedPath(
        final Path root,
        final WorldIdentity world,
        final String suffix,
        final Logger logger
    ) {
        final Path current = root.resolve(world.serverId()).resolve(world.worldId() + suffix);
        for (final String legacyId : world.legacyStorageIds()) {
            if (Files.exists(current)) {
                break;
            }
            final Path legacy = root.resolve(world.serverId()).resolve(legacyId + suffix);
            if (Files.exists(legacy)) {
                migrate(legacy, current, logger);
                break;
            }
        }
        return current;
    }

    private static void migrate(final Path legacy, final Path current, final Logger logger) {
        if (Files.exists(current) || !Files.exists(legacy)) {
            return;
        }
        try {
            Files.createDirectories(current.getParent());
            move(legacy, current);
            logger.info("Migrated world storage from {} to {}", legacy, current);
        } catch (final IOException e) {
            logger.warn("Could not migrate world storage from {} to {}; starting with isolated empty data", legacy, current, e);
        }
    }

    private static void move(final Path source, final Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (final AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }
}
