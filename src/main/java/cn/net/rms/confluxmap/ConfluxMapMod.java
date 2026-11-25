package cn.net.rms.confluxmap;

import cn.net.rms.confluxmap.server.ConfluxMapCompanion;
import cn.net.rms.confluxmap.server.ServerConfigIo;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.ModMetadata;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class ConfluxMapMod implements ModInitializer {
    public static final String ID = "confluxmap";
    public static final Logger LOGGER = LogManager.getLogger("ConfluxMap");

    private static String name;
    private static String version;
    private static ConfluxMapCompanion companion;

    public static String getName() {
        return name;
    }

    public static String getVersion() {
        return version;
    }

    public static ConfluxMapCompanion companion() {
        return companion;
    }

    @Override
    public void onInitialize() {
        final ModMetadata metadata = FabricLoader.getInstance()
            .getModContainer(ID)
            .orElseThrow(IllegalStateException::new)
            .getMetadata();
        name = metadata.getName();
        version = metadata.getVersion().getFriendlyString();

        // Global callbacks must exist before an integrated world can be published to LAN. The
        // companion itself stays inactive in local singleplayer and starts on demand after publish.
        companion = new ConfluxMapCompanion(
            new ServerConfigIo(
                FabricLoader.getInstance().getConfigDir().resolve(ID).resolve("server.json"),
                LOGGER
            )
        );
        companion.initialize();

        LOGGER.info("{} {} initialized", name, version);
    }
}
