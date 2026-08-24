package cn.net.rms.confluxmap.core.color;

/**
 * Holds the current global day/night factor and live vanilla gamma value. Both are
 * quantized so map lighting only triggers a tile relight when an input has moved
 * meaningfully, not every client tick.
 *
 * <p>Single writer, many readers: the client-tick thread ({@code mc.world.McDaylightTracker})
 * is the only caller of {@link #update}, while tile-composition workers read the immutable
 * {@link State} through one volatile reference. A reader observing last tick's state is harmless
 * since the day/night cycle is a many-second drift, not a per-frame concern.
 */
public final class DaylightModel {
    public record State(float daylight, float gamma) {
    }

    /** Quantization buckets across [0, 1]; coarse enough that a full day/night cycle only relights a few dozen times. */
    private static final int BUCKETS = 32;
    private static final int GAMMA_BUCKETS_PER_UNIT = 64;
    private static final float MAX_GAMMA = 32f;

    private volatile State state = new State(1f, 0f);
    private int bucket = bucketOf(1f);
    private int gammaBucket;

    /** The most recently published daylight factor, clamped to [0, 1]. */
    public float factor() {
        return state.daylight();
    }

    /** The live vanilla gamma option, including values injected by compatible mods. */
    public float gamma() {
        return state.gamma();
    }

    /** Atomic snapshot for consumers that need both values from the same client tick. */
    public State state() {
        return state;
    }

    /**
     * Publishes a new raw factor (clamped to [0, 1] here so callers don't each have to).
     * Returns true exactly when this call moved the quantized daylight bucket. Main/client-tick
     * thread only; the two-input overload also reports gamma bucket changes.
     */
    public boolean update(final float rawFactor) {
        return update(rawFactor, state.gamma());
    }

    /** Publishes daylight and gamma as one relight state. Main/client-tick thread only. */
    public boolean update(final float rawFactor, final float rawGamma) {
        final float clamped = clamp01(rawFactor);
        final float clampedGamma = clampGamma(rawGamma);
        state = new State(clamped, clampedGamma);
        final int nextBucket = bucketOf(clamped);
        final int nextGammaBucket = gammaBucketOf(clampedGamma);
        final boolean changed = nextBucket != bucket || nextGammaBucket != gammaBucket;
        bucket = nextBucket;
        gammaBucket = nextGammaBucket;
        return changed;
    }

    /**
     * Whether two factors fall in the same quantization bucket, i.e. whether the
     * difference between them is below what {@link #update} considers a meaningful
     * daylight change. Used by the render-side tile re-light (see {@code
     * mc.render.TileTextureManager}) so an already-uploaded tile is only rewritten
     * on the same cadence at which resident tiles recompose.
     */
    public static boolean sameBucket(final float a, final float b) {
        return bucketOf(clamp01(a)) == bucketOf(clamp01(b));
    }

    /** Whether two complete lightmap states are visually equivalent for relighting. */
    public static boolean sameBucket(
        final float daylightA,
        final float gammaA,
        final float daylightB,
        final float gammaB
    ) {
        return sameBucket(daylightA, daylightB)
            && sameGammaBucket(gammaA, gammaB);
    }

    public static boolean sameGammaBucket(final float a, final float b) {
        return gammaBucketOf(clampGamma(a)) == gammaBucketOf(clampGamma(b));
    }

    private static int bucketOf(final float f) {
        return Math.round(f * (BUCKETS - 1));
    }

    private static int gammaBucketOf(final float gamma) {
        return Math.round(gamma * GAMMA_BUCKETS_PER_UNIT);
    }

    private static float clamp01(final float v) {
        return v < 0f ? 0f : Math.min(v, 1f);
    }

    private static float clampGamma(final float value) {
        if (!Float.isFinite(value)) {
            return 0f;
        }
        return value < 0f ? 0f : Math.min(value, MAX_GAMMA);
    }
}
