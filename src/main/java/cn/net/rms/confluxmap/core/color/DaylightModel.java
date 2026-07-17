package cn.net.rms.confluxmap.core.color;

/**
 * Holds the current global day/night daylight factor in {@code [0, 1]} (1 = full daylight,
 * 0 = darkest night), quantized into a small number of buckets so the SURFACE layer's
 * dynamic lighting (see {@link ShadingPipeline#applyDaylight}) only triggers a tile relight
 * when the factor has moved meaningfully, not every single client tick.
 *
 * <p>Single writer, many readers: the client-tick thread ({@code mc.world.McDaylightTracker})
 * is the only caller of {@link #update}, while {@link #factor()} is read by tile-composition
 * worker threads. {@link #factor} is {@code volatile} so a freshly-published value is always
 * visible to those readers without a lock; a reader observing last tick's value instead of
 * this tick's is harmless since the day/night cycle is a many-second drift, not a per-frame
 * concern.
 */
public final class DaylightModel {
    /** Quantization buckets across [0, 1]; coarse enough that a full day/night cycle only relights a few dozen times. */
    private static final int BUCKETS = 32;

    private volatile float factor = 1f;
    private int bucket = bucketOf(1f);

    /** The most recently published daylight factor, clamped to [0, 1]. */
    public float factor() {
        return factor;
    }

    /**
     * Publishes a new raw factor (clamped to [0, 1] here so callers don't each have to).
     * Returns true exactly when this call moved the quantized bucket, i.e. when callers
     * should invalidate cached SURFACE-layer tiles. Main/client-tick thread only.
     */
    public boolean update(final float rawFactor) {
        final float clamped = clamp01(rawFactor);
        factor = clamped;
        final int nextBucket = bucketOf(clamped);
        final boolean changed = nextBucket != bucket;
        bucket = nextBucket;
        return changed;
    }

    private static int bucketOf(final float f) {
        return Math.round(f * (BUCKETS - 1));
    }

    private static float clamp01(final float v) {
        return v < 0f ? 0f : Math.min(v, 1f);
    }
}
