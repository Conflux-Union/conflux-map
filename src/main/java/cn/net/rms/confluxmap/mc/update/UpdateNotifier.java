package cn.net.rms.confluxmap.mc.update;

import cn.net.rms.confluxmap.core.update.UpdateCheckService;
import java.util.Optional;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.LiteralText;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Formatting;

/**
 * Chat-side surface of the update check: once per game launch, as soon as a
 * completed check knows a newer release and the player is in a world, posts one
 * chat line with a clickable download link. The fullscreen-map badge reads the
 * same {@link UpdateCheckService} state, so the two surfaces never disagree.
 */
public final class UpdateNotifier {
    private final MinecraftClient client;
    private final UpdateCheckService updates;
    private boolean chatShown;

    public UpdateNotifier(final MinecraftClient client, final UpdateCheckService updates) {
        this.client = client;
        this.updates = updates;
    }

    public void register() {
        ClientTickEvents.END_CLIENT_TICK.register(c -> tick());
    }

    private void tick() {
        if (chatShown || client.player == null) {
            return;
        }
        final Optional<UpdateCheckService.UpdateInfo> info = updates.available();
        if (info.isEmpty()) {
            return;
        }
        chatShown = true;
        client.player.sendMessage(buildMessage(info.get()), false);
    }

    private static Text buildMessage(final UpdateCheckService.UpdateInfo info) {
        final MutableText link = new TranslatableText("confluxmap.update.chat.link")
            .formatted(Formatting.AQUA, Formatting.UNDERLINE)
            .styled(style -> style
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, info.releaseUrl()))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new LiteralText(info.releaseUrl()))));
        return new TranslatableText("confluxmap.update.chat", info.latestVersion(), info.currentVersion())
            .formatted(Formatting.YELLOW)
            .append(new LiteralText(" "))
            .append(link);
    }
}
