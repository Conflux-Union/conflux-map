package cn.net.rms.confluxmap.core.quality;

import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.util.Argb;
import java.util.Arrays;

/** Compares a generated reference tile with its prediction using visual and semantic metrics. */
public final class PredictionQualityEvaluator {
    private static final double COLOR_RANGE = Math.sqrt(3.0 * 255.0 * 255.0);
    private static final double SSIM_C1 = square(0.01 * 255.0);
    private static final double SSIM_C2 = square(0.03 * 255.0);

    public record TileData(
        int width,
        int height,
        int[] pixels,
        short[] surfaceY,
        byte[] kind,
        byte[] fluidDepth
    ) {
        public TileData {
            final int length = Math.multiplyExact(width, height);
            if (width <= 0 || height <= 0
                || pixels.length != length
                || surfaceY.length != length
                || kind.length != length
                || fluidDepth.length != length) {
                throw new IllegalArgumentException("quality tile arrays must match width * height");
            }
        }
    }

    public record Metrics(
        int evaluatedPixels,
        int evaluatedWaterPixels,
        double coverageAccuracy,
        double surfaceKindAccuracy,
        double heightMae,
        double heightP95,
        double heightWithinTwo,
        double fluidBucketAccuracy,
        double colorSimilarity,
        double structuralSimilarity,
        double edgeF1,
        double combinedScore
    ) {
    }

    private PredictionQualityEvaluator() {
    }

    public static Metrics evaluate(final TileData reference, final TileData prediction) {
        requireSameShape(reference, prediction);
        final int length = reference.pixels().length;
        int referenceValid = 0;
        int coverageMatches = 0;
        int overlap = 0;
        int kindMatches = 0;
        int withinTwo = 0;
        int fluidSamples = 0;
        int fluidMatches = 0;
        double heightErrorSum = 0.0;
        double colorSimilaritySum = 0.0;
        final int[] heightErrors = new int[length];
        final double[] referenceLuminance = new double[length];
        final double[] predictionLuminance = new double[length];

        for (int i = 0; i < length; i++) {
            final boolean actualValid = valid(reference, i);
            final boolean predictedValid = valid(prediction, i);
            if (actualValid) {
                referenceValid++;
            }
            if (actualValid == predictedValid) {
                coverageMatches++;
            }
            if (!actualValid || !predictedValid) {
                continue;
            }
            final int error = Math.abs(reference.surfaceY()[i] - prediction.surfaceY()[i]);
            heightErrors[overlap] = error;
            heightErrorSum += error;
            if (error <= 2) {
                withinTwo++;
            }
            if (reference.kind()[i] == prediction.kind()[i]) {
                kindMatches++;
            }
            if (SurfaceKind.byOrdinal(reference.kind()[i]) == SurfaceKind.WATER) {
                fluidSamples++;
                if (fluidBucket(reference.fluidDepth()[i]) == fluidBucket(prediction.fluidDepth()[i])) {
                    fluidMatches++;
                }
            }
            colorSimilaritySum += colorSimilarity(reference.pixels()[i], prediction.pixels()[i]);
            referenceLuminance[overlap] = luminance(reference.pixels()[i]);
            predictionLuminance[overlap] = luminance(prediction.pixels()[i]);
            overlap++;
        }

        Arrays.sort(heightErrors, 0, overlap);
        final double coverage = ratio(coverageMatches, length);
        final double kind = ratio(kindMatches, overlap);
        final double heightMae = overlap == 0 ? Double.POSITIVE_INFINITY : heightErrorSum / overlap;
        final double heightP95 = overlap == 0 ? Double.POSITIVE_INFINITY : heightErrors[p95Index(overlap)];
        final double heightWithinTwo = ratio(withinTwo, overlap);
        final double fluid = fluidSamples == 0 ? 1.0 : ratio(fluidMatches, fluidSamples);
        final double color = overlap == 0 ? 0.0 : colorSimilaritySum / overlap;
        final double structural = structuralSimilarity(referenceLuminance, predictionLuminance, overlap);
        final double edges = edgeF1(reference, prediction);
        final double combined = 0.10 * coverage
            + 0.20 * kind
            + 0.20 * heightWithinTwo
            + 0.10 * fluid
            + 0.20 * color
            + 0.10 * structural
            + 0.10 * edges;
        return new Metrics(
            referenceValid,
            fluidSamples,
            coverage,
            kind,
            heightMae,
            heightP95,
            heightWithinTwo,
            fluid,
            color,
            structural,
            edges,
            combined
        );
    }

    private static void requireSameShape(final TileData reference, final TileData prediction) {
        if (reference.width() != prediction.width() || reference.height() != prediction.height()) {
            throw new IllegalArgumentException("quality tiles must have the same dimensions");
        }
    }

    private static boolean valid(final TileData tile, final int index) {
        final SurfaceKind kind = SurfaceKind.byOrdinal(tile.kind()[index]);
        return Argb.alpha(tile.pixels()[index]) != 0 && kind != SurfaceKind.UNKNOWN && kind != SurfaceKind.VOID;
    }

    private static int fluidBucket(final byte depth) {
        final int value = Byte.toUnsignedInt(depth);
        if (value == 0) {
            return 0;
        }
        if (value <= 3) {
            return 1;
        }
        if (value <= 9) {
            return 2;
        }
        return 3;
    }

    private static double colorSimilarity(final int actual, final int predicted) {
        final int dr = Argb.red(actual) - Argb.red(predicted);
        final int dg = Argb.green(actual) - Argb.green(predicted);
        final int db = Argb.blue(actual) - Argb.blue(predicted);
        return 1.0 - Math.sqrt((double) dr * dr + (double) dg * dg + (double) db * db) / COLOR_RANGE;
    }

    private static double luminance(final int argb) {
        return 0.2126 * Argb.red(argb) + 0.7152 * Argb.green(argb) + 0.0722 * Argb.blue(argb);
    }

    private static double structuralSimilarity(final double[] actual, final double[] predicted, final int count) {
        if (count == 0) {
            return 0.0;
        }
        double actualMean = 0.0;
        double predictedMean = 0.0;
        for (int i = 0; i < count; i++) {
            actualMean += actual[i];
            predictedMean += predicted[i];
        }
        actualMean /= count;
        predictedMean /= count;
        double actualVariance = 0.0;
        double predictedVariance = 0.0;
        double covariance = 0.0;
        for (int i = 0; i < count; i++) {
            final double actualDelta = actual[i] - actualMean;
            final double predictedDelta = predicted[i] - predictedMean;
            actualVariance += actualDelta * actualDelta;
            predictedVariance += predictedDelta * predictedDelta;
            covariance += actualDelta * predictedDelta;
        }
        actualVariance /= count;
        predictedVariance /= count;
        covariance /= count;
        final double score = ((2.0 * actualMean * predictedMean + SSIM_C1) * (2.0 * covariance + SSIM_C2))
            / ((square(actualMean) + square(predictedMean) + SSIM_C1)
                * (actualVariance + predictedVariance + SSIM_C2));
        return clamp01(score);
    }

    private static double edgeF1(final TileData actual, final TileData predicted) {
        int actualEdges = 0;
        int predictedEdges = 0;
        int matchingEdges = 0;
        for (int z = 0; z < actual.height(); z++) {
            for (int x = 0; x < actual.width(); x++) {
                final int index = z * actual.width() + x;
                if (x + 1 < actual.width()) {
                    final int right = index + 1;
                    final boolean actualEdge = edge(actual, index, right);
                    final boolean predictedEdge = edge(predicted, index, right);
                    actualEdges += actualEdge ? 1 : 0;
                    predictedEdges += predictedEdge ? 1 : 0;
                    matchingEdges += actualEdge && predictedEdge ? 1 : 0;
                }
                if (z + 1 < actual.height()) {
                    final int below = index + actual.width();
                    final boolean actualEdge = edge(actual, index, below);
                    final boolean predictedEdge = edge(predicted, index, below);
                    actualEdges += actualEdge ? 1 : 0;
                    predictedEdges += predictedEdge ? 1 : 0;
                    matchingEdges += actualEdge && predictedEdge ? 1 : 0;
                }
            }
        }
        if (actualEdges == 0 && predictedEdges == 0) {
            return 1.0;
        }
        return ratio(2 * matchingEdges, actualEdges + predictedEdges);
    }

    private static boolean edge(final TileData tile, final int first, final int second) {
        return semanticClass(tile, first) != semanticClass(tile, second);
    }

    private static int semanticClass(final TileData tile, final int index) {
        if (!valid(tile, index)) {
            return -1;
        }
        return Byte.toUnsignedInt(tile.kind()[index]);
    }

    private static int p95Index(final int count) {
        return Math.min(count - 1, (int) Math.ceil(count * 0.95) - 1);
    }

    private static double ratio(final int numerator, final int denominator) {
        return denominator == 0 ? 0.0 : numerator / (double) denominator;
    }

    private static double square(final double value) {
        return value * value;
    }

    private static double clamp01(final double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
