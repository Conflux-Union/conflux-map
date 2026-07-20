package cn.net.rms.confluxmap.core.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.SurfaceKind;
import org.junit.jupiter.api.Test;

class PredictionQualityEvaluatorTest {
    @Test
    void identicalRenderedTilesReceivePerfectScores() {
        final PredictionQualityEvaluator.TileData tile = new PredictionQualityEvaluator.TileData(
            2,
            2,
            new int[] {0xFF112233, 0xFF445566, 0xFF778899, 0xFFAABBCC},
            new short[] {64, 65, 66, 67},
            new byte[] {
                (byte) SurfaceKind.LAND.ordinal(),
                (byte) SurfaceKind.WATER.ordinal(),
                (byte) SurfaceKind.SAND.ordinal(),
                (byte) SurfaceKind.SNOW.ordinal()
            },
            new byte[] {0, 4, 0, 0}
        );

        final PredictionQualityEvaluator.Metrics metrics = PredictionQualityEvaluator.evaluate(tile, tile);

        assertEquals(4, metrics.evaluatedPixels());
        assertEquals(1, metrics.evaluatedWaterPixels());
        assertEquals(1.0, metrics.coverageAccuracy());
        assertEquals(1.0, metrics.surfaceKindAccuracy());
        assertEquals(0.0, metrics.heightMae());
        assertEquals(0.0, metrics.heightBias());
        assertEquals(0.0, metrics.heightP95());
        assertEquals(1.0, metrics.heightWithinTwo());
        assertEquals(1.0, metrics.fluidBucketAccuracy());
        assertEquals(1.0, metrics.colorSimilarity());
        assertEquals(1.0, metrics.structuralSimilarity());
        assertEquals(1.0, metrics.exactEdgeF1());
        assertEquals(1.0, metrics.edgeF1());
        assertEquals(1.0, metrics.combinedScore());
    }

    @Test
    void mismatchesAreReflectedInTheirIndependentMetrics() {
        final PredictionQualityEvaluator.TileData reference = new PredictionQualityEvaluator.TileData(
            2,
            2,
            new int[] {0xFFFF0000, 0xFF000000, 0xFF0000FF, 0xFF00FF00},
            new short[] {10, 20, 30, 40},
            new byte[] {
                (byte) SurfaceKind.LAND.ordinal(),
                (byte) SurfaceKind.WATER.ordinal(),
                (byte) SurfaceKind.LAND.ordinal(),
                (byte) SurfaceKind.WATER.ordinal()
            },
            new byte[] {0, 2, 0, 10}
        );
        final PredictionQualityEvaluator.TileData prediction = new PredictionQualityEvaluator.TileData(
            2,
            2,
            new int[] {0xFFFF0000, 0xFFFFFFFF, 0xFF0000FF, 0x00000000},
            new short[] {11, 25, 30, 40},
            new byte[] {
                (byte) SurfaceKind.LAND.ordinal(),
                (byte) SurfaceKind.WATER.ordinal(),
                (byte) SurfaceKind.SAND.ordinal(),
                (byte) SurfaceKind.VOID.ordinal()
            },
            new byte[] {0, 4, 0, 0}
        );

        final PredictionQualityEvaluator.Metrics metrics = PredictionQualityEvaluator.evaluate(reference, prediction);

        assertEquals(4, metrics.evaluatedPixels());
        assertEquals(1, metrics.evaluatedWaterPixels());
        assertEquals(0.75, metrics.coverageAccuracy());
        assertEquals(2.0 / 3.0, metrics.surfaceKindAccuracy());
        assertEquals(2.0, metrics.heightMae());
        assertEquals(2.0, metrics.heightBias());
        assertEquals(5.0, metrics.heightP95());
        assertEquals(2.0 / 3.0, metrics.heightWithinTwo());
        assertEquals(0.0, metrics.fluidBucketAccuracy());
        assertEquals(2.0 / 3.0, metrics.colorSimilarity());
        assertEquals(2.0 / 3.0, metrics.exactEdgeF1());
        assertEquals(1.0, metrics.edgeF1());
        assertTrue(metrics.structuralSimilarity() >= 0.0 && metrics.structuralSimilarity() <= 1.0);
        assertTrue(metrics.combinedScore() > 0.0 && metrics.combinedScore() < 1.0);
    }

    @Test
    void semanticEdgesAllowOnePixelOfSpatialTolerance() {
        final PredictionQualityEvaluator.TileData reference = semanticSplit(2);
        final PredictionQualityEvaluator.TileData prediction = semanticSplit(3);

        final PredictionQualityEvaluator.Metrics metrics = PredictionQualityEvaluator.evaluate(reference, prediction);

        assertEquals(0.0, metrics.exactEdgeF1());
        assertEquals(1.0, metrics.edgeF1());
    }

    private static PredictionQualityEvaluator.TileData semanticSplit(final int waterFromX) {
        final int width = 5;
        final int height = 3;
        final int length = width * height;
        final int[] pixels = new int[length];
        final short[] surfaceY = new short[length];
        final byte[] kind = new byte[length];
        final byte[] fluidDepth = new byte[length];
        java.util.Arrays.fill(pixels, 0xFF336699);
        java.util.Arrays.fill(surfaceY, (short) 62);
        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                kind[z * width + x] = (byte) (x >= waterFromX
                    ? SurfaceKind.WATER.ordinal()
                    : SurfaceKind.LAND.ordinal());
            }
        }
        return new PredictionQualityEvaluator.TileData(width, height, pixels, surfaceY, kind, fluidDepth);
    }
}
