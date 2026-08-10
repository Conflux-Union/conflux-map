package cn.net.rms.confluxmap.mc.radar;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.compat.Ids;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;

/**
 * Drops model-derived portraits whenever resources reload, so changed entity textures and models
 * are baked again.
 */
public final class EntityIconReloadListener implements SimpleSynchronousResourceReloadListener {
    private static final Identifier ID = Ids.of(ConfluxMapMod.ID, "entity_icons");

    private final EntityIconManager icons;

    public EntityIconReloadListener(final EntityIconManager icons) {
        this.icons = icons;
    }

    @Override
    public Identifier getFabricId() {
        return ID;
    }

    @Override
    public void reload(final ResourceManager manager) {
        icons.invalidateTextures();
    }
}
