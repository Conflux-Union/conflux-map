package cn.net.rms.confluxmap;

import net.fabricmc.api.ClientModInitializer;

public final class ConfluxMapClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ConfluxMapMod.LOGGER.info("Conflux Map client services starting");
    }
}
