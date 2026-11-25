package cn.net.rms.confluxmap.core.multiworld;

import java.nio.file.Path;
import org.apache.logging.log4j.Logger;

/** Atomic persistence for the client-owned world profile registry. */
public final class ClientWorldProfileIo {
    private final JsonStore<ClientWorldProfileRegistry> store;

    public ClientWorldProfileIo(final Path file, final Logger logger) {
        this.store = new JsonStore<>(
            file,
            logger,
            ClientWorldProfileRegistry.class,
            ClientWorldProfileRegistry::new,
            ClientWorldProfileRegistry::normalize,
            "client world registry"
        );
    }

    public ClientWorldProfileRegistry load() {
        return store.load();
    }

    public void save(final ClientWorldProfileRegistry registry) {
        store.save(registry);
    }
}
