package cn.net.rms.confluxmap.core.store;

import cn.net.rms.confluxmap.core.model.WorldIdentity;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
import org.apache.logging.log4j.Logger;

/**
 * Carries a server's stored data over when its namespace key changes from the world UUID to the
 * instance id, which is what happens the first time a player reconnects after the server gains
 * the {@code SERVER_INSTANCE} capability. Without this the player's map would look erased.
 *
 * <p>Only an unoccupied target is adopted. Two namespaces that both hold data are two histories,
 * and folding them together is not reversible, so that stays an explicit user-confirmed migration.
 *
 * <p>The move is also what keeps the adoption single. A mirror server synced from a survival
 * server advertises the same world UUID and would claim the same old namespace; whichever
 * connects first moves it, and the second finds nothing left to take and starts empty. No separate
 * bookkeeping is needed because the absence of the source directory is the record.
 */
public final class NamespaceAdoption {
    /**
     * One store laid out as {@code <root>/<serverId>/<worldId><suffix>}, matching
     * {@link WorldStorageMigration}. The suffix is empty for directory-per-world stores such as
     * the region cache, and a file extension for single-file stores such as waypoints.
     */
    public record Store(Path root, String suffix) {
        public Store {
            Objects.requireNonNull(root, "root");
            Objects.requireNonNull(suffix, "suffix");
        }
    }

    private NamespaceAdoption() {
    }

    /**
     * @param legacyWorldId namespace the server used before it advertised an instance id. Pass the
     *                      world UUID only: the address-level {@code world} namespace may belong to
     *                      a different upstream world behind the same proxy address, so it is never
     *                      adopted without the player saying so.
     * @return whether any store moved
     */
    public static boolean adopt(
        final List<Store> stores,
        final WorldIdentity identity,
        final String legacyWorldId,
        final Logger logger
    ) {
        if (legacyWorldId == null || legacyWorldId.equals(identity.worldId())) {
            return false;
        }
        boolean moved = false;
        for (final Store store : stores) {
            moved |= adoptOne(store, identity, legacyWorldId, logger);
        }
        return moved;
    }

    private static boolean adoptOne(
        final Store store,
        final WorldIdentity identity,
        final String legacyWorldId,
        final Logger logger
    ) {
        final Path serverDir = store.root().resolve(identity.serverId());
        final Path source = serverDir.resolve(legacyWorldId + store.suffix());
        final Path target = serverDir.resolve(identity.worldId() + store.suffix());
        if (!Files.exists(source) || Files.exists(target)) {
            return false;
        }
        try {
            Files.createDirectories(serverDir);
            move(source, target);
            logger.info(
                "Adopted map data stored under world {} for server instance {} ({})",
                legacyWorldId, identity.worldId(), target
            );
            return true;
        } catch (final IOException | SecurityException e) {
            logger.warn("Could not adopt {} as {}; leaving it in place", source, target, e);
            return false;
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
