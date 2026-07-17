package cn.net.rms.confluxmap;

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

    public static String getName() {
        return name;
    }

    public static String getVersion() {
        return version;
    }

    @Override
    public void onInitialize() {
        final ModMetadata metadata = FabricLoader.getInstance()
            .getModContainer(ID)
            .orElseThrow(IllegalStateException::new)
            .getMetadata();
        name = metadata.getName();
        version = metadata.getVersion().getFriendlyString();
        LOGGER.info("{} {} initialized", name, version);
    }
}
