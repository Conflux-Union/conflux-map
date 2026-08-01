package cn.net.rms.confluxmap.core.predict;

import java.util.Random;

/**
 * Vanilla's fixed, world-seed-independent warm-patch test for the frozen ocean temperature
 * modifier. At sea level a normal frozen ocean freezes except where this modifier returns its
 * warm value; deep frozen ocean has a base temperature above the freezing threshold everywhere,
 * so its ordinary surface remains water and its visible ice comes from placed features.
 */
final class FrozenOceanTemperature {
    private static final int FROZEN_OCEAN = 10;
    private static final int DEEP_FROZEN_OCEAN = 50;

    private static final OctaveSimplexNoise FROZEN_OCEAN_NOISE = new OctaveSimplexNoise(3456L, 3);
    private static final OctaveSimplexNoise FOLIAGE_NOISE = new OctaveSimplexNoise(2345L, 1);

    private FrozenOceanTemperature() {
    }

    static boolean freezesAtSeaLevel(final int biomeId, final int blockX, final int blockZ) {
        if (biomeId == DEEP_FROZEN_OCEAN) {
            return false;
        }
        if (biomeId != FROZEN_OCEAN) {
            return true;
        }

        final double broad = FROZEN_OCEAN_NOISE.sample(blockX * 0.05, blockZ * 0.05) * 7.0;
        final double detail = FOLIAGE_NOISE.sample(blockX * 0.2, blockZ * 0.2);
        final boolean warmPatch = broad + detail < 0.3
            && FOLIAGE_NOISE.sample(blockX * 0.09, blockZ * 0.09) < 0.8;
        return !warmPatch;
    }

    /** The legacy octave layout used by the two static vanilla temperature noises. */
    private static final class OctaveSimplexNoise {
        private final SimplexNoise[] octaves;
        private final double persistence;

        private OctaveSimplexNoise(final long seed, final int octaveCount) {
            final Random random = new Random(seed);
            this.octaves = new SimplexNoise[octaveCount];
            for (int i = 0; i < octaveCount; i++) {
                octaves[i] = new SimplexNoise(random);
            }
            this.persistence = 1.0 / (Math.pow(2.0, octaveCount) - 1.0);
        }

        private double sample(final double x, final double z) {
            double result = 0.0;
            double frequency = 1.0;
            double amplitude = persistence;
            for (final SimplexNoise octave : octaves) {
                result += octave.sample(x * frequency, z * frequency) * amplitude;
                frequency /= 2.0;
                amplitude *= 2.0;
            }
            return result;
        }
    }

    /** Minimal 2D port of vanilla's legacy simplex sampler, including its permutation seeding. */
    private static final class SimplexNoise {
        private static final int[][] GRADIENTS = {
            {1, 1}, {-1, 1}, {1, -1}, {-1, -1},
            {1, 0}, {-1, 0}, {1, 0}, {-1, 0},
            {0, 1}, {0, -1}, {0, 1}, {0, -1}
        };
        private static final double SQRT_3 = Math.sqrt(3.0);
        private static final double SKEW = 0.5 * (SQRT_3 - 1.0);
        private static final double UNSKEW = (3.0 - SQRT_3) / 6.0;

        private final int[] permutation = new int[256];

        private SimplexNoise(final Random random) {
            // Vanilla stores these origins even though the frozen-ocean callers disable origin
            // offsets. Consuming the values is still required to seed the permutation identically.
            random.nextDouble();
            random.nextDouble();
            random.nextDouble();
            for (int i = 0; i < permutation.length; i++) {
                permutation[i] = i;
            }
            for (int i = 0; i < permutation.length; i++) {
                final int swap = i + random.nextInt(256 - i);
                final int value = permutation[i];
                permutation[i] = permutation[swap];
                permutation[swap] = value;
            }
        }

        private double sample(final double x, final double z) {
            final double skew = (x + z) * SKEW;
            final int cellX = floor(x + skew);
            final int cellZ = floor(z + skew);
            final double unskew = (cellX + cellZ) * UNSKEW;
            final double localX = x - (cellX - unskew);
            final double localZ = z - (cellZ - unskew);

            final int stepX;
            final int stepZ;
            if (localX > localZ) {
                stepX = 1;
                stepZ = 0;
            } else {
                stepX = 0;
                stepZ = 1;
            }

            final double middleX = localX - stepX + UNSKEW;
            final double middleZ = localZ - stepZ + UNSKEW;
            final double farX = localX - 1.0 + 2.0 * UNSKEW;
            final double farZ = localZ - 1.0 + 2.0 * UNSKEW;
            final int wrappedX = cellX & 255;
            final int wrappedZ = cellZ & 255;
            final int nearGradient = map(wrappedX + map(wrappedZ)) % 12;
            final int middleGradient = map(wrappedX + stepX + map(wrappedZ + stepZ)) % 12;
            final int farGradient = map(wrappedX + 1 + map(wrappedZ + 1)) % 12;

            return 70.0 * (
                gradient(nearGradient, localX, localZ)
                    + gradient(middleGradient, middleX, middleZ)
                    + gradient(farGradient, farX, farZ)
            );
        }

        private int map(final int value) {
            return permutation[value & 255];
        }

        private static double gradient(final int index, final double x, final double z) {
            double attenuation = 0.5 - x * x - z * z;
            if (attenuation < 0.0) {
                return 0.0;
            }
            attenuation *= attenuation;
            final int[] gradient = GRADIENTS[index];
            return attenuation * attenuation * (gradient[0] * x + gradient[1] * z);
        }

        private static int floor(final double value) {
            final int truncated = (int) value;
            return value < truncated ? truncated - 1 : truncated;
        }
    }
}
