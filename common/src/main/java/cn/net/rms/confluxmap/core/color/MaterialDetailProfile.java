package cn.net.rms.confluxmap.core.color;

import cn.net.rms.confluxmap.core.util.Argb;
import java.util.Arrays;

/**
 * Immutable 4x4 luminance profile extracted from a material's resource-pack texture. One cell is
 * selected by a stable world-coordinate hash for each map column, turning the texture's real
 * contrast into restrained block-to-block detail without imposing a visibly repeating 4x4 tile.
 */
public final class MaterialDetailProfile {
    public static final int CELLS = 16;
    private static final MaterialDetailProfile FLAT = new MaterialDetailProfile(new double[CELLS]);

    /** Zero-centered brightness offsets; {@code 0.08} means eight percent brighter. */
    private final double[] offsets;

    private MaterialDetailProfile(final double[] offsets) {
        this.offsets = offsets;
    }

    public static MaterialDetailProfile flat() {
        return FLAT;
    }

    /**
     * Builds a mean-preserving profile from sixteen equal-area luminance samples. Contrast is
     * centered after clamping, then uniformly reduced if centering would exceed {@code maxOffset}.
     */
    public static MaterialDetailProfile fromLuminance(final int[] luminance, final double maxOffset) {
        if (luminance.length != CELLS) {
            throw new IllegalArgumentException("luminance must have 16 entries");
        }
        if (maxOffset < 0.0 || maxOffset >= 1.0) {
            throw new IllegalArgumentException("maxOffset must be in [0, 1)");
        }
        double mean = 0.0;
        for (final int value : luminance) {
            mean += Math.max(0, value);
        }
        mean /= CELLS;
        if (mean == 0.0 || maxOffset == 0.0) {
            return flat();
        }

        final double[] offsets = new double[CELLS];
        double offsetMean = 0.0;
        for (int i = 0; i < CELLS; i++) {
            offsets[i] = clamp(Math.max(0, luminance[i]) / mean - 1.0, -maxOffset, maxOffset);
            offsetMean += offsets[i];
        }
        offsetMean /= CELLS;
        double largest = 0.0;
        for (int i = 0; i < CELLS; i++) {
            offsets[i] -= offsetMean;
            largest = Math.max(largest, Math.abs(offsets[i]));
        }
        if (largest > maxOffset) {
            final double scale = maxOffset / largest;
            for (int i = 0; i < CELLS; i++) {
                offsets[i] *= scale;
            }
        }
        return Arrays.stream(offsets).allMatch(value -> value == 0.0)
            ? flat()
            : new MaterialDetailProfile(offsets);
    }

    /** Applies this material's stable world-space brightness variation, preserving alpha. */
    public int apply(final int argb, final int worldX, final int worldZ, final int salt) {
        if (argb == Argb.TRANSPARENT || this == FLAT) {
            return argb;
        }
        final int cell = mix(worldX, worldZ, salt) >>> 28;
        return ShadingPipeline.applyBrightnessMultiplier(argb, 1.0 + offsets[cell]);
    }

    /** Serializable immutable snapshot used by non-Minecraft renderers. */
    public double[] offsets() {
        return offsets.clone();
    }

    private static int mix(final int x, final int z, final int salt) {
        int value = salt * 0x9E3779B9;
        value ^= x * 0x85EBCA6B;
        value = Integer.rotateLeft(value, 13);
        value ^= z * 0xC2B2AE35;
        value ^= value >>> 16;
        value *= 0x7FEB352D;
        value ^= value >>> 15;
        return value;
    }

    private static double clamp(final double value, final double min, final double max) {
        return Math.max(min, Math.min(max, value));
    }
}
