package cn.net.rms.confluxmap.gametest;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.predict.BaselineDeriver;
import cn.net.rms.confluxmap.core.predict.BaselineGrid;
import cn.net.rms.confluxmap.core.predict.CanopyStylizer;
import cn.net.rms.confluxmap.core.predict.DerivedGrid;
import cn.net.rms.confluxmap.core.predict.LodSampling;
import cn.net.rms.confluxmap.core.predict.NativeBaselineSampler;
import cn.net.rms.confluxmap.core.predict.PredictedTileComposer;
import cn.net.rms.confluxmap.core.predict.PredictionDimensions;
import cn.net.rms.confluxmap.core.predict.PredictionPalette;
import cn.net.rms.confluxmap.core.quality.GeneratedTileComposer;
import cn.net.rms.confluxmap.core.quality.PredictionQualityCorpus;
import cn.net.rms.confluxmap.core.quality.PredictionQualityEvaluator;
import cn.net.rms.confluxmap.nativepredict.McVersions;
import cn.net.rms.confluxmap.nativepredict.NativeLib;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.world.World;

/** Generates complete Vanilla regions and compares their map render with cubiomes prediction. */
public final class PredictionQualityGameTest implements FabricGameTest {
    private static final int CHUNKS_PER_TILE = 16 * 16;
    private static final int TILE_PIXELS = BaselineGrid.PIXELS;
    private static final int TICK_LIMIT = 30_000;

    @GameTest(structureName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = TICK_LIMIT)
    public void generatedRegionsMeetPredictionQualityBaseline(final TestContext context) {
        final ServerWorld testWorld = context.getWorld();
        final long expectedSeed = Long.getLong("confluxmap.quality.world-seed", testWorld.getSeed());
        require(
            context,
            testWorld.getSeed() == expectedSeed,
            "quality world seed does not match the configured seed: expected="
                + expectedSeed
                + " actual="
                + testWorld.getSeed()
        );
        require(context, NativeLib.available(), "native predictor was not initialized by the companion");
        final Path reportDir = Path.of(System.getProperty(
            "confluxmap.quality.report-dir",
            "build/reports/prediction-quality"
        ));
        new QualityRun(context, testWorld.getServer(), testWorld.getSeed(), reportDir).start();
    }

    private static final class QualityRun {
        private final TestContext context;
        private final MinecraftServer server;
        private final long worldSeed;
        private final Path reportDir;
        private final List<PredictionQualityCorpus.Sample> samples = PredictionQualityCorpus.defaultSamples();
        private final List<PredictionQualitySampleResult> results = new ArrayList<>();
        private int sampleIndex;
        private int chunkIndex;

        QualityRun(
            final TestContext context,
            final MinecraftServer server,
            final long worldSeed,
            final Path reportDir
        ) {
            this.context = context;
            this.server = server;
            this.worldSeed = worldSeed;
            this.reportDir = reportDir;
        }

        void start() {
            context.waitAndRun(1L, this::step);
        }

        private void step() {
            try {
                if (sampleIndex >= samples.size()) {
                    finish();
                    return;
                }
                final PredictionQualityCorpus.Sample sample = samples.get(sampleIndex);
                final ServerWorld world = world(sample.dimension());
                if (chunkIndex < CHUNKS_PER_TILE) {
                    final int localX = chunkIndex & 15;
                    final int localZ = chunkIndex >>> 4;
                    world.getChunk(sample.tileX() * 16 + localX, sample.tileZ() * 16 + localZ);
                    chunkIndex++;
                    context.waitAndRun(1L, this::step);
                    return;
                }
                results.add(evaluate(world, sample));
                sampleIndex++;
                chunkIndex = 0;
                context.waitAndRun(1L, this::step);
            } catch (final Throwable failure) {
                context.throwGameTestException("prediction quality run failed: " + failure);
            }
        }

        private PredictionQualitySampleResult evaluate(
            final ServerWorld world,
            final PredictionQualityCorpus.Sample sample
        ) throws IOException {
            final GeneratedTileComposer.Grid generated = GeneratedWorldTileReader.read(
                world,
                sample.tileX(),
                sample.tileZ()
            );
            final PredictionQualityEvaluator.TileData reference = GeneratedTileComposer.compose(
                generated,
                PredictionPalette.defaults()
            );
            final int nativeDimension = PredictionDimensions.nativeDim(sample.dimension());
            final int mcVersion = McVersions.toCubiomes("1.17.1").orElseThrow();
            final NativeBaselineSampler sampler = new NativeBaselineSampler(mcVersion, worldSeed, nativeDimension);
            final int originX = sample.tileX() * TILE_PIXELS;
            final int originZ = sample.tileZ() * TILE_PIXELS;
            final BaselineGrid baseline = LodSampling.sample(sampler, nativeDimension == PredictionDimensions.END, 0, originX, originZ);
            if (baseline == null) {
                throw new IllegalStateException("native baseline failed for " + sample.id());
            }
            final DerivedGrid derived = BaselineDeriver.derive(baseline);
            CanopyStylizer.apply(derived, baseline, sampler, worldSeed, 0, originX, originZ);
            final int[] predictedPixels = PredictedTileComposer.compose(
                derived,
                baseline,
                PredictionPalette.defaults()
            );
            final PredictionQualityEvaluator.TileData prediction = toPredictionTile(derived, predictedPixels);
            return new PredictionQualitySampleResult(
                sample,
                dominantBiome(generated),
                reference,
                prediction,
                PredictionQualityEvaluator.evaluate(reference, prediction)
            );
        }

        private void finish() throws IOException {
            PredictionQualityReport.write(reportDir, worldSeed, results);
            PredictionQualityThresholds.verify(results);
            context.complete();
        }

        private ServerWorld world(final DimensionId dimension) {
            final ServerWorld world = dimension.equals(DimensionId.END)
                ? server.getWorld(World.END)
                : server.getWorld(World.OVERWORLD);
            if (world == null) {
                throw new IllegalStateException("server did not create " + dimension);
            }
            return world;
        }
    }

    private static PredictionQualityEvaluator.TileData toPredictionTile(
        final DerivedGrid derived,
        final int[] pixels
    ) {
        final int length = TILE_PIXELS * TILE_PIXELS;
        final short[] surfaceY = new short[length];
        final byte[] kind = new byte[length];
        final byte[] fluidDepth = new byte[length];
        for (int z = 0; z < TILE_PIXELS; z++) {
            for (int x = 0; x < TILE_PIXELS; x++) {
                final int destination = z * TILE_PIXELS + x;
                final int source = BaselineGrid.index(x, z);
                surfaceY[destination] = clampShort(derived.surfaceY[source]);
                kind[destination] = derived.kind[source];
                fluidDepth[destination] = (byte) Math.max(0, Math.min(255, derived.fluidDepth[source]));
            }
        }
        return new PredictionQualityEvaluator.TileData(
            TILE_PIXELS,
            TILE_PIXELS,
            pixels,
            surfaceY,
            kind,
            fluidDepth
        );
    }

    private static int dominantBiome(final GeneratedTileComposer.Grid grid) {
        final Map<Integer, Integer> counts = new HashMap<>();
        for (int i = 0; i < grid.biomeId().length; i++) {
            final SurfaceKind kind = SurfaceKind.byOrdinal(grid.kind()[i]);
            if (kind != SurfaceKind.UNKNOWN && kind != SurfaceKind.VOID) {
                counts.merge(grid.biomeId()[i], 1, Integer::sum);
            }
        }
        return counts.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(-1);
    }

    private static short clampShort(final int value) {
        return (short) Math.max(Short.MIN_VALUE + 1, Math.min(Short.MAX_VALUE, value));
    }

    private static void require(final TestContext context, final boolean condition, final String message) {
        if (!condition) {
            context.throwGameTestException(message);
        }
    }
}
