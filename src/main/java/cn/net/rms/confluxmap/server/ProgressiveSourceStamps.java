package cn.net.rms.confluxmap.server;

import java.util.function.IntToLongFunction;
import java.util.function.LongSupplier;

/** Source versions captured by a progressive tile scan and checked again before reuse. */
final class ProgressiveSourceStamps {
    enum Validation {
        IN_PROGRESS,
        FRESH,
        STALE
    }

    private final long[] mcaMtimes;
    private final long[] liveEpochs;
    private final boolean[] recorded;
    private boolean stable = true;
    private int validationCursor;

    ProgressiveSourceStamps(final int regionCount) {
        if (regionCount <= 0) {
            throw new IllegalArgumentException("regionCount must be positive");
        }
        mcaMtimes = new long[regionCount];
        liveEpochs = new long[regionCount];
        recorded = new boolean[regionCount];
    }

    void record(
        final int regionIndex,
        final long mtimeBefore,
        final long mtimeAfter,
        final long liveEpochBefore,
        final long liveEpochAfter
    ) {
        if (regionIndex < 0 || regionIndex >= recorded.length) {
            throw new IllegalArgumentException("region index outside scan: " + regionIndex);
        }
        recorded[regionIndex] = true;
        mcaMtimes[regionIndex] = mtimeAfter;
        liveEpochs[regionIndex] = liveEpochAfter;
        stable &= mtimeBefore == mtimeAfter && liveEpochBefore == liveEpochAfter;
    }

    boolean stableScan() {
        if (!stable) {
            return false;
        }
        for (final boolean value : recorded) {
            if (!value) {
                return false;
            }
        }
        return true;
    }

    void restartValidation() {
        validationCursor = 0;
    }

    Validation validate(
        final IntToLongFunction currentMtime,
        final IntToLongFunction currentLiveEpoch,
        final int maxRegions,
        final long maxNanos,
        final LongSupplier nanoClock
    ) {
        if (!stableScan()) {
            return Validation.STALE;
        }
        if (maxRegions <= 0 || maxNanos <= 0L) {
            return Validation.IN_PROGRESS;
        }
        final long started = nanoClock.getAsLong();
        int checked = 0;
        while (validationCursor < recorded.length && checked < maxRegions) {
            final int region = validationCursor++;
            if (currentMtime.applyAsLong(region) != mcaMtimes[region]
                || currentLiveEpoch.applyAsLong(region) != liveEpochs[region]) {
                validationCursor = 0;
                return Validation.STALE;
            }
            checked++;
            if (nanoClock.getAsLong() - started >= maxNanos) {
                return Validation.IN_PROGRESS;
            }
        }
        if (validationCursor == recorded.length) {
            validationCursor = 0;
            return Validation.FRESH;
        }
        return Validation.IN_PROGRESS;
    }

    @Override
    public String toString() {
        return "ProgressiveSourceStamps{regions=" + recorded.length
            + ", recorded=" + countRecorded() + ", stable=" + stable + '}';
    }

    private int countRecorded() {
        int count = 0;
        for (final boolean value : recorded) {
            count += value ? 1 : 0;
        }
        return count;
    }
}
