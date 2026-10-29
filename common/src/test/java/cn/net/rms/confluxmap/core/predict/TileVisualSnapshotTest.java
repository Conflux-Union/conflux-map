package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.nativepredict.McVersions;
import cn.net.rms.confluxmap.nativepredict.NativeLib;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Visual regression gate for predicted Overworld tiles: the verdict is the final rendered pixel,
 * not the intermediate sampled arrays. Each run pushes the fixed corpus (two generator eras x
 * three LODs x three fixed coastal regions) through the production chain
 * ({@link LodSampling#sample} -> {@link BaselineDeriver#derive} -> {@link CanopyStylizer#apply}
 * -> {@link PredictedTileComposer#compose} with the default palette and no corrections), then:
 *
 * <ul>
 *   <li>When {@code common/build/tile-cost/visual-baseline/} is absent, stores one binary
 *       baseline per combination (terrain heights, biome ids, surface flags, rendered ARGB via
 *       {@link DataOutputStream#writeInt}) and writes an all-clear
 *       {@code common/build/tile-cost/visual-diff.json} with {@code wroteBaseline:true}.
 *   <li>Otherwise it resamples, compares against the baseline, and writes the graded metrics to
 *       the same JSON: exact equality overall and for the 1.17.1 era alone, plus - taken over the
 *       1.21.4 combinations only - water/land flip rates judged on rendered colors, the share of
 *       visibly changed pixels (any channel moved by {@code >= 8/255}), the mean absolute color
 *       delta, and the terrain-height MAE (recorded, not gated). The test itself never fails on a
 *       measured difference - it is a pure measurement, and the consuming workflow gates on the
 *       JSON (hard red line on rendered ocean-to-land flips, human review of visible change).
 * </ul>
 *
 * Every run also draws the nine 1.21.4 combinations side by side ("baseline | current") into
 * {@code common/build/tile-cost/visual/contact-sheet.png} so a regression can be seen, not just
 * measured. Water-color classification is set-based rather than threshold-based: a pixel counts
 * as water-colored when its rendered ARGB equals the render of some {@link
 * BaselineGrid#SURFACE_FLUID} column on either side of the comparison, which keeps the metric a
 * property of the shipped renderer instead of a hand-tuned blue test.
 */
@Tag("benchmark")
class TileVisualSnapshotTest {
    private static final long SEED = 146008555L;
    private static final String[] VERSIONS = {"1.17.1", "1.21.4"};
    private static final int[] LODS = {0, 2, 4};
    /**
     * The 1.21.4 combinations are the "modern" series every graded metric in visual-diff.json is
     * maximized over.
     */
    private static final String MODERN_VERSION = "1.21.4";

    /**
     * Fixed coastal corpus on the SEED's Overworld, one region per sign quadrant. Origins are in
     * block coordinates; region names double as baseline filename parts and contact-sheet labels.
     * Each window was chosen (strided SURFACE_FLUID sweep of the quadrant, then a production
     * {@link LodSampling#sample} re-check) to hold a large ocean share plus a long water/land edge
     * on BOTH generator eras at every corpus LOD, so the flip and visible-diff metrics always have
     * water and coastline to bite on.
     */
    private static final int[] REGION_POS_POS = {6912, 3712};
    private static final int[] REGION_NEG_POS = {-3328, 7424};
    private static final int[] REGION_NEG_NEG = {-5632, -4864};
    private static final int[][] REGIONS = {
        REGION_POS_POS, REGION_NEG_POS, REGION_NEG_NEG,
    };
    private static final String[] REGION_NAMES = {
        "coast-pp", "coast-np", "coast-nn",
    };

    /** Rendered-channel movement a human can see on the map plane; 8/255 of full scale. */
    private static final int VISIBLE_CHANNEL_DELTA = 8;

    private static final int GRID_CELLS = BaselineGrid.SIZE * BaselineGrid.SIZE;
    private static final int RENDER_PIXELS = BaselineGrid.PIXELS * BaselineGrid.PIXELS;

    @BeforeEach
    void requireNative() {
        Assumptions.assumeTrue(NativeLib.initForTests(), "native prediction library unavailable on this platform");
    }

    @Test
    void gatePredictedTileVisualsAgainstBaseline() throws IOException {
        final Path tileCostDir = tileCostDir();
        final Path baselineDir = tileCostDir.resolve("visual-baseline");
        final boolean wroteBaseline = !Files.isDirectory(baselineDir);
        final Path diffJson = tileCostDir.resolve("visual-diff.json");

        boolean exactMatch = true;
        boolean legacyExact = true;
        double maxOceanToLandPct = 0;
        double maxLandToOceanPct = 0;
        double maxVisibleDiffPct = 0;
        double maxMeanColorDelta = 0;
        double maxHeightMae = 0;
        final int[][] sheetBaseline = new int[REGIONS.length * LODS.length][];
        final int[][] sheetCurrent = new int[REGIONS.length * LODS.length][];

        for (final String version : VERSIONS) {
            final OptionalInt mc = McVersions.toCubiomes(version);
            assertTrue(mc.isPresent(), "McVersions must know \"" + version + "\"");
            final NativeBaselineSampler sampler = new NativeBaselineSampler(
                mc.getAsInt(), SEED, PredictionDimensions.OVERWORLD, 0
            );
            for (int lodIdx = 0; lodIdx < LODS.length; lodIdx++) {
                final int lod = LODS[lodIdx];
                for (int regionIdx = 0; regionIdx < REGIONS.length; regionIdx++) {
                    final int[] origin = REGIONS[regionIdx];
                    final BaselineGrid grid = LodSampling.sample(
                        sampler, false, lod, origin[0], origin[1]
                    );
                    assertNotNull(grid, "sampling must succeed: " + comboId(version, lod, regionIdx));
                    final DerivedGrid derived = BaselineDeriver.derive(grid);
                    CanopyStylizer.apply(derived, grid, SEED, lod, origin[0], origin[1]);
                    final int[] render = PredictedTileComposer.compose(
                        derived, grid, PredictionPalette.defaults(), null,
                        PredictionViewMode.EVERYWHERE, lod
                    );

                    final Path baselineFile = baselineDir.resolve(
                        "visual-" + comboId(version, lod, regionIdx) + ".bin"
                    );
                    final boolean modern = version.equals(MODERN_VERSION);
                    final int sheetSlot = regionIdx * LODS.length + lodIdx;
                    if (wroteBaseline) {
                        writeBaseline(baselineDir, baselineFile, grid, render);
                        if (modern) {
                            sheetBaseline[sheetSlot] = render;
                            sheetCurrent[sheetSlot] = render;
                        }
                        continue;
                    }

                    assertTrue(Files.isRegularFile(baselineFile),
                        "baseline entry missing for " + comboId(version, lod, regionIdx)
                            + "; delete " + baselineDir + " to re-pin the whole gate");
                    final int[] baselineRender = new int[RENDER_PIXELS];
                    final LoadedBaseline baseline = readBaseline(baselineFile, baselineRender);
                    final ComboDiff diff = compareCombo(baseline, grid, render);

                    exactMatch &= diff.exact();
                    if (version.equals("1.17.1")) {
                        legacyExact &= diff.exact();
                    }
                    if (modern) {
                        maxOceanToLandPct = Math.max(maxOceanToLandPct, diff.oceanToLandPct);
                        maxLandToOceanPct = Math.max(maxLandToOceanPct, diff.landToOceanPct);
                        maxVisibleDiffPct = Math.max(maxVisibleDiffPct, diff.visibleDiffPct);
                        maxMeanColorDelta = Math.max(maxMeanColorDelta, diff.meanColorDelta);
                        maxHeightMae = Math.max(maxHeightMae, diff.heightMae);
                        sheetBaseline[sheetSlot] = baselineRender;
                        sheetCurrent[sheetSlot] = render;
                    }
                }
            }
        }

        Files.writeString(diffJson, wroteBaseline ? baselineWrittenJson() : comparedJson(
            exactMatch, legacyExact, maxOceanToLandPct, maxLandToOceanPct,
            maxVisibleDiffPct, maxMeanColorDelta, maxHeightMae
        ), StandardCharsets.UTF_8);
        writeContactSheet(
            tileCostDir.resolve("visual").resolve("contact-sheet.png"),
            sheetBaseline, sheetCurrent
        );
    }

    private static String comboId(final String version, final int lod, final int regionIdx) {
        return version + "-lod" + lod + "-" + REGION_NAMES[regionIdx];
    }

    /** Graded per-combination comparison of the resampled tile against its pinned baseline. */
    private static final class ComboDiff {
        boolean arraysExact;
        boolean renderExact;
        double heightMae;
        double oceanToLandPct;
        double landToOceanPct;
        double visibleDiffPct;
        double meanColorDelta;

        boolean exact() {
            return arraysExact && renderExact;
        }
    }

    private static ComboDiff compareCombo(
        final LoadedBaseline baseline,
        final BaselineGrid grid,
        final int[] render
    ) {
        final ComboDiff diff = new ComboDiff();
        diff.arraysExact = Arrays.equals(baseline.terrainY, grid.terrainY)
            && Arrays.equals(baseline.biomeId, grid.biomeId)
            && Arrays.equals(baseline.surfaceFlags, grid.surfaceFlags);
        diff.renderExact = Arrays.equals(baseline.render, render);

        // Height MAE over the shared margin-inclusive grid; sentinel-only columns (End void
        // between islands) exist for symmetry with other dimensions but never fire in Overworld.
        long heightSum = 0;
        long heightPairs = 0;
        for (int i = 0; i < GRID_CELLS; i++) {
            final int was = baseline.terrainY[i];
            final int now = grid.terrainY[i];
            if (was == BaselineGrid.NO_SURFACE || now == BaselineGrid.NO_SURFACE) {
                continue;
            }
            heightSum += Math.abs(now - was);
            heightPairs++;
        }
        diff.heightMae = heightPairs == 0 ? 0.0 : (double) heightSum / heightPairs;

        // Water-color reference: the rendered ARGB of every SURFACE_FLUID column on either side,
        // so a shade that only one side still produces still classifies its own pixels.
        final Set<Integer> waterColors = new HashSet<>();
        for (int z = 0; z < BaselineGrid.PIXELS; z++) {
            for (int x = 0; x < BaselineGrid.PIXELS; x++) {
                final int idx = BaselineGrid.index(x, z);
                final int p = z * BaselineGrid.PIXELS + x;
                if ((baseline.surfaceFlags[idx] & BaselineGrid.SURFACE_FLUID) != 0) {
                    waterColors.add(baseline.render[p]);
                }
                if ((grid.surfaceFlags[idx] & BaselineGrid.SURFACE_FLUID) != 0) {
                    waterColors.add(render[p]);
                }
            }
        }
        long baselineWaterPixels = 0;
        long oceanToLand = 0;
        long landToOcean = 0;
        long visiblePixels = 0;
        long colorSum = 0;
        for (int p = 0; p < RENDER_PIXELS; p++) {
            final int was = baseline.render[p];
            final int now = render[p];
            final boolean wasWater = waterColors.contains(was);
            final boolean nowWater = waterColors.contains(now);
            if (wasWater) {
                baselineWaterPixels++;
                if (!nowWater) {
                    oceanToLand++;
                }
            } else if (nowWater) {
                landToOcean++;
            }
            if (channelVisible(was, now)) {
                visiblePixels++;
            }
            colorSum += channelAbsDelta(was, now);
        }
        diff.oceanToLandPct = baselineWaterPixels == 0
            ? 0.0 : 100.0 * oceanToLand / baselineWaterPixels;
        diff.landToOceanPct = baselineWaterPixels == 0
            ? 0.0 : 100.0 * landToOcean / baselineWaterPixels;
        diff.visibleDiffPct = 100.0 * visiblePixels / RENDER_PIXELS;
        diff.meanColorDelta = (double) colorSum / RENDER_PIXELS;
        return diff;
    }

    /** Any A/R/G/B channel moved by at least the visible threshold counts as a visible pixel. */
    private static boolean channelVisible(final int was, final int now) {
        return Math.abs((was >>> 24) - (now >>> 24)) >= VISIBLE_CHANNEL_DELTA
            || Math.abs(((was >> 16) & 0xFF) - ((now >> 16) & 0xFF)) >= VISIBLE_CHANNEL_DELTA
            || Math.abs(((was >> 8) & 0xFF) - ((now >> 8) & 0xFF)) >= VISIBLE_CHANNEL_DELTA
            || Math.abs((was & 0xFF) - (now & 0xFF)) >= VISIBLE_CHANNEL_DELTA;
    }

    /** Mean absolute RGB delta in 0-255 units; alpha is constant on the composed plane. */
    private static int channelAbsDelta(final int was, final int now) {
        return (Math.abs(((was >> 16) & 0xFF) - ((now >> 16) & 0xFF))
            + Math.abs(((was >> 8) & 0xFF) - ((now >> 8) & 0xFF))
            + Math.abs((was & 0xFF) - (now & 0xFF))) / 3;
    }

    private static final class LoadedBaseline {
        final int[] terrainY = new int[GRID_CELLS];
        final int[] biomeId = new int[GRID_CELLS];
        final int[] surfaceFlags = new int[GRID_CELLS];
        final int[] render;
        LoadedBaseline(final int[] render) {
            this.render = render;
        }
    }

    private static void writeBaseline(
        final Path baselineDir, final Path file, final BaselineGrid grid, final int[] render
    ) throws IOException {
        Files.createDirectories(baselineDir);
        try (DataOutputStream output = new DataOutputStream(
            new BufferedOutputStream(Files.newOutputStream(file))
        )) {
            output.writeInt(GRID_CELLS);
            output.writeInt(RENDER_PIXELS);
            for (final int v : grid.terrainY) {
                output.writeInt(v);
            }
            for (final int v : grid.biomeId) {
                output.writeInt(v);
            }
            for (final int v : grid.surfaceFlags) {
                output.writeInt(v);
            }
            for (final int v : render) {
                output.writeInt(v);
            }
        }
    }

    private static LoadedBaseline readBaseline(final Path file, final int[] render) throws IOException {
        final LoadedBaseline baseline = new LoadedBaseline(render);
        try (DataInputStream input = new DataInputStream(
            new BufferedInputStream(Files.newInputStream(file))
        )) {
            assertTrue(input.readInt() == GRID_CELLS && input.readInt() == RENDER_PIXELS,
                "baseline header mismatch in " + file);
            for (int i = 0; i < GRID_CELLS; i++) {
                baseline.terrainY[i] = input.readInt();
            }
            for (int i = 0; i < GRID_CELLS; i++) {
                baseline.biomeId[i] = input.readInt();
            }
            for (int i = 0; i < GRID_CELLS; i++) {
                baseline.surfaceFlags[i] = input.readInt();
            }
            for (int i = 0; i < RENDER_PIXELS; i++) {
                render[i] = input.readInt();
            }
        }
        return baseline;
    }

    private static String baselineWrittenJson() {
        return "{\"exactMatch\":true,\"legacyExact\":true,\"modernMaxOceanToLandPct\":0,"
            + "\"modernMaxLandToOceanPct\":0,\"modernMaxVisibleDiffPct\":0,"
            + "\"modernMeanColorDelta\":0,\"modernMaxHeightMae\":0,\"wroteBaseline\":true}\n";
    }

    private static String comparedJson(
        final boolean exactMatch,
        final boolean legacyExact,
        final double maxOceanToLandPct,
        final double maxLandToOceanPct,
        final double maxVisibleDiffPct,
        final double maxMeanColorDelta,
        final double maxHeightMae
    ) {
        return "{\"exactMatch\":" + exactMatch
            + ",\"legacyExact\":" + legacyExact
            + ",\"modernMaxOceanToLandPct\":" + metric(maxOceanToLandPct)
            + ",\"modernMaxLandToOceanPct\":" + metric(maxLandToOceanPct)
            + ",\"modernMaxVisibleDiffPct\":" + metric(maxVisibleDiffPct)
            + ",\"modernMeanColorDelta\":" + metric(maxMeanColorDelta)
            + ",\"modernMaxHeightMae\":" + metric(maxHeightMae)
            + ",\"wroteBaseline\":false}\n";
    }

    private static String metric(final double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    /**
     * Draws the nine modern combinations as a regions-x-LODs grid; every cell shows the pinned
     * baseline beside the current render so a diff is visible at a glance.
     */
    private static void writeContactSheet(final Path file, final int[][] baseline, final int[][] current)
        throws IOException {
        final int cell = BaselineGrid.PIXELS;
        final int divider = 6;
        final int label = 18;
        final int gap = 10;
        final int margin = 10;
        final int width = margin * 2 + LODS.length * (cell * 2 + divider) + (LODS.length - 1) * gap;
        final int height = margin * 2 + REGIONS.length * (label + cell) + (REGIONS.length - 1) * gap;
        final BufferedImage sheet = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        final Graphics2D graphics = sheet.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setColor(Color.BLACK);
            for (int regionIdx = 0; regionIdx < REGIONS.length; regionIdx++) {
                for (int lodIdx = 0; lodIdx < LODS.length; lodIdx++) {
                    final int slot = regionIdx * LODS.length + lodIdx;
                    final int x = margin + lodIdx * (cell * 2 + divider + gap);
                    final int y = margin + regionIdx * (label + cell + gap);
                    final int[] origin = REGIONS[regionIdx];
                    graphics.drawString(String.format(Locale.ROOT,
                        "%s LOD%d %s (%d,%d) baseline|current",
                        MODERN_VERSION, LODS[lodIdx], REGION_NAMES[regionIdx],
                        origin[0], origin[1]), x, y + label - 5);
                    drawTile(sheet, baseline[slot], x, y + label);
                    drawTile(sheet, current[slot], x + cell + divider, y + label);
                }
            }
        } finally {
            graphics.dispose();
        }
        Files.createDirectories(file.getParent());
        ImageIO.write(sheet, "png", file.toFile());
    }

    private static void drawTile(
        final BufferedImage sheet, final int[] render, final int x, final int y
    ) {
        for (int z = 0; z < BaselineGrid.PIXELS; z++) {
            for (int px = 0; px < BaselineGrid.PIXELS; px++) {
                sheet.setRGB(x + px, y + z, render[z * BaselineGrid.PIXELS + px]);
            }
        }
    }

    /**
     * Gradle runs tests with the owning project directory as the working directory, so the
     * artifacts land in {@code common/build/tile-cost/}; IDE runs from the repository root
     * resolve the same directory instead of scattering a stray tree elsewhere.
     */
    private static Path tileCostDir() {
        final Path workingDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        if (workingDir.getFileName().toString().equals("common")
            && Files.isRegularFile(workingDir.resolve("build.gradle"))) {
            return workingDir.resolve("build").resolve("tile-cost");
        }
        final Path common = workingDir.resolve("common");
        assertTrue(Files.isRegularFile(common.resolve("build.gradle")),
            "cannot locate the common project directory from " + workingDir);
        return common.resolve("build").resolve("tile-cost");
    }
}
