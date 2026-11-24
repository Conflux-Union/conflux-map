package cn.net.rms.confluxmap.core.multiworld;

import java.nio.file.Path;
import org.apache.logging.log4j.Logger;

/** Atomic persistence for the server alias registry; pretty-printed because players edit it. */
public final class ServerAliasIo {
    private final JsonStore<ServerAliasRegistry> store;

    public ServerAliasIo(final Path file, final Logger logger) {
        this.store = new JsonStore<>(
            file,
            logger,
            ServerAliasRegistry.class,
            ServerAliasRegistry::new,
            ServerAliasRegistry::normalize,
            "server alias registry"
        );
    }

    public ServerAliasRegistry load() {
        return store.load();
    }

    public void save(final ServerAliasRegistry registry) {
        store.save(registry);
    }
}
