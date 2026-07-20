package cn.net.rms.confluxmap.gametest;

import cn.net.rms.confluxmap.core.quality.PredictionQualityCorpus;
import cn.net.rms.confluxmap.core.quality.PredictionQualityEvaluator;

record PredictionQualitySampleResult(
    PredictionQualityCorpus.Sample sample,
    int dominantBiomeId,
    PredictionQualityEvaluator.TileData reference,
    PredictionQualityEvaluator.TileData prediction,
    PredictionQualityEvaluator.Metrics metrics
) {
}
