package cn.net.rms.confluxmap.core.quality;

import cn.net.rms.confluxmap.core.model.DimensionId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

/** Builds a reproducible random corpus of complete map-tile coordinates. */
public final class PredictionQualityCorpus {
    private static final int BLOCKS_PER_TILE = 256;
    public static final long DEFAULT_CORPUS_SEED = 0xC0F1_17A1_5EEDL;
    public static final int DEFAULT_OVERWORLD_SAMPLES = 8;
    public static final int DEFAULT_END_SAMPLES = 4;
    public static final int DEFAULT_RADIUS_TILES = 64;
    private static final int MAX_RADIUS_TILES = Integer.MAX_VALUE / BLOCKS_PER_TILE;

    public record Sample(DimensionId dimension, int tileX, int tileZ) {
        public String id() {
            return dimension.fileName() + "_" + tileX + "_" + tileZ;
        }
    }

    private PredictionQualityCorpus() {
    }

    public static List<Sample> defaultSamples() {
        return generate(
            DEFAULT_CORPUS_SEED,
            DEFAULT_OVERWORLD_SAMPLES,
            DEFAULT_END_SAMPLES,
            DEFAULT_RADIUS_TILES
        );
    }

    public static List<Sample> generate(
        final long seed,
        final int overworldSamples,
        final int endSamples,
        final int radiusTiles
    ) {
        if (overworldSamples < 0 || endSamples < 0 || radiusTiles < 3 || radiusTiles > MAX_RADIUS_TILES) {
            throw new IllegalArgumentException(
                "sample counts must be non-negative and radius must fit 32-bit block coordinates"
            );
        }
        final long side = 2L * radiusTiles + 1L;
        final long availablePerDimension = side * side - 25L;
        if (overworldSamples > availablePerDimension || endSamples > availablePerDimension) {
            throw new IllegalArgumentException("sample count exceeds the unique coordinates in the requested radius");
        }
        final int totalSamples;
        try {
            totalSamples = Math.addExact(overworldSamples, endSamples);
        } catch (final ArithmeticException overflow) {
            throw new IllegalArgumentException("total sample count exceeds the supported list size", overflow);
        }
        final SplittableRandom random = new SplittableRandom(seed);
        final List<Sample> result = new ArrayList<>(totalSamples);
        append(result, random, DimensionId.OVERWORLD, overworldSamples, radiusTiles);
        append(result, random, DimensionId.END, endSamples, radiusTiles);
        return List.copyOf(result);
    }

    private static void append(
        final List<Sample> result,
        final SplittableRandom random,
        final DimensionId dimension,
        final int count,
        final int radius
    ) {
        final Set<Sample> unique = new LinkedHashSet<>();
        while (unique.size() < count) {
            final int tileX = random.nextInt(-radius, radius + 1);
            final int tileZ = random.nextInt(-radius, radius + 1);
            if (Math.max(Math.abs(tileX), Math.abs(tileZ)) <= 2) {
                continue;
            }
            unique.add(new Sample(dimension, tileX, tileZ));
        }
        result.addAll(unique);
    }
}
