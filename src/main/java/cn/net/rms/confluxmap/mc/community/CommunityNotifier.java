package cn.net.rms.confluxmap.mc.community;

import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.core.community.CommunityInviteClickPayload;
import cn.net.rms.confluxmap.core.config.ConfigIo;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/** Posts the official community invitation once per game launch, as soon as a world is joined. */
public final class CommunityNotifier {
    private static final String QQ_GROUP_URL = "https://qm.qq.com/q/Qimxluy2UW";
    private static final String DISCORD_URL = "https://discord.gg/eyFaJhfnV6";

    private final ConfluxConfig config;
    private final ConfigIo configIo;
    private boolean chatShown;

    public CommunityNotifier(final ConfluxConfig config, final ConfigIo configIo) {
        this.config = config;
        this.configIo = configIo;
    }

    public void register(final MinecraftClient client) {
        ClientTickEvents.END_CLIENT_TICK.register(ignored -> tick(client));
    }

    public void dismiss() {
        config.communityInviteDismissed = true;
        configIo.save(config);
    }

    private void tick(final MinecraftClient client) {
        if (client.player == null) {
            return;
        }
        post(message -> {
            //#if MC>=260100
            //$$ client.player.sendSystemMessage(message);
            //#else
            client.player.sendMessage(message, false);
            //#endif
        });
    }

    void post(final Consumer<Text> sender) {
        if (chatShown || config.communityInviteDismissed) {
            return;
        }
        chatShown = true;
        sender.accept(buildMessage());
    }

    static Text buildMessage() {
        return line("confluxmap.community.chat.head")
            .append(link("confluxmap.community.chat.qq_group", QQ_GROUP_URL))
            .append(line("confluxmap.community.chat.mid"))
            .append(link("confluxmap.community.chat.discord", DISCORD_URL))
            .append(line("confluxmap.community.chat.end"))
            .append(optOut());
    }

    private static MutableText line(final String key) {
        return Texts.translatable(key).formatted(Formatting.YELLOW);
    }

    private static MutableText link(final String labelKey, final String url) {
        return Texts.translatable(labelKey)
            .formatted(Formatting.AQUA, Formatting.UNDERLINE)
            .styled(style -> style
                .withClickEvent(Texts.openUrl(url))
                .withHoverEvent(Texts.showText(Texts.literal(url))));
    }

    private static MutableText optOut() {
        return Texts.translatable("confluxmap.community.chat.dismiss")
            .formatted(Formatting.GRAY, Formatting.UNDERLINE)
            .styled(style -> style
                .withClickEvent(Texts.copyToClipboard(CommunityInviteClickPayload.dismiss()))
                .withHoverEvent(Texts.showText(
                    Texts.translatable("confluxmap.community.chat.dismiss.hover")
                )));
    }
}
