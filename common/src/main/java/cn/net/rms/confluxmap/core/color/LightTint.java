package cn.net.rms.confluxmap.core.color;

import cn.net.rms.confluxmap.core.util.Argb;

/**
 * Simplified reproduction of cave-nether-layers.md §5.2's block-light/sky-light -> RGB
 * curve, used to darken/tint underground map colors. Pure int/float math, no MC types -
 * {@code mc/} resolves the actual (blockLevel, skyLevel) pair and any lava/magma override
 * (§3's forced block-light-14) and hands them here.
 *
 * <p>The spec's own curve depends on inputs this codebase does not track anywhere yet
 * (gamma/brightness setting, night-vision duration, lightning-flash state, live
 * time-of-day sky darkening, per-tick torch flicker) - S3's {@link ShadingPipeline}
 * already deferred all of that ("no day/night light table exists yet"). Rather than wire
 * up that whole subsystem for this slice, this class fixes those inputs to a static
 * "plain daytime, no gamma boost" approximation and only varies the two things the spec
 * calls out as structural: the nonlinear block/sky brightness curve and each dimension's
 * ambient-light floor (0 normally, ~0.1 for nether-like dimensions - see {@code
 * McChunkSnapshotFactory#isNetherLayer}). The spec's own confidence notes explicitly say
 * an equivalent substitute curve is fine here, since the exact constants belong to the
 * base game's lightmap shader, not to the mapping logic.
 *
 * <p>Precomputed once per (blockLevel, skyLevel, ambient-floor-variant) at class-load,
 * per the spec's "small lookup table, not per-pixel work" approach - cheap here since we
 * only resolve light once per captured column, not once per rendered frame.
 */
public final class LightTint {
    private static final int LEVELS = 16;
    private static final float BLOCK_FACTOR = 1.5f;
    private static final float SKY_FACTOR = 1.0f;
    private static final float SOFTEN_AMOUNT = 0.04f;
    private static final float SOFTEN_TARGET = 0.75f;
    /** Vanilla's Nether {@code DimensionType.ambientLight}; Overworld/End are 0. */
    private static final float NETHER_AMBIENT_FLOOR = 0.1f;
    /**
     * Map-readability floor, NOT a vanilla constant: a faithfully-vanilla curve renders
     * unlit caves as ~3% brightness, i.e. invisible on the map. The floor compresses the
     * light gradient into [floor, 1] so pitch-black areas stay readable while torch-lit
     * areas still stand out.
     */
    private static final float READABILITY_FLOOR = 0.30f;

    private static final int[] TABLE_NORMAL = build(0.0f);
    private static final int[] TABLE_NETHER = build(NETHER_AMBIENT_FLOOR);

    private LightTint() {
    }

    /**
     * Opaque ARGB multiplier (alpha always 255) for a given block-light/sky-light pair
     * (each clamped to 0-15). Multiply this into a base color with {@link Argb#multiply}.
     */
    public static int multiplier(final int blockLevel, final int skyLevel, final boolean netherAmbient) {
        final int block = clampLevel(blockLevel);
        final int sky = clampLevel(skyLevel);
        final int[] table = netherAmbient ? TABLE_NETHER : TABLE_NORMAL;
        return table[block * LEVELS + sky];
    }

    private static int[] build(final float ambientFloor) {
        final int[] table = new int[LEVELS * LEVELS];
        for (int block = 0; block < LEVELS; block++) {
            for (int sky = 0; sky < LEVELS; sky++) {
                table[block * LEVELS + sky] = computeRgb(block, sky, ambientFloor);
            }
        }
        return table;
    }

    /** §5.2's curve, generically: warm block-light tint, cooler sky-light tint, ambient floor, softening. */
    private static int computeRgb(final int blockLevel, final int skyLevel, final float ambientFloor) {
        final float blockStrength = curve(blockLevel / 15f) * BLOCK_FACTOR;
        final float skyStrength = curve(skyLevel / 15f) * SKY_FACTOR;

        float r = blockStrength;
        float g = blockStrength * ((blockStrength * 0.6f + 0.4f) * 0.6f + 0.4f);
        float b = blockStrength * (blockStrength * blockStrength * 0.6f + 0.4f);

        r = mix(r, 1f, ambientFloor);
        g = mix(g, 1f, ambientFloor);
        b = mix(b, 1f, ambientFloor);

        r += skyStrength;
        g += skyStrength;
        b += skyStrength * 1.05f;

        r = READABILITY_FLOOR + r * (1f - READABILITY_FLOOR);
        g = READABILITY_FLOOR + g * (1f - READABILITY_FLOOR);
        b = READABILITY_FLOOR + b * (1f - READABILITY_FLOOR);

        r = mix(r, SOFTEN_TARGET, SOFTEN_AMOUNT);
        g = mix(g, SOFTEN_TARGET, SOFTEN_AMOUNT);
        b = mix(b, SOFTEN_TARGET, SOFTEN_AMOUNT);

        return Argb.pack(255, toByte(r), toByte(g), toByte(b));
    }

    private static float curve(final float normalizedLevel) {
        return normalizedLevel / (4f - 3f * normalizedLevel);
    }

    private static float mix(final float value, final float target, final float amount) {
        return value + (target - value) * amount;
    }

    private static int toByte(final float v) {
        final float clamped = v < 0f ? 0f : Math.min(v, 1f);
        return Math.round(clamped * 255f);
    }

    private static int clampLevel(final int level) {
        return level < 0 ? 0 : Math.min(level, LEVELS - 1);
    }
}
