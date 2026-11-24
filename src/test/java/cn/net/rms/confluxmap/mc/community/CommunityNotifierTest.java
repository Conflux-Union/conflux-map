package cn.net.rms.confluxmap.mc.community;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.core.config.ConfigIo;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.text.Text;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CommunityNotifierTest {
    private static final Logger LOGGER = LogManager.getLogger("CommunityNotifierTest");

    @BeforeAll
    static void skipWhereHoverEventStaticInitBreaks() {
        //#if MC>=11800 && MC<12105
        //$$ Assumptions.abort(
        //$$     "HoverEvent static init pulls vanilla registries that 1.18+ test jars cannot bootstrap"
        //$$ );
        //#endif
    }

    @TempDir
    Path configDir;

    @Test
    void messageLinksTheQqGroupThenTheDiscordCommunity() {
        final List<Text> actions = clickableParts(CommunityNotifier.buildMessage());

        assertOpens(actions.get(0), "https://qm.qq.com/q/Qimxluy2UW");
        assertOpens(actions.get(1), "https://discord.gg/eyFaJhfnV6");
    }

    @Test
    void messageEndsWithTheOptOutAction() {
        final List<Text> parts = CommunityNotifier.buildMessage().getSiblings();
        final Text last = parts.get(parts.size() - 1);

        assertTrue(last.getStyle().isUnderlined());
        assertEquals(
            "confluxmap:community:dismiss",
            Texts.clickValue(last.getStyle().getClickEvent())
        );
    }

    @Test
    void postsTheInvitationOncePerLaunch() {
        final CommunityNotifier notifier = new CommunityNotifier(new ConfluxConfig(), configIo());
        final List<Text> posted = new ArrayList<>();

        notifier.post(posted::add);
        notifier.post(posted::add);

        assertEquals(1, posted.size());
    }

    @Test
    void staysSilentOnEveryLaunchAfterAnOptOut() {
        final ConfigIo io = configIo();
        new CommunityNotifier(io.load(), io).dismiss();

        // The next launch reads its config back from disk, so this proves the opt-out persisted.
        final CommunityNotifier nextLaunch = new CommunityNotifier(io.load(), io);
        final List<Text> posted = new ArrayList<>();
        nextLaunch.post(posted::add);

        assertTrue(posted.isEmpty());
    }

    private ConfigIo configIo() {
        return new ConfigIo(configDir.resolve("config.json"), LOGGER);
    }

    private static List<Text> clickableParts(final Text message) {
        return message.getSiblings().stream()
            .filter(part -> part.getStyle().getClickEvent() != null)
            .toList();
    }

    private static void assertOpens(final Text link, final String url) {
        assertTrue(link.getStyle().isUnderlined());
        assertEquals(url, Texts.openUrlValue(link.getStyle().getClickEvent()));
        assertNotNull(link.getStyle().getHoverEvent());
    }
}
