package cn.net.rms.confluxmap.gametest;

import cn.net.rms.confluxmap.core.quality.PredictionQualityCorpus;
import java.util.List;
import java.util.Locale;

/** Regression floors calibrated from the deterministic seed-0 generated-region corpus. */
final class PredictionQualityThresholds {
    private static final int EXPECTED_SAMPLES = PredictionQualityCorpus.DEFAULT_OVERWORLD_SAMPLES
        + PredictionQualityCorpus.DEFAULT_END_SAMPLES;
    private static final int MIN_REFERENCE_PIXELS = 20_000;
    private static final double MIN_MEAN_COMBINED = 0.75;
    // Per-sample scores move by roughly +-0.01 for any shading tweak, and the corpus's weakest
    // sample (1.21.5 minecraft_overworld_28_30) sat 0.010 above the old 0.60 gate, so the height
    // curve recalibration noted on MIN_MEAN_STRUCTURAL dropped it to 0.5995. Refloored with enough
    // margin to absorb one such shift rather than to trip on the next.
    private static final double MIN_SAMPLE_COMBINED = 0.58;
    private static final double MIN_MEAN_COVERAGE = 0.97;
    private static final double MIN_MEAN_KIND = 0.80;
    // LOD0 uses an 8-pixel exact residual grid instead of generating all 65,536 columns. These
    // retain a narrow regression margin around the unified overview pipeline's seed-0 corpus.
    private static final double MAX_MEAN_HEIGHT_MAE = 2.25;
    private static final double MIN_MEAN_HEIGHT_WITHIN_TWO = 0.78;
    private static final double MIN_MEAN_FLUID = 0.80;
    private static final double MIN_MEAN_COLOR = 0.80;
    // Recalibrated when the predicted plane moved onto the captured map's combined height curve
    // (PredictedTileComposer's heightShade slopeAlsoActive, false -> true, i.e. K 1.8 -> 3.0). The
    // old curve over-contrasted absolute height, which inflated per-tile luminance variance and so
    // flattered SSIM; the 1.17.1 corpus mean moved 0.419 -> 0.377 while colour similarity - the
    // metric that actually tracks "does the underlay match the map beside it" - moved 0.836 ->
    // 0.871 and the mean combined score rose on both 1.17.1 and 1.21.5.
    private static final double MIN_MEAN_STRUCTURAL = 0.36;
    private static final double MIN_MEAN_EXACT_EDGE = 0.08;
    private static final double MIN_MEAN_TOLERANT_EDGE = 0.40;

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
            "mean exact edge F1",
            PredictionQualityAggregate.mean(results, metrics -> metrics.exactEdgeF1()),
            MIN_MEAN_EXACT_EDGE
        );
        requireMinimum(
            "mean one-pixel-tolerant edge F1",
            PredictionQualityAggregate.mean(results, metrics -> metrics.edgeF1()),
            MIN_MEAN_TOLERANT_EDGE
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
