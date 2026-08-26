package cn.net.rms.confluxmap.mc.platform;

import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.core.config.ConfigIo;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.mc.ui.screen.UnsupportedPlatformWarningScreen;
import cn.net.rms.confluxmap.nativepredict.PlatformWarningEnvironment;
import cn.net.rms.confluxmap.nativepredict.UnsupportedPlatformWarningPolicy;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;

/** Opens the unsupported-platform warning once the first main menu is ready. */
public final class UnsupportedPlatformWarningNotifier {
    private final MinecraftClient client;
    private final ConfluxConfig config;
    private final ConfigIo configIo;
    private final PlatformWarningEnvironment.Selection selection;
    private final UnsupportedPlatformWarningPolicy policy;

    public UnsupportedPlatformWarningNotifier(
        final MinecraftClient client,
        final ConfluxConfig config,
        final ConfigIo configIo
    ) {
        this.client = client;
        this.config = config;
        this.configIo = configIo;
        selection = PlatformWarningEnvironment.current();
        policy = new UnsupportedPlatformWarningPolicy(
            selection.platform().officiallySupported()
        );
    }

    public void register() {
        ClientTickEvents.END_CLIENT_TICK.register(ignored -> tick());
    }

    private void tick() {
        if (!policy.shouldShow(
            selection.preview() ? false : config.unsupportedPlatformWarningDismissed
        )) {
            return;
        }
        final Screen current = MinecraftAccess.screen(client);
        if (!(current instanceof TitleScreen)) {
            return;
        }
        policy.markShown();
        MinecraftAccess.setScreen(
            client,
            new UnsupportedPlatformWarningScreen(
                current,
                selection.platform(),
                config,
                configIo
            )
        );
    }
}
