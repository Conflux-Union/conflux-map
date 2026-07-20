package cn.net.rms.confluxmap.gametest;

import cn.net.rms.confluxmap.core.quality.PredictionQualityCorpus;
import java.util.List;
import java.util.Locale;

/** Regression floors calibrated from the deterministic seed-0 generated-region corpus. */
final class PredictionQualityThresholds {
    private static final int EXPECTED_SAMPLES = PredictionQualityCorpus.DEFAULT_OVERWORLD_SAMPLES
        + PredictionQualityCorpus.DEFAULT_END_SAMPLES;
    private static final int MIN_REFERENCE_PIXELS = 20_000;
    private static final double MIN_MEAN_COMBINED = 0.65;
    private static final double MIN_SAMPLE_COMBINED = 0.45;
    private static final double MIN_MEAN_COVERAGE = 0.97;
    private static final double MIN_MEAN_KIND = 0.75;
    private static final double MAX_MEAN_HEIGHT_MAE = 2.50;
    private static final double MIN_MEAN_HEIGHT_WITHIN_TWO = 0.78;
    private static final double MIN_MEAN_FLUID = 0.50;
    private static final double MIN_MEAN_COLOR = 0.70;
    private static final double MIN_MEAN_STRUCTURAL = 0.35;
    private static final double MIN_MEAN_EDGE = 0.05;

    private PredictionQualityThresholds() {
    }

    static void verify(final List<PredictionQualitySampleResult> results) {
        if (results.size() != EXPECTED_SAMPLES) {
            throw new IllegalStateException(
                "prediction quality corpus expected " + EXPECTED_SAMPLES + " samples, got " + results.size()
            );
        }
        for (final PredictionQualitySampleResult result : results) {
            if (result.metrics().evaluatedPixels() < MIN_REFERENCE_PIXELS) {
                throw new IllegalStateException(
                    "generated tile had too few reference pixels: "
                        + result.sample().id()
                        + "="
                        + result.metrics().evaluatedPixels()
                );
            }
            requireMinimum(
                result.sample().id() + " combined score",
                result.metrics().combinedScore(),
                MIN_SAMPLE_COMBINED
            );
        }
        requireMinimum(
            "mean combined score",
            PredictionQualityAggregate.mean(results, metrics -> metrics.combinedScore()),
            MIN_MEAN_COMBINED
        );
        requireMinimum(
            "mean coverage",
            PredictionQualityAggregate.mean(results, metrics -> metrics.coverageAccuracy()),
            MIN_MEAN_COVERAGE
        );
        requireMinimum(
            "mean surface kind",
            PredictionQualityAggregate.mean(results, metrics -> metrics.surfaceKindAccuracy()),
            MIN_MEAN_KIND
        );
        requireMaximum(
            "mean height MAE",
            PredictionQualityAggregate.mean(results, metrics -> metrics.heightMae()),
            MAX_MEAN_HEIGHT_MAE
        );
        requireMinimum(
            "mean height within two",
            PredictionQualityAggregate.mean(results, metrics -> metrics.heightWithinTwo()),
            MIN_MEAN_HEIGHT_WITHIN_TWO
        );
        requireMinimum(
            "water-weighted fluid bucket",
            PredictionQualityAggregate.waterWeightedFluidAccuracy(results),
            MIN_MEAN_FLUID
        );
        requireMinimum(
            "mean color similarity",
            PredictionQualityAggregate.mean(results, metrics -> metrics.colorSimilarity()),
            MIN_MEAN_COLOR
        );
        requireMinimum(
            "mean structural similarity",
            PredictionQualityAggregate.mean(results, metrics -> metrics.structuralSimilarity()),
            MIN_MEAN_STRUCTURAL
        );
        requireMinimum(
            "mean edge F1",
            PredictionQualityAggregate.mean(results, metrics -> metrics.edgeF1()),
            MIN_MEAN_EDGE
        );
    }

    private static void requireMinimum(final String name, final double actual, final double minimum) {
        if (actual < minimum) {
            throw failure(name, actual, ">=", minimum);
        }
    }

    private static void requireMaximum(final String name, final double actual, final double maximum) {
        if (actual > maximum) {
            throw failure(name, actual, "<=", maximum);
        }
    }

    private static IllegalStateException failure(
        final String name,
        final double actual,
        final String operator,
        final double expected
    ) {
        return new IllegalStateException(String.format(
            Locale.ROOT,
            "prediction quality %s was %.6f, expected %s %.6f",
            name,
            actual,
            operator,
            expected
        ));
    }
}
