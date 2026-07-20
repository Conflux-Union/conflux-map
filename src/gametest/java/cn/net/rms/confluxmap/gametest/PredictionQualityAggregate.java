package cn.net.rms.confluxmap.gametest;

import cn.net.rms.confluxmap.core.quality.PredictionQualityEvaluator;
import java.util.List;
import java.util.function.ToDoubleFunction;

/** Shared aggregate calculations used by both reports and quality gates. */
final class PredictionQualityAggregate {
    private PredictionQualityAggregate() {
    }

    static double mean(
        final List<PredictionQualitySampleResult> results,
        final ToDoubleFunction<PredictionQualityEvaluator.Metrics> metric
    ) {
        return results.stream().mapToDouble(result -> metric.applyAsDouble(result.metrics())).average().orElse(0.0);
    }

    static double minimum(
        final List<PredictionQualitySampleResult> results,
        final ToDoubleFunction<PredictionQualityEvaluator.Metrics> metric
    ) {
        return results.stream().mapToDouble(result -> metric.applyAsDouble(result.metrics())).min().orElse(0.0);
    }

    static double waterWeightedFluidAccuracy(final List<PredictionQualitySampleResult> results) {
        long waterPixels = 0L;
        double correctBuckets = 0.0;
        for (final PredictionQualitySampleResult result : results) {
            final PredictionQualityEvaluator.Metrics metrics = result.metrics();
            waterPixels += metrics.evaluatedWaterPixels();
            correctBuckets += metrics.fluidBucketAccuracy() * metrics.evaluatedWaterPixels();
        }
        return waterPixels == 0L ? 1.0 : correctBuckets / waterPixels;
    }
}
