package cn.net.rms.confluxmap.mc.survey;

import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.core.config.ConfigIo;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.survey.SurveyReminderClickPayload;
import cn.net.rms.confluxmap.core.survey.SurveyReminderSchedule;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Posts the optional survey reminder, which also links the community, after enough cumulative
 * client-open time.
 */
public final class SurveyReminderNotifier {
    public static final String SURVEY_URL = "https://survey.cxu.org.cn/s/zx6xgPHk1Uj0ex0VcFdAV";
    private static final String QQ_GROUP_URL = "https://qm.qq.com/q/Qimxluy2UW";
    private static final String DISCORD_URL = "https://discord.gg/eyFaJhfnV6";

    private static final long CHECKPOINT_MILLIS = TimeUnit.MINUTES.toMillis(5L);

    private final MinecraftClient client;
    private final ConfluxConfig config;
    private final ConfigIo configIo;
    private final SurveyReminderSchedule schedule;

    private long lastClockNanos;
    private long remainderNanos;
    private long unsavedMillis;

    public SurveyReminderNotifier(
        final MinecraftClient client,
        final ConfluxConfig config,
        final ConfigIo configIo
    ) {
        this.client = client;
        this.config = config;
        this.configIo = configIo;
        schedule = SurveyReminderSchedule.restore(
            SurveyReminderSchedule.FIRST_DELAY_MILLIS,
            SurveyReminderSchedule.REPEAT_DELAY_MILLIS,
            new SurveyReminderSchedule.Snapshot(
                config.surveyReminderGameOpenMillis,
                config.surveyReminderNextPromptAtMillis,
                config.surveyReminderDismissed
            )
        );
        lastClockNanos = System.nanoTime();
    }

    public void register() {
        ClientTickEvents.END_CLIENT_TICK.register(ignored -> tick());
    }

    public void dismiss() {
        advanceClock();
        schedule.dismiss();
        persist();
    }

    /** Copies the final partial session into config before the composition root saves it. */
    public void flush() {
        advanceClock();
        copyStateToConfig();
    }

    private void tick() {
        if (takeDueReminder(client.player != null)) {
            //#if MC>=260100
            //$$ client.player.sendSystemMessage(buildMessage());
            //#else
            client.player.sendMessage(buildMessage(), false);
            //#endif
        }
    }

    /**
     * Advances the clock and checkpoints the tally like every tick. True means a due reminder was
     * taken: the repeat delay is started and persisted, and the caller must post it now.
     */
    boolean takeDueReminder(final boolean inWorld) {
        advanceClock();
        if (!schedule.isDue() || !inWorld) {
            if (unsavedMillis >= CHECKPOINT_MILLIS) {
                persist();
            }
            return false;
        }
        schedule.markShown();
        persist();
        return true;
    }

    private void advanceClock() {
        final long now = System.nanoTime();
        final long elapsedNanos = Math.max(0L, now - lastClockNanos);
        lastClockNanos = now;
        if (schedule.isDismissed()) {
            remainderNanos = 0L;
            return;
        }
        final long totalNanos = remainderNanos + elapsedNanos;
        final long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(totalNanos);
        remainderNanos = totalNanos - TimeUnit.MILLISECONDS.toNanos(elapsedMillis);
        schedule.advanceGameOpenTime(elapsedMillis);
        unsavedMillis += elapsedMillis;
    }

    private void persist() {
        copyStateToConfig();
        configIo.save(config);
        unsavedMillis = 0L;
    }

    private void copyStateToConfig() {
        final SurveyReminderSchedule.Snapshot snapshot = schedule.snapshot();
        config.surveyReminderGameOpenMillis = snapshot.gameOpenMillis();
        config.surveyReminderNextPromptAtMillis = snapshot.nextReminderAtMillis();
        config.surveyReminderDismissed = snapshot.dismissed();
    }

    static Text buildMessage() {
        return line("confluxmap.survey.chat.intro")
            .append(link("confluxmap.survey.chat.open", SURVEY_URL))
            .append(line("confluxmap.survey.chat.body"))
            .append(line("confluxmap.survey.chat.community.qq_head"))
            .append(link("confluxmap.survey.chat.community.qq_group", QQ_GROUP_URL))
            .append(line("confluxmap.survey.chat.community.discord_head"))
            .append(link("confluxmap.survey.chat.community.discord", DISCORD_URL))
            .append(line("confluxmap.survey.chat.community.end"))
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
        return Texts.translatable("confluxmap.survey.chat.dismiss")
            .formatted(Formatting.GRAY, Formatting.UNDERLINE)
            .styled(style -> style
                .withClickEvent(Texts.copyToClipboard(SurveyReminderClickPayload.dismiss()))
                .withHoverEvent(Texts.showText(
                    Texts.translatable("confluxmap.survey.chat.dismiss.hover")
                )));
    }
}
