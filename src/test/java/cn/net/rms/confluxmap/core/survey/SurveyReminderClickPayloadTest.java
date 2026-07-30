package cn.net.rms.confluxmap.core.survey;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SurveyReminderClickPayloadTest {

    @Test
    void onlyTheReservedDismissPayloadOptsOut() {
        assertTrue(SurveyReminderClickPayload.isDismiss(SurveyReminderClickPayload.dismiss()));
        assertFalse(SurveyReminderClickPayload.isDismiss("ordinary clipboard text"));
        assertFalse(SurveyReminderClickPayload.isDismiss("confluxmap:survey:dismiss-extra"));
        assertFalse(SurveyReminderClickPayload.isDismiss(null));
    }
}
