package cn.net.rms.confluxmap.mc.ui.hud;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.config.MinimapHudVisibility;
import cn.net.rms.confluxmap.mc.ui.screen.FullscreenMapScreen;
import net.minecraft.client.MinecraftClient;

/**
 * Head checks shared by the HUD-avoidance mixins before they measure their element.
 *
 * <p>The status-effect and toast handlers both open with the same three questions: is the mod
 * loaded with a player present, is HUD avoidance switched on, and is the minimap drawn this
 * frame. Any no here means there is nothing to avoid, so the caller keeps vanilla placement.
 */
public final class MinimapHudGuards {
    private MinimapHudGuards() {
    }

    static boolean overlayVisible() {
        final ConfluxMapClient app = ConfluxMapClient.get();
        final MinecraftClient client = MinecraftClient.getInstance();
        if (app == null || client.player == null) {
            return false;
        }
        final ConfluxConfig config = app.config();
        final var screen = MinecraftAccess.screen(client);
        return MinimapHudVisibility.shouldRender(
            config.minimapEnabled && app.companionSession().minimapAllowed(),
            app.gameBridge().session().active(),
            screen instanceof FullscreenMapScreen,
            MinecraftAccess.isContainerScreen(screen),
            MinecraftAccess.isFullDebugOverlayVisible(client),
            MinecraftAccess.isHudHidden(client)
        );
    }

    public static boolean avoidanceActive() {
        final ConfluxMapClient app = ConfluxMapClient.get();
        final MinecraftClient client = MinecraftClient.getInstance();
        if (app == null || client.player == null) {
            return false;
        }
        if (!app.config().minimapHudAvoidance) {
            return false;
        }
        return overlayVisible();
    }
}
