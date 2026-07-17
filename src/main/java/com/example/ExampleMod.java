package com.example;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.ModMetadata;

public final class ExampleMod implements ModInitializer {
    private static final String ID = "modid";
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
    }
}
