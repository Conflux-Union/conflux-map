package cn.net.rms.confluxmap.mc.color;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.compat.Ids;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;

/** Clears {@link SpriteColorSampler}'s per-BlockState cache whenever resources (a resource pack) reload. */
public final class ColorReloadListener implements SimpleSynchronousResourceReloadListener {
    private static final Identifier ID = Ids.of(ConfluxMapMod.ID, "sprite_color_cache");

    private final SpriteColorSampler sampler;

    public ColorReloadListener(final SpriteColorSampler sampler) {
        this.sampler = sampler;
    }

    @Override
    public Identifier getFabricId() {
        return ID;
    }

    @Override
    public void reload(final ResourceManager manager) {
        sampler.clearCache();
    }
}
