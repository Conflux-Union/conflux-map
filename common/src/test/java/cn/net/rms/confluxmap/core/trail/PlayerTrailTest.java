package cn.net.rms.confluxmap.core.trail;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlayerTrailTest {

    @Test
    void keepsOnlyDistinctPositionsInsideTheRetentionWindow() {
        final PlayerTrail trail = new PlayerTrail();
        final long retention = Duration.ofMinutes(5).toNanos();

        trail.record(10.0, 20.0, 0L, retention);
        trail.record(10.2, 20.2, Duration.ofSeconds(1).toNanos(), retention);
        trail.record(12.0, 20.0, Duration.ofSeconds(2).toNanos(), retention);

        assertEquals(
            List.of(
                new PlayerTrail.Sample(10.0, 20.0, 0L),
                new PlayerTrail.Sample(12.0, 20.0, Duration.ofSeconds(2).toNanos())
            ),
            trail.snapshot(Duration.ofMinutes(4).toNanos(), retention)
        );
        assertEquals(
            List.of(new PlayerTrail.Sample(12.0, 20.0, Duration.ofSeconds(2).toNanos())),
            trail.snapshot(Duration.ofMinutes(5).plusSeconds(1).toNanos(), retention)
        );
    }

    @Test
    void clearSeparatesMapSessionsEvenAtTheSamePosition() {
        final PlayerTrail trail = new PlayerTrail();
        final long retention = Duration.ofMinutes(5).toNanos();
        trail.record(10.0, 20.0, 0L, retention);

        trail.clear();
        trail.record(10.0, 20.0, Duration.ofSeconds(1).toNanos(), retention);

        assertEquals(
            List.of(new PlayerTrail.Sample(10.0, 20.0, Duration.ofSeconds(1).toNanos())),
            trail.snapshot(Duration.ofSeconds(1).toNanos(), retention)
        );
    }
}
