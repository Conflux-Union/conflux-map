package cn.net.rms.confluxmap.core.survey;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SurveyReminderScheduleTest {

    @Test
    void firstReminderBecomesDueAfterCumulativeGameOpenTime() {
        final SurveyReminderSchedule schedule = SurveyReminderSchedule.fresh(100L, 500L);

        schedule.advanceGameOpenTime(99L);
        assertFalse(schedule.isDue());

        schedule.advanceGameOpenTime(1L);
        assertTrue(schedule.isDue());
    }

    @Test
    void shownReminderRepeatsAfterAnotherFullInterval() {
        final SurveyReminderSchedule schedule = SurveyReminderSchedule.fresh(100L, 500L);
        schedule.advanceGameOpenTime(150L);

        schedule.markShown();
        assertFalse(schedule.isDue());

        schedule.advanceGameOpenTime(499L);
        assertFalse(schedule.isDue());

        schedule.advanceGameOpenTime(1L);
        assertTrue(schedule.isDue());
    }

    @Test
    void dismissedReminderNeverBecomesDueAgain() {
        final SurveyReminderSchedule schedule = SurveyReminderSchedule.fresh(100L, 500L);
        schedule.advanceGameOpenTime(100L);
        assertTrue(schedule.isDue());

        schedule.dismiss();
        schedule.advanceGameOpenTime(10_000L);

        assertFalse(schedule.isDue());
    }

    @Test
    void restoredScheduleContinuesFromPersistedGameOpenTime() {
        final SurveyReminderSchedule original = SurveyReminderSchedule.fresh(100L, 500L);
        original.advanceGameOpenTime(100L);
        original.markShown();
        original.advanceGameOpenTime(250L);

        final SurveyReminderSchedule restored = SurveyReminderSchedule.restore(
            100L, 500L, original.snapshot()
        );
        restored.advanceGameOpenTime(249L);
        assertFalse(restored.isDue());

        restored.advanceGameOpenTime(1L);
        assertTrue(restored.isDue());
    }

    @Test
    void restoredScheduleNormalizesInvalidPersistedTimes() {
        final SurveyReminderSchedule restored = SurveyReminderSchedule.restore(
            100L,
            500L,
            new SurveyReminderSchedule.Snapshot(-50L, 0L, false)
        );

        assertFalse(restored.isDue());
        restored.advanceGameOpenTime(99L);
        assertFalse(restored.isDue());

        restored.advanceGameOpenTime(1L);
        assertTrue(restored.isDue());
    }

    @Test
    void dismissalSurvivesRestoringPersistedState() {
        final SurveyReminderSchedule original = SurveyReminderSchedule.fresh(100L, 500L);
        original.dismiss();

        final SurveyReminderSchedule restored = SurveyReminderSchedule.restore(
            100L, 500L, original.snapshot()
        );
        restored.advanceGameOpenTime(10_000L);

        assertTrue(restored.isDismissed());
        assertFalse(restored.isDue());
    }
}
