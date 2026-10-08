package cn.net.rms.confluxmap.mc.ui;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.compat.Ids;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;

/** Re-resolves custom portal icon textures after F3+T so a newly added pack texture takes effect. */
public final class PortalIconReloadListener implements SimpleSynchronousResourceReloadListener {
    private static final Identifier ID = Ids.of(ConfluxMapMod.ID, "portal_icons");

    @Override
    public Identifier getFabricId() {
        return ID;
    }

    @Override
    public void reload(final ResourceManager manager) {
        PortalMarkerRenderer.clearTextureCache();
    }
}
