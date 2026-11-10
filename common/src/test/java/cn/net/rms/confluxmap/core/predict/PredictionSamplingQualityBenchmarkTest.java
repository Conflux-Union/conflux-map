package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.nativepredict.McVersions;
import cn.net.rms.confluxmap.nativepredict.NativeLib;
import com.google.gson.GsonBuilder;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Measures residual-density tradeoffs; no quality thresholds are weakened by this harness. */
@Tag("benchmark")
class PredictionSamplingQualityBenchmarkTest {
    private static final long[] SEEDS = {146008555L, 123456789L, 987654321L};
    private static final int SIDE = 32;
    private static final int POINTS = SIDE * SIDE;
    private static final int PIXEL_STEP = 8;
    private static final int PIXEL_OFFSET = 4;
    private static final String[] VARIANTS = {"baseline8_16", "stride16_32", "stride32_32", "noResidual"};
    private static final int[][] STRIDES = {{8, 16}, {16, 32}, {32, 32}};

    @Test
    void measureResidualSamplingQuality() throws Exception {
        assertTrue(NativeLib.initForTests(), "quality audit requires native prediction");
        final Field strideField = LodSampling.class.getDeclaredField("EXACT_CORRECTION_PIXEL_STRIDE");
        strideField.setAccessible(true);
        final int[] mutableStrides = (int[]) strideField.get(null);
        final int[] saved = mutableStrides.clone();
        final List<Map<String, Object>> rows = new ArrayList<>();
        try {
            for (final long seed : SEEDS) {
                final BaselineSampler sampler = new NativeBaselineSampler(
                    McVersions.toCubiomes("1.21.4").orElseThrow(), seed, PredictionDimensions.OVERWORLD, 0);
                for (final Region region : regions(sampler)) {
                    for (int lod = 0; lod <= 1; lod++) {
                        final int bpp = 1 << lod;
                        final BaselineGrid truth = truth(sampler, lod, region.x, region.z);
                        final DerivedGrid truthKinds = BaselineDeriver.derive(truth);
                        BaselineGrid baseline = null;
                        DerivedGrid baselineKinds = null;
                        int[] baselineRender = null;
                        for (int variant = 0; variant < VARIANTS.length; variant++) {
                            final long started = System.nanoTime();
                            final BaselineGrid grid;
                            if (variant < STRIDES.length) {
                                mutableStrides[0] = STRIDES[variant][0];
                                mutableStrides[1] = STRIDES[variant][1];
                                grid = LodSampling.sample(sampler, false, lod, region.x, region.z);
                            } else {
                                grid = withoutResidual(sampler, lod, region.x, region.z);
                            }
                            final double sampleMs = (System.nanoTime() - started) / 1e6;
                            assertNotNull(grid);
                            final DerivedGrid kinds = BaselineDeriver.derive(grid);
                            final DerivedGrid styled = BaselineDeriver.derive(grid);
                            CanopyStylizer.apply(styled, grid, seed, lod, region.x, region.z);
                            final int[] render = PredictedTileComposer.compose(styled, grid,
                                PredictionPalette.defaults(), null, PredictionViewMode.EVERYWHERE, lod);
                            if (variant == 0) {
                                baseline = grid;
                                baselineKinds = kinds;
                                baselineRender = render;
                            }
                            final Map<String, Object> row = new LinkedHashMap<>();
                            row.put("seed", seed);
                            row.put("scene", region.name);
                            row.put("blockX", region.x);
                            row.put("blockZ", region.z);
                            row.put("lod", lod);
                            row.put("blocksPerPixel", bpp);
                            row.put("variant", VARIANTS[variant]);
                            row.put("sampleMsSingleRun", sampleMs);
                            row.put("againstExactColumns", compareSampled(grid, kinds, truth, truthKinds));
                            row.put("againstPr154Baseline", compareSampled(grid, kinds, baseline, baselineKinds));
                            row.put("renderAgainstPr154Baseline", compareRender(baseline, baselineRender, grid, render));
                            rows.add(row);
                            System.out.printf("quality seed=%d %s LOD%d %s: %s%n", seed, region.name, lod,
                                VARIANTS[variant], row.get("againstExactColumns"));
                            writeReport(rows);
                        }
                    }
                }
            }
        } finally {
            System.arraycopy(saved, 0, mutableStrides, 0, saved.length);
        }
    }

    private static BaselineGrid truth(final BaselineSampler sampler, final int lod, final int x, final int z) {
        final int bpp = 1 << lod;
        final int[] solid = new int[POINTS], fluid = new int[POINTS], surface = new int[POINTS], flags = new int[POINTS];
        assertTrue(sampler.surfaceColumns(x + PIXEL_OFFSET * bpp, z + PIXEL_OFFSET * bpp,
            SIDE, SIDE, PIXEL_STEP * bpp, solid, fluid, surface, flags));
        final int[] biomes = new int[POINTS];
        assertTrue(sampler.surfaceBiomes(x + PIXEL_OFFSET * bpp, z + PIXEL_OFFSET * bpp,
            SIDE, SIDE, PIXEL_STEP * bpp, solid, biomes));
        final BaselineGrid grid = new BaselineGrid(lod, x, z);
        for (int j = 0; j < SIDE; j++) {
            for (int i = 0; i < SIDE; i++) {
                final int p = j * SIDE + i;
                final int index = BaselineGrid.index(PIXEL_OFFSET + i * PIXEL_STEP, PIXEL_OFFSET + j * PIXEL_STEP);
                grid.terrainY[index] = solid[p];
                grid.fluidY[index] = fluid[p];
                grid.baseSurfaceY[index] = surface[p];
                grid.surfaceFlags[index] = flags[p];
                grid.biomeId[index] = biomes[p];
            }
        }
        return grid;
    }

    /** Same near-LOD overview/biome/fluid path, with residual correction omitted. */
    private static BaselineGrid withoutResidual(
        final BaselineSampler sampler, final int lod, final int x, final int z
    ) throws Exception {
        final int bpp = 1 << lod;
        final BaselineGrid grid = new BaselineGrid(lod, x, z);
        assertTrue(sampler.overviewHeights(x - bpp, z - bpp, BaselineGrid.SIZE, BaselineGrid.SIZE, bpp, grid.terrainY));
        assertTrue(sampler.surfaceBiomes(x - bpp, z - bpp, BaselineGrid.SIZE, BaselineGrid.SIZE,
            bpp, grid.terrainY, grid.biomeId));
        final Method fluids = LodSampling.class.getDeclaredMethod("resolveOverviewFluids", BaselineGrid.class, int.class, int[].class);
        fluids.setAccessible(true);
        fluids.invoke(null, grid, lod, new int[grid.terrainY.length]);
        return grid;
    }

    private static Map<String, Double> compareSampled(
        final BaselineGrid current, final DerivedGrid kinds, final BaselineGrid reference, final DerivedGrid referenceKinds
    ) {
        long heightError = 0, bias = 0;
        int within2 = 0, kindChanged = 0, biomeChanged = 0, waterChanged = 0, waterToLand = 0, landToWater = 0;
        for (int j = 0; j < SIDE; j++) {
            for (int i = 0; i < SIDE; i++) {
                final int index = BaselineGrid.index(PIXEL_OFFSET + i * PIXEL_STEP, PIXEL_OFFSET + j * PIXEL_STEP);
                final int delta = current.terrainY[index] - reference.terrainY[index];
                heightError += Math.abs(delta);
                bias += delta;
                if (Math.abs(delta) <= 2) within2++;
                if (kinds.kind[index] != referenceKinds.kind[index]) kindChanged++;
                if (current.biomeId[index] != reference.biomeId[index]) biomeChanged++;
                final boolean water = (current.surfaceFlags[index] & BaselineGrid.SURFACE_FLUID) != 0;
                final boolean wasWater = (reference.surfaceFlags[index] & BaselineGrid.SURFACE_FLUID) != 0;
                if (water != wasWater) waterChanged++;
                if (wasWater && !water) waterToLand++;
                if (!wasWater && water) landToWater++;
            }
        }
        final Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("heightMae", (double) heightError / POINTS);
        metrics.put("heightBias", (double) bias / POINTS);
        metrics.put("heightWithin2Pct", 100.0 * within2 / POINTS);
        metrics.put("kindChangedPct", 100.0 * kindChanged / POINTS);
        metrics.put("biomeChangedPct", 100.0 * biomeChanged / POINTS);
        metrics.put("fluidChangedPct", 100.0 * waterChanged / POINTS);
        metrics.put("waterToLandPctOfAllSamples", 100.0 * waterToLand / POINTS);
        metrics.put("landToWaterPctOfAllSamples", 100.0 * landToWater / POINTS);
        return metrics;
    }

    /** Match TileVisualSnapshotTest's rendered-color water metric and 8/255 visibility threshold. */
    private static Map<String, Double> compareRender(
        final BaselineGrid baseline, final int[] before, final BaselineGrid current, final int[] after
    ) {
        final Set<Integer> waterColors = new HashSet<>();
        for (int z = 0; z < 256; z++) for (int x = 0; x < 256; x++) {
            final int index = BaselineGrid.index(x, z), p = z * 256 + x;
            if ((baseline.surfaceFlags[index] & BaselineGrid.SURFACE_FLUID) != 0) waterColors.add(before[p]);
            if ((current.surfaceFlags[index] & BaselineGrid.SURFACE_FLUID) != 0) waterColors.add(after[p]);
        }
        long waterPixels = 0, waterToLand = 0, landToWater = 0, visible = 0, changed = 0;
        for (int p = 0; p < before.length; p++) {
            final boolean wasWater = waterColors.contains(before[p]), water = waterColors.contains(after[p]);
            if (wasWater) waterPixels++;
            if (wasWater && !water) waterToLand++;
            if (!wasWater && water) landToWater++;
            if (before[p] != after[p]) changed++;
            for (int shift = 0; shift <= 24; shift += 8) {
                if (Math.abs(((before[p] >>> shift) & 255) - ((after[p] >>> shift) & 255)) >= 8) {
                    visible++;
                    break;
                }
            }
        }
        final Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("oceanToLandPct", waterPixels == 0 ? 0.0 : 100.0 * waterToLand / waterPixels);
        metrics.put("landToOceanPct", waterPixels == 0 ? 0.0 : 100.0 * landToWater / waterPixels);
        metrics.put("visibleDiffPct", 100.0 * visible / before.length);
        metrics.put("changedArgbPct", 100.0 * changed / before.length);
        return metrics;
    }

    private static List<Region> regions(final BaselineSampler sampler) {
        final int side = 128, origin = -1024, step = 16;
        final int[] biomes = new int[side * side];
        assertTrue(sampler.biomesSparseStrided(16, origin, origin, side, side, step, biomes));
        Region coast = null, plains = null, mountain = null;
        for (int radius = 0; radius < side; radius++) for (int z = 1; z < side - 1; z++) for (int x = 1; x < side - 1; x++) {
            if (Math.max(Math.abs(x - side / 2), Math.abs(z - side / 2)) != radius) continue;
            final int biome = biomes[z * side + x];
            final int blockX = (origin + x * step) * 16, blockZ = (origin + z * step) * 16;
            if (plains == null && (biome == 1 || biome == 129)) plains = new Region("plains", blockX - 128, blockZ - 128);
            if (mountain == null && biome >= 178 && biome <= 182) mountain = new Region("mountain", blockX - 128, blockZ - 128);
            if (coast == null && ocean(biome) != ocean(biomes[z * side + x + 1]))
                coast = new Region("coast", blockX, blockZ - 128);
            if (coast != null && plains != null && mountain != null)
                return List.of(coast, plains, mountain, new Region("negative", -5632, -4864));
        }
        assertNotNull(coast, "coastal scene must be found");
        assertNotNull(plains, "plains scene must be found");
        assertNotNull(mountain, "mountain scene must be found");
        return List.of(coast, plains, mountain, new Region("negative", -5632, -4864));
    }

    private static boolean ocean(final int biome) {
        return biome == 0 || biome == 10 || biome == 24 || biome >= 44 && biome <= 50;
    }

    private static void writeReport(final List<Map<String, Object>> rows) throws Exception {
        final Map<String, Object> report = new LinkedHashMap<>();
        report.put("version", "1.21.4");
        report.put("samplesPerTile", POINTS);
        report.put("sampling", "32x32 uniform pixel centers at 4+8*k; exact solid/fluid columns and their production surface biomes");
        report.put("baseline", "PR154 original residual strides 8/16; use saved baseline main/api classpath when production sources change");
        report.put("timingNote", "Single-run sample timings are diagnostic; use PredictionPipelineBenchmarkTest for warmed throughput.");
        report.put("cases", rows);
        final Path working = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        final Path common = working.getFileName().toString().equals("common") ? working : working.resolve("common");
        final Path output = common.resolve("build/reports/prediction-pipeline/sampling-quality.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n");
    }

    private static final class Region {
        final String name;
        final int x, z;
        Region(final String name, final int x, final int z) { this.name = name; this.x = x; this.z = z; }
    }
}
