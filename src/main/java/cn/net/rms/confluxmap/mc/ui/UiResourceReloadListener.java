package cn.net.rms.confluxmap.mc.ui;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.compat.Ids;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;

/** Refreshes project-native and Xaero-compatible UI resource selection after F3+T. */
public final class UiResourceReloadListener implements SimpleSynchronousResourceReloadListener {
    private static final Identifier ID = Ids.of(ConfluxMapMod.ID, "ui_theme");

    private final UiResourceTheme theme;

    public UiResourceReloadListener(final UiResourceTheme theme) {
        this.theme = theme;
    }

    @Override
    public Identifier getFabricId() {
        return ID;
    }

    @Override
    public void reload(final ResourceManager manager) {
        theme.reload(manager);
    }
}
