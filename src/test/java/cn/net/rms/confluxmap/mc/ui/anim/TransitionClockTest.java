package cn.net.rms.confluxmap.mc.ui.anim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class TransitionClockTest {
    @Test
    void firstTickReturnsZeroAndLaterTicksReturnElapsedSeconds() {
        final long[] now = {1_000_000_000L};
        final TransitionClock clock = new TransitionClock(() -> now[0]);

        assertEquals(0.0f, clock.tick());

        now[0] += 25_000_000L;
        assertEquals(0.025f, clock.tick(), 1e-6f);

        now[0] += 50_000_000L;
        assertEquals(0.05f, clock.tick(), 1e-6f);
    }

    @Test
    void stallIsCappedSoARenderHitchCannotSkipTheWholeAnimation() {
        final long[] now = {0L};
        final TransitionClock clock = new TransitionClock(() -> now[0]);
        clock.tick();

        now[0] += 2_000_000_000L;
        assertEquals(0.1f, clock.tick(), 1e-6f);
    }

    @Test
    void backwardsTimeIsTreatedAsZero() {
        final long[] now = {1_000_000_000L};
        final TransitionClock clock = new TransitionClock(() -> now[0]);
        clock.tick();

        now[0] -= 500_000_000L;
        assertEquals(0.0f, clock.tick(), 1e-6f);
    }
}
