package cn.net.rms.confluxmap.core.color;

import cn.net.rms.confluxmap.core.util.Argb;

/**
 * Simplified reproduction of cave-nether-layers.md §5.2's block-light/sky-light -> RGB
 * curve, used to darken/tint underground map colors. Pure int/float math, no MC types -
 * {@code mc/} resolves the actual (blockLevel, skyLevel) pair and any lava/magma override
 * (§3's forced block-light-14) and hands them here.
 *
 * <p>The base lookup fixes night vision, lightning flashes, live sky darkening, and torch
 * flicker to a static daytime approximation. Gamma is deliberately not baked into stored
 * columns: composition replaces the lookup tint with a live gamma-adjusted variant, so a
 * vanilla brightness change or Tweakeroo Gamma Override takes effect without invalidating
 * disk caches. The structural inputs remain the nonlinear block/sky curve and each
 * dimension's ambient-light floor (0 normally, ~0.1 for Nether-like dimensions).
 *
 * <p>The gamma-free base is precomputed once per (blockLevel, skyLevel,
 * ambient-floor-variant) at class load. Gamma replacement is CPU tile-composition work,
 * never per-frame shader work.
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

    /**
     * Replaces the zero-light ambient tint already baked into {@code argb} with the tint for
     * {@code blockLevel}. Nether-roof authoritative snapshots and synchronized corrections share
     * this representation, so both can apply the same per-column light plane during composition.
     */
    public static int applyBlockLightOverAmbient(
        final int argb,
        final int blockLevel,
        final boolean netherAmbient
    ) {
        return applyBlockLightOverAmbient(argb, blockLevel, netherAmbient, 0f);
    }

    /** Gamma-aware variant for deferred-light Nether roof pixels. */
    public static int applyBlockLightOverAmbient(
        final int argb,
        final int blockLevel,
        final boolean netherAmbient,
        final float gamma
    ) {
        final int level = clampLevel(blockLevel);
        if (level == 0 && gamma <= 0f) {
            return argb;
        }
        final int ambient = multiplier(0, 0, netherAmbient);
        final int lit = gammaAdjustedMultiplier(level, netherAmbient, gamma);
        return Argb.pack(
            Argb.alpha(argb),
            replaceTint(Argb.red(argb), Argb.red(ambient), Argb.red(lit)),
            replaceTint(Argb.green(argb), Argb.green(ambient), Argb.green(lit)),
            replaceTint(Argb.blue(argb), Argb.blue(ambient), Argb.blue(lit))
        );
    }

    /** Replaces the static tint baked into a cave/Nether/End pixel with its gamma-aware tint. */
    public static int applyGammaOverBakedLight(
        final int argb,
        final int blockLevel,
        final boolean netherAmbient,
        final float gamma
    ) {
        if (gamma <= 0f) {
            return argb;
        }
        final int level = clampLevel(blockLevel);
        final int baked = multiplier(level, 0, netherAmbient);
        final int adjusted = gammaAdjustedMultiplier(level, netherAmbient, gamma);
        return Argb.pack(
            Argb.alpha(argb),
            replaceTint(Argb.red(argb), Argb.red(baked), Argb.red(adjusted)),
            replaceTint(Argb.green(argb), Argb.green(baked), Argb.green(adjusted)),
            replaceTint(Argb.blue(argb), Argb.blue(baked), Argb.blue(adjusted))
        );
    }

    private static int replaceTint(final int channel, final int ambient, final int lit) {
        return Math.min(255, Math.round(channel * (lit / (float) ambient)));
    }

    private static int gammaAdjustedMultiplier(
        final int blockLevel,
        final boolean netherAmbient,
        final float gamma
    ) {
        final int base = multiplier(blockLevel, 0, netherAmbient);
        return Argb.pack(
            255,
            gammaChannel(Argb.red(base), gamma),
            gammaChannel(Argb.green(base), gamma),
            gammaChannel(Argb.blue(base), gamma)
        );
    }

    private static int gammaChannel(final int channel, final float gamma) {
        return Math.round(ShadingPipeline.applyGamma(channel / 255f, gamma) * 255f);
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
