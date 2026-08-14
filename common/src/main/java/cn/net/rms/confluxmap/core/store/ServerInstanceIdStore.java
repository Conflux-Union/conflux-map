package cn.net.rms.confluxmap.core.store;

import java.nio.file.Path;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Persists the UUID identifying one running server, kept beside the companion configuration
 * rather than inside the world save.
 *
 * <p>A world folder is routinely copied - a mirror server synced from a survival server, a test
 * server cloned from production - and the {@linkplain WorldIdStore world UUID} travels with the
 * copy. That makes it evidence of world lineage, not of server identity. This id stays behind
 * because the config directory is outside the save, so a copied world reaches its new server
 * without claiming the original's identity.
 */
public final class ServerInstanceIdStore {
    private static final Logger LOGGER = LogManager.getLogger("ConfluxMap/ServerInstanceIdStore");
    private static final String FILE_NAME = "server_instance.json";

    private ServerInstanceIdStore() {
    }

    /** @param configDir directory holding the companion configuration; created when missing */
    public static UUID loadOrCreate(final Path configDir) {
        return UuidFileStore.loadOrCreate(configDir.resolve(FILE_NAME), LOGGER);
    }
}
