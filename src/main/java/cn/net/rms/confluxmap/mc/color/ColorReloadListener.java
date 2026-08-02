package cn.net.rms.confluxmap.mc.color;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.compat.Ids;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;

/** Clears captured material samples and rebuilds prediction material profiles after a resource reload. */
public final class ColorReloadListener implements SimpleSynchronousResourceReloadListener {
    private static final Identifier ID = Ids.of(ConfluxMapMod.ID, "sprite_color_cache");

    private final MinecraftClient client;
    private final SpriteColorSampler sampler;
    private final Runnable afterReload;

    public ColorReloadListener(
        final MinecraftClient client,
        final SpriteColorSampler sampler,
        final Runnable afterReload
    ) {
        this.client = client;
        this.sampler = sampler;
        this.afterReload = afterReload;
    }

    @Override
    public Identifier getFabricId() {
        return ID;
    }

    @Override
    public void reload(final ResourceManager manager) {
        sampler.clearCache();
        client.execute(afterReload);
    }
}
