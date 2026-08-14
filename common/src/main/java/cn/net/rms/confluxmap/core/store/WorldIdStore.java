package cn.net.rms.confluxmap.core.store;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Persists the stable UUID shared by every local and companion view of one world root.
 *
 * <p>This identifies the world, not the server hosting it: copying a save copies the file, so two
 * servers sharing a synced world advertise the same value. {@link ServerInstanceIdStore} is what
 * distinguishes the servers themselves.
 */
public final class WorldIdStore {
    private static final Logger LOGGER = LogManager.getLogger("ConfluxMap/WorldIdStore");
    private static final String DIRECTORY_NAME = "confluxmap";
    private static final String FILE_NAME = "world_uuid.json";

    private WorldIdStore() {
    }

    /** Generates the UUID once and keeps it inside the save so deleting the save deletes its identity. */
    public static UUID loadOrCreate(final Path worldRoot) {
        return UuidFileStore.loadOrCreate(
            worldRoot.resolve(DIRECTORY_NAME).resolve(FILE_NAME), LOGGER
        );
    }

    static UUID parse(final String json) {
        return UuidFileStore.parse(json);
    }

    static void writeAtomic(final Path file, final UUID uuid) throws IOException {
        UuidFileStore.writeAtomic(file, uuid);
    }
}
