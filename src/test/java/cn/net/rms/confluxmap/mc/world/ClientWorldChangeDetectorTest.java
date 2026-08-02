package cn.net.rms.confluxmap.mc.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ClientWorldChangeDetectorTest {
    @Test
    void requiresTwoWeakSignalsInsideOneObservationWindow() {
        final ClientWorldChangeDetector detector = new ClientWorldChangeDetector();

        assertFalse(detector.observeWeakSignal(10L, ClientWorldChangeDetector.WeakSignal.GAME_MODE));
        assertTrue(detector.observeWeakSignal(20L, ClientWorldChangeDetector.WeakSignal.POSITION));

        detector.reset();
        assertFalse(detector.observeWeakSignal(10L, ClientWorldChangeDetector.WeakSignal.GAME_MODE));
        assertFalse(detector.observeWeakSignal(
            10L + ClientWorldChangeDetector.OBSERVATION_WINDOW_TICKS + 1L,
            ClientWorldChangeDetector.WeakSignal.POSITION
        ));
    }

    @Test
    void escalatesPartialChunkReplacementOnlyWhenItIsCorroborated() {
        final ClientWorldChangeDetector detector = new ClientWorldChangeDetector();

        assertEquals(
            ClientWorldChangeDetector.ReplacementStrength.SUSPECTED,
            detector.replacementStrength(0L, 100, 35)
        );
        assertTrue(detector.observeWeakSignal(1L, ClientWorldChangeDetector.WeakSignal.GAME_MODE));

        detector.reset();
        assertEquals(
            ClientWorldChangeDetector.ReplacementStrength.DEFINITE,
            detector.replacementStrength(0L, 100, 70)
        );
    }

    @Test
    void terrainProbePolicyRateLimitsAndEventuallyExhaustsRetries() {
        final ClientWorldTerrainProbePolicy policy = new ClientWorldTerrainProbePolicy();

        assertTrue(policy.shouldProbe(0L));
        policy.recordAttempt(0L);
        assertFalse(policy.shouldProbe(ClientWorldTerrainProbePolicy.RETRY_INTERVAL_TICKS - 1L));
        assertTrue(policy.shouldProbe(ClientWorldTerrainProbePolicy.RETRY_INTERVAL_TICKS));

        for (int attempt = 1; attempt < ClientWorldTerrainProbePolicy.MAX_ATTEMPTS; attempt++) {
            final long tick = (long) attempt * ClientWorldTerrainProbePolicy.RETRY_INTERVAL_TICKS;
            policy.recordAttempt(tick);
        }

        assertTrue(policy.exhausted());
        assertFalse(policy.shouldProbe(1_000L));
    }
}
