package cn.net.rms.confluxmap.mc.world;

import java.util.EnumSet;

/**
 * Combines weak client-side indications before they can suspend a map session. A single game-mode
 * update, movement jump, or partial chunk wave is normal gameplay; two independent indications
 * within one observation window are required before probing begins.
 */
final class ClientWorldChangeDetector {
    static final int OBSERVATION_WINDOW_TICKS = 20;
    private static final double SUSPECTED_REPLACEMENT_RATIO = 0.35D;
    private static final double DEFINITE_REPLACEMENT_RATIO = 0.70D;

    private final EnumSet<WeakSignal> signals = EnumSet.noneOf(WeakSignal.class);
    private long windowStartedAt = Long.MIN_VALUE;

    boolean observeWeakSignal(final long tick, final WeakSignal signal) {
        expire(tick);
        if (signals.isEmpty()) {
            windowStartedAt = tick;
        }
        signals.add(signal);
        return signals.size() >= 2;
    }

    ReplacementStrength replacementStrength(
        final long tick,
        final int expectedChunks,
        final int replacedChunks
    ) {
        expire(tick);
        if (expectedChunks <= 0 || replacedChunks <= 0) {
            return ReplacementStrength.NONE;
        }
        final double ratio = (double) replacedChunks / expectedChunks;
        if (ratio >= DEFINITE_REPLACEMENT_RATIO) {
            return ReplacementStrength.DEFINITE;
        }
        if (ratio >= SUSPECTED_REPLACEMENT_RATIO) {
            return observeWeakSignal(tick, WeakSignal.CHUNK_REPLACEMENT)
                ? ReplacementStrength.DEFINITE
                : ReplacementStrength.SUSPECTED;
        }
        return ReplacementStrength.NONE;
    }

    void expire(final long tick) {
        if (!signals.isEmpty() && tick - windowStartedAt > OBSERVATION_WINDOW_TICKS) {
            signals.clear();
            windowStartedAt = Long.MIN_VALUE;
        }
    }

    void reset() {
        signals.clear();
        windowStartedAt = Long.MIN_VALUE;
    }

    enum WeakSignal {
        CHUNK_REPLACEMENT,
        GAME_MODE,
        POSITION
    }

    enum ReplacementStrength {
        NONE,
        SUSPECTED,
        DEFINITE
    }
}
