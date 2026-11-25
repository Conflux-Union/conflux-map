package cn.net.rms.confluxmap.mc.survey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.core.config.ConfigIo;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.survey.SurveyReminderSchedule;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.text.Text;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SurveyReminderNotifierTest {
    private static final Logger LOGGER = LogManager.getLogger("SurveyReminderNotifierTest");

    @TempDir
    Path configDir;

    @Test
    void messageLinksTheSurveyThenTheQqGroupThenTheDiscordCommunity() {
        assumeChatLinesCanBeBuilt();

        final List<Text> links = openedLinks(SurveyReminderNotifier.buildMessage());

        assertEquals(3, links.size());
        assertOpens(links.get(0), "https://survey.cxu.org.cn/s/zx6xgPHk1Uj0ex0VcFdAV");
        assertOpens(links.get(1), "https://qm.qq.com/q/Qimxluy2UW");
        assertOpens(links.get(2), "https://discord.gg/eyFaJhfnV6");
    }

    @Test
    void messageAssemblesItsPartsInReadingOrder() {
        assumeChatLinesCanBeBuilt();

        // Unit tests run before the mod language resources are installed, so translatable
        // components expose their keys here.
        assertEquals(
            "confluxmap.survey.chat.intro"
                + "confluxmap.survey.chat.open"
                + "confluxmap.survey.chat.body"
                + "confluxmap.survey.chat.community.qq_head"
                + "confluxmap.survey.chat.community.qq_group"
                + "confluxmap.survey.chat.community.discord_head"
                + "confluxmap.survey.chat.community.discord"
                + "confluxmap.survey.chat.community.end"
                + "confluxmap.survey.chat.dismiss",
            SurveyReminderNotifier.buildMessage().getString()
        );
    }

    @Test
    void messageEndsWithTheOptOutAction() {
        assumeChatLinesCanBeBuilt();

        final List<Text> parts = SurveyReminderNotifier.buildMessage().getSiblings();
        final Text last = parts.get(parts.size() - 1);

        assertTrue(last.getStyle().isUnderlined());
        assertEquals(
            "confluxmap:survey:dismiss",
            Texts.clickValue(last.getStyle().getClickEvent())
        );
    }

    @Test
    void joiningAWorldBeforeEnoughGameOpenTimeTakesNoReminder() {
        final ConfigIo io = configIo();
        final ConfluxConfig config = io.load();
        config.surveyReminderGameOpenMillis = SurveyReminderSchedule.FIRST_DELAY_MILLIS - 60_000L;

        assertFalse(notifier(config, io).takeDueReminder(true));
    }

    @Test
    void takenReminderIsNotTakenAgainBeforeTheRepeatInterval() {
        final ConfigIo io = configIo();
        final ConfluxConfig config = io.load();
        config.surveyReminderGameOpenMillis = SurveyReminderSchedule.FIRST_DELAY_MILLIS;
        final SurveyReminderNotifier notifier = notifier(config, io);

        assertTrue(notifier.takeDueReminder(true));
        assertFalse(notifier.takeDueReminder(true));
        assertTrue(io.load().surveyReminderNextPromptAtMillis
            >= SurveyReminderSchedule.FIRST_DELAY_MILLIS + SurveyReminderSchedule.REPEAT_DELAY_MILLIS);
    }

    @Test
    void dueReminderWaitsUntilAWorldIsJoined() {
        final ConfigIo io = configIo();
        final ConfluxConfig config = io.load();
        config.surveyReminderGameOpenMillis = SurveyReminderSchedule.FIRST_DELAY_MILLIS;
        final SurveyReminderNotifier notifier = notifier(config, io);

        assertFalse(notifier.takeDueReminder(false));
        assertTrue(notifier.takeDueReminder(true));
    }

    @Test
    void optOutSurvivesARelaunchAndSilencesADueReminder() {
        final ConfigIo io = configIo();
        notifier(io.load(), io).dismiss();

        final ConfluxConfig nextLaunch = io.load();
        nextLaunch.surveyReminderGameOpenMillis = SurveyReminderSchedule.FIRST_DELAY_MILLIS;

        assertFalse(notifier(nextLaunch, io).takeDueReminder(true));
    }

    private ConfigIo configIo() {
        return new ConfigIo(configDir.resolve("config.json"), LOGGER);
    }

    /** The client stays null: taking a reminder never reads it, only the tick callback does. */
    private static SurveyReminderNotifier notifier(final ConfluxConfig config, final ConfigIo io) {
        return new SurveyReminderNotifier(null, config, io);
    }

    /**
     * Building the chat line needs hover events, which the 1.18-1.21.4 test JVMs cannot initialize.
     * The scheduling tests never build one, so only the message tests are skipped there.
     */
    private static void assumeChatLinesCanBeBuilt() {
        //#if MC>=11800 && MC<12105
        //$$ Assumptions.abort(
        //$$     "HoverEvent static init pulls vanilla registries that 1.18+ test jars cannot bootstrap"
        //$$ );
        //#endif
    }

    private static List<Text> openedLinks(final Text message) {
        return message.getSiblings().stream()
            .filter(part -> Texts.openUrlValue(part.getStyle().getClickEvent()) != null)
            .toList();
    }

    private static void assertOpens(final Text link, final String url) {
        assertTrue(link.getStyle().isUnderlined());
        assertEquals(url, Texts.openUrlValue(link.getStyle().getClickEvent()));
        assertNotNull(link.getStyle().getHoverEvent());
    }
}
