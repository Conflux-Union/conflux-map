package cn.net.rms.confluxmap.core.survey;

/** Reserved client-local click value used by the survey reminder opt-out action. */
public final class SurveyReminderClickPayload {
    private static final String DISMISS = "confluxmap:survey:dismiss";

    private SurveyReminderClickPayload() {
    }

    public static String dismiss() {
        return DISMISS;
    }

    public static boolean isDismiss(final String value) {
        return DISMISS.equals(value);
    }
}
