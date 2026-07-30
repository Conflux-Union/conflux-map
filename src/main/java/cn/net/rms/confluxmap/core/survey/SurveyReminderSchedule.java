package cn.net.rms.confluxmap.core.survey;

/** Tracks survey reminders against cumulative time that the game client is open. */
public final class SurveyReminderSchedule {
    public static final long FIRST_DELAY_MILLIS = 3L * 60L * 60L * 1_000L;
    public static final long REPEAT_DELAY_MILLIS = 24L * 60L * 60L * 1_000L;

    private final long repeatDelayMillis;
    private long gameOpenMillis;
    private long nextReminderAtMillis;
    private boolean dismissed;

    private SurveyReminderSchedule(
        final long repeatDelayMillis,
        final Snapshot snapshot
    ) {
        this.repeatDelayMillis = repeatDelayMillis;
        gameOpenMillis = snapshot.gameOpenMillis();
        nextReminderAtMillis = snapshot.nextReminderAtMillis();
        dismissed = snapshot.dismissed();
    }

    public static SurveyReminderSchedule fresh(final long firstDelayMillis, final long repeatDelayMillis) {
        validateDelays(firstDelayMillis, repeatDelayMillis);
        return new SurveyReminderSchedule(
            repeatDelayMillis,
            new Snapshot(0L, firstDelayMillis, false)
        );
    }

    public static SurveyReminderSchedule restore(
        final long firstDelayMillis,
        final long repeatDelayMillis,
        final Snapshot snapshot
    ) {
        validateDelays(firstDelayMillis, repeatDelayMillis);
        return new SurveyReminderSchedule(
            repeatDelayMillis,
            new Snapshot(
                Math.max(0L, snapshot.gameOpenMillis()),
                snapshot.nextReminderAtMillis() <= 0L
                    ? firstDelayMillis
                    : snapshot.nextReminderAtMillis(),
                snapshot.dismissed()
            )
        );
    }

    public void advanceGameOpenTime(final long elapsedMillis) {
        if (elapsedMillis < 0L) {
            throw new IllegalArgumentException("Elapsed time must not be negative");
        }
        gameOpenMillis += elapsedMillis;
    }

    public boolean isDue() {
        return !dismissed && gameOpenMillis >= nextReminderAtMillis;
    }

    public boolean isDismissed() {
        return dismissed;
    }

    /** Starts the repeat delay from the moment the chat reminder was actually shown. */
    public void markShown() {
        nextReminderAtMillis = gameOpenMillis + repeatDelayMillis;
    }

    public void dismiss() {
        dismissed = true;
    }

    public Snapshot snapshot() {
        return new Snapshot(gameOpenMillis, nextReminderAtMillis, dismissed);
    }

    private static void validateDelays(final long firstDelayMillis, final long repeatDelayMillis) {
        if (firstDelayMillis <= 0L || repeatDelayMillis <= 0L) {
            throw new IllegalArgumentException("Reminder delays must be positive");
        }
    }

    public record Snapshot(long gameOpenMillis, long nextReminderAtMillis, boolean dismissed) {
    }
}
