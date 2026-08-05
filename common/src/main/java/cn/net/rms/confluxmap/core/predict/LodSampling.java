package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.model.DimensionId;

/**
 * Fills a {@link BaselineGrid} for one predicted tile. Every Overworld version follows the same
 * two-level layout: a cheap terrain overview at every output pixel, corrected by a globally
 * aligned sparse grid of exact cubiomes columns. The overview keeps LOD3-4 spatially detailed;
 * the exact residual grid removes broad height drift without making a fullscreen viewport pay for
 * a complete world-generation column at every texel. End heights retain their
 * dimension-specific interpolation and pooling path.
 * <ul>
 *   <li>LOD0-1: final biomes are sampled at block scale, one lookup per output pixel.
 *   <li>LOD2: final biomes are sampled at native scale 4, one lookup per output pixel.
 *   <li>LOD3-4: scale-4 biomes use a 2-cell or 4-cell stride so every output pixel still maps to
 *       its own world position instead of falling back to a coarser pre-zoom layer. Because a
 *       pixel there spans 8 or 16 blocks - wider than a river - biomes are additionally sampled
 *       2x2 per pixel and box-filtered by the composer, mirroring how the captured map averages
 *       finer pixels into a coarse tile. See {@link #BIOME_SUB_PER_AXIS}.
 *   <li>Overworld exact corrections use an 8-pixel stride at LOD0 and 16 at LOD1-4. Nearby
 *       views retain more terrain and shoreline precision while distant views do
 *       not pay for details smaller than an output pixel.
 *   <li>Overworld biomes reuse the corrected terrain height instead of generating terrain a
 *       second time solely to choose the surface biome.
 * </ul>
 *
 * <p>Every grid is sampled over the full margin range {@code
 * [-BaselineGrid.MARGIN, BaselineGrid.PIXELS-1+BaselineGrid.MARGIN]} on both axes so both slope
 * samples remain local to the tile.
 */
public final class LodSampling {
    private static final int PIXELS = BaselineGrid.PIXELS;
    private static final int P_MIN = -BaselineGrid.MARGIN;
    private static final int P_MAX = PIXELS - 1 + BaselineGrid.MARGIN;
    /** Screen-space exact correction stride per LOD; independent of the Minecraft version. */
    private static final int[] EXACT_CORRECTION_PIXEL_STRIDE = {8, 16, 16, 16, 16};
    private static final int FLUID_CONFIDENCE_MAX = 256;
    private static final int FLUID_CONFIDENCE_THRESHOLD = FLUID_CONFIDENCE_MAX / 2;

    /**
     * Highest LOD that still runs the sparse exact residual pass. At LOD0 its 8-block anchor
     * spacing carries most of the tile's relief (measured terrain gradient correlation 0.25 ->
     * 0.53) and at LOD1 it still helps a little. From LOD2 up the anchors are 64 blocks apart or
     * worse and contribute essentially nothing to height - measured correlation gain +0.001 at
     * LOD2 and +0.001 at LOD4, for ~330ms per tile - so they are simply not run there. The
     * overview terrain model absorbed that job when it started solving for the 3D noise term.
     */
    private static final int MAX_EXACT_RESIDUAL_LOD = 1;

    /**
     * Highest LOD that classifies fluid from the exact anchors' own aquifer answer. Above it the
     * anchor lattice is too sparse to place scattered inland water: the flag is binary, sampled
     * every 16 output pixels (64 blocks at LOD2, 256 at LOD4), bilinearly interpolated and then
     * thresholded, so a pond lands on the lattice rather than where it actually is. Measured
     * against real generation over swamp, that mask reached IoU 0.060 at LOD4 and 0.082 at LOD3.
     * Deciding per pixel from the terrain height instead reaches 0.56-0.88, beating even a
     * 2-pixel anchor lattice that costs 21x more.
     *
     * <p>LOD0 keeps the anchors: at 8-block spacing they are dense, and they answer with the
     * generator's real aquifer resolution rather than a height threshold.
     */
    private static final int MAX_ANCHOR_FLUID_LOD = 0;

    /**
     * A predicted column with solid terrain below this Y carries water. One block under
     * {@link BaselineDeriver#WATER_LEVEL}: sweeping the threshold against real generation put the
     * best intersection-over-union here (0.618 at LOD4 versus 0.556 at sea level itself), because
     * the overview height is an estimate and sea level is the point where over-detection starts
     * flooding dry ground.
     */
    private static final int PREDICTED_FLUID_CEILING = BaselineDeriver.WATER_LEVEL - 1;

    /** Final biome layer used per LOD (cubiomes {@code Range} scale: 1 or 4). */
    private static final int[] BIOME_SCALE = {1, 1, 4, 4, 4};
    /** Native coordinates advanced per output pixel at each LOD. */
    private static final int[] BIOME_STRIDE = {1, 2, 1, 2, 4};
    /**
     * Biome sub-samples per axis per output pixel. LOD0-2 already sample at or below the native
     * 4-block biome grid, so one lookup per pixel loses nothing. LOD3-4 span 8 and 16 blocks, where
     * a single lookup misses most of a river or shoreline: measured against full 1-block
     * generation, one sample per pixel retains 69% of thin-feature pixels at LOD3 and 58% at LOD4,
     * which is what turns a meandering river into a broken, straightened line. 2x2 lifts those to
     * 88% and 80% for 4x the biome sampling cost; 4x4 would reach 96%/92% at 16x the cost.
     */
    private static final int[] BIOME_SUB_PER_AXIS = {1, 1, 1, 2, 2};

    private LodSampling() {
    }

    /**
     * Samples one tile's biome ids and terrain heights, or returns null if any underlying native
     * query failed (caller should treat this as a transient no-op - see {@code
     * PredictionTileService}'s gating).
     */
    public static BaselineGrid sample(
        final BaselineSampler sampler,
        final boolean end,
        final int lod,
        final int tileOriginX,
        final int tileOriginZ
    ) {
        final BaselineGrid grid = new BaselineGrid(
            lod, tileOriginX, tileOriginZ, end ? BaselineGrid.NO_SUPERSAMPLING : BIOME_SUB_PER_AXIS[lod]
        );
        if (!end) {
            return sampleOverworld(sampler, lod, tileOriginX, tileOriginZ, grid)
                ? grid
                : null;
        }

        if (!sampleEndHeights(sampler, lod, tileOriginX, tileOriginZ, grid)
            || !sampleBiomes(sampler, lod, tileOriginX, tileOriginZ, grid)) {
            return null;
        }
        if (lod == 4) {
            refineEndLod4Coverage(grid);
        }
        for (int i = 0; i < grid.terrainY.length; i++) {
            grid.baseSurfaceY[i] = grid.terrainY[i];
        }
        return grid;
    }

    /** Samples the baseline owned by one supported vanilla dimension. */
    public static BaselineGrid sample(
        final BaselineSampler sampler,
        final DimensionId dimension,
        final int lod,
        final int tileOriginX,
        final int tileOriginZ
    ) {
        if (dimension.equals(DimensionId.NETHER)) {
            return sampleNetherRoof(sampler, lod, tileOriginX, tileOriginZ);
        }
        if (dimension.equals(DimensionId.OVERWORLD) || dimension.equals(DimensionId.END)) {
            return sample(
                sampler, dimension.equals(DimensionId.END), lod, tileOriginX, tileOriginZ
            );
        }
        return null;
    }

    /**
     * Samples the vanilla Nether's bedrock-roof plane. Cubiomes owns the 3D biome lookup at roof
     * height, while the terrain plane is fixed because cubiomes does not model Nether density
     * columns. Server corrections and captured chunks replace this approximation wherever real
     * roof blocks are known.
     */
    public static BaselineGrid sampleNetherRoof(
        final BaselineSampler sampler,
        final int lod,
        final int tileOriginX,
        final int tileOriginZ
    ) {
        final BaselineGrid grid = new BaselineGrid(
            lod, tileOriginX, tileOriginZ, BIOME_SUB_PER_AXIS[lod]
        );
        if (!sampleBiomes(sampler, lod, tileOriginX, tileOriginZ, grid)) {
            return null;
        }
        java.util.Arrays.fill(grid.terrainY, PredictionDimensions.NETHER_ROOF_Y);
        java.util.Arrays.fill(grid.baseSurfaceY, PredictionDimensions.NETHER_ROOF_Y);
        if (grid.supersampled()) {
            sampleRawSubBiomes(sampler, lod, tileOriginX, tileOriginZ, grid);
            java.util.Arrays.fill(grid.subBaseSurfaceY, PredictionDimensions.NETHER_ROOF_Y);
        }
        return grid;
    }

    /** Samples only one output-pixel window of the fixed Nether roof baseline. */
    public static BaselineGrid sampleNetherRoofWindow(
        final BaselineSampler sampler,
        final int lod,
        final int tileOriginX,
        final int tileOriginZ,
        final int minPixelX,
        final int minPixelZ,
        final int maxPixelX,
        final int maxPixelZ
    ) {
        if (sampler == null || lod < 0 || lod > 4
            || minPixelX < 0 || minPixelZ < 0
            || maxPixelX >= PIXELS || maxPixelZ >= PIXELS
            || minPixelX > maxPixelX || minPixelZ > maxPixelZ) {
            throw new IllegalArgumentException("invalid Nether roof baseline window");
        }
        final BaselineGrid grid = new BaselineGrid(
            lod, tileOriginX, tileOriginZ, BIOME_SUB_PER_AXIS[lod]
        );
        final int scale = BIOME_SCALE[lod];
        final int stride = BIOME_STRIDE[lod];
        final int width = maxPixelX - minPixelX + 1;
        final int height = maxPixelZ - minPixelZ + 1;
        final int nativeX = Math.floorDiv(tileOriginX, scale) + minPixelX * stride;
        final int nativeZ = Math.floorDiv(tileOriginZ, scale) + minPixelZ * stride;
        final int[] biomes = new int[width * height];
        if (!sampler.biomesStrided(
            scale, nativeX, nativeZ, width, height, stride, biomes
        )) {
            return null;
        }
        for (int z = minPixelZ; z <= maxPixelZ; z++) {
            for (int x = minPixelX; x <= maxPixelX; x++) {
                final int source = (z - minPixelZ) * width + x - minPixelX;
                final int target = BaselineGrid.index(x, z);
                grid.biomeId[target] = biomes[source];
                grid.terrainY[target] = PredictionDimensions.NETHER_ROOF_Y;
                grid.baseSurfaceY[target] = PredictionDimensions.NETHER_ROOF_Y;
            }
        }
        return grid;
    }

    /**
     * Samples only an inclusive output-pixel window of an Overworld tile at LOD2-4. These LODs
     * use the per-pixel overview directly, so their target pixels have no dependency on a
     * tile-wide exact-residual lattice. Unfilled cells remain private implementation detail and
     * must not be read by the caller.
     */
    public static BaselineGrid sampleOverworldWindow(
        final BaselineSampler sampler,
        final int lod,
        final int tileOriginX,
        final int tileOriginZ,
        final int minPixelX,
        final int minPixelZ,
        final int maxPixelX,
        final int maxPixelZ
    ) {
        if (sampler == null || lod < 2 || lod > 4
            || minPixelX < 0 || minPixelZ < 0
            || maxPixelX >= PIXELS || maxPixelZ >= PIXELS
            || minPixelX > maxPixelX || minPixelZ > maxPixelZ) {
            throw new IllegalArgumentException("invalid coarse baseline window");
        }
        final BaselineGrid grid = new BaselineGrid(
            lod, tileOriginX, tileOriginZ, BIOME_SUB_PER_AXIS[lod]
        );
        final int width = maxPixelX - minPixelX + 1;
        final int height = maxPixelZ - minPixelZ + 1;
        final int blocksPerPixel = 1 << lod;
        final int blockX = tileOriginX + minPixelX * blocksPerPixel;
        final int blockZ = tileOriginZ + minPixelZ * blocksPerPixel;
        final int[] terrain = new int[width * height];
        if (!sampler.overviewHeights(
            blockX, blockZ, width, height, blocksPerPixel, terrain
        )) {
            return null;
        }
        final int[] biomes = new int[width * height];
        if (!sampler.surfaceBiomes(
            blockX, blockZ, width, height, blocksPerPixel, terrain, biomes
        )) {
            final int scale = BIOME_SCALE[lod];
            final int stride = BIOME_STRIDE[lod];
            final int nativeX = Math.floorDiv(tileOriginX, scale) + minPixelX * stride;
            final int nativeZ = Math.floorDiv(tileOriginZ, scale) + minPixelZ * stride;
            if (!sampler.biomesStrided(
                scale, nativeX, nativeZ, width, height, stride, biomes
            )) {
                return null;
            }
        }
        copyWindow(grid.terrainY, terrain, minPixelX, minPixelZ, width, height);
        copyWindow(grid.biomeId, biomes, minPixelX, minPixelZ, width, height);
        sampleSubBiomeWindow(
            sampler, grid, blockX, blockZ,
            minPixelX, minPixelZ, width, height, blocksPerPixel, terrain
        );
        resolveOverviewFluidWindow(grid, minPixelX, minPixelZ, maxPixelX, maxPixelZ);
        return grid;
    }

    private static void sampleSubBiomeWindow(
        final BaselineSampler sampler,
        final BaselineGrid grid,
        final int blockX,
        final int blockZ,
        final int minPixelX,
        final int minPixelZ,
        final int width,
        final int height,
        final int blocksPerPixel,
        final int[] terrain
    ) {
        if (!grid.supersampled()) {
            return;
        }
        final int sub = grid.subPerAxis;
        final int subWidth = width * sub;
        final int subHeight = height * sub;
        final int[] heights = new int[subWidth * subHeight];
        for (int z = 0; z < subHeight; z++) {
            final int sourceRow = (z / sub) * width;
            for (int x = 0; x < subWidth; x++) {
                heights[z * subWidth + x] = terrain[sourceRow + x / sub];
            }
        }
        final int[] biomes = new int[subWidth * subHeight];
        final boolean sampled = sampler.surfaceBiomes(
            blockX, blockZ, subWidth, subHeight, blocksPerPixel / sub, heights, biomes
        );
        for (int z = 0; z < subHeight; z++) {
            for (int x = 0; x < subWidth; x++) {
                final int pixelX = minPixelX + x / sub;
                final int pixelZ = minPixelZ + z / sub;
                final int pixel = BaselineGrid.index(pixelX, pixelZ);
                grid.subBiomeId[grid.subIndex(pixel, x % sub, z % sub)] = sampled
                    ? biomes[z * subWidth + x] : grid.biomeId[pixel];
            }
        }
    }

    private static void resolveOverviewFluidWindow(
        final BaselineGrid grid,
        final int minPixelX,
        final int minPixelZ,
        final int maxPixelX,
        final int maxPixelZ
    ) {
        for (int pixelZ = minPixelZ; pixelZ <= maxPixelZ; pixelZ++) {
            for (int pixelX = minPixelX; pixelX <= maxPixelX; pixelX++) {
                final int index = BaselineGrid.index(pixelX, pixelZ);
                final int solidY = grid.terrainY[index];
                final boolean belowSeaLevel = solidY < BaselineDeriver.WATER_LEVEL;
                final boolean fluid = solidY < PREDICTED_FLUID_CEILING
                    || BiomeTable.get(grid.biomeId[index]).waterBiome();
                if (belowSeaLevel && fluid) {
                    grid.fluidY[index] = BaselineDeriver.WATER_LEVEL;
                    grid.baseSurfaceY[index] = BaselineDeriver.WATER_LEVEL;
                    grid.surfaceFlags[index] = BaselineGrid.SURFACE_FLUID;
                } else {
                    grid.fluidY[index] = BaselineGrid.NO_FLUID;
                    grid.baseSurfaceY[index] = solidY;
                    grid.surfaceFlags[index] = 0;
                }
                if (!grid.supersampled()) {
                    continue;
                }
                for (int subZ = 0; subZ < grid.subPerAxis; subZ++) {
                    for (int subX = 0; subX < grid.subPerAxis; subX++) {
                        final int sub = grid.subIndex(index, subX, subZ);
                        final boolean subFluid = solidY < PREDICTED_FLUID_CEILING
                            || BiomeTable.get(grid.subBiomeId[sub]).waterBiome();
                        if (belowSeaLevel && subFluid) {
                            grid.subBaseSurfaceY[sub] = BaselineDeriver.WATER_LEVEL;
                            grid.subSurfaceFlags[sub] = BaselineGrid.SURFACE_FLUID;
                        } else {
                            grid.subBaseSurfaceY[sub] = solidY;
                            grid.subSurfaceFlags[sub] = 0;
                        }
                    }
                }
            }
        }
    }

    private static void copyWindow(
        final int[] target,
        final int[] source,
        final int minPixelX,
        final int minPixelZ,
        final int width,
        final int height
    ) {
        for (int z = 0; z < height; z++) {
            System.arraycopy(
                source, z * width,
                target, BaselineGrid.index(minPixelX, minPixelZ + z),
                width
            );
        }
    }

    private static boolean sampleOverworld(
        final BaselineSampler sampler,
        final int lod,
        final int tileOriginX,
        final int tileOriginZ,
        final BaselineGrid grid
    ) {
        final int blocksPerPixel = 1 << lod;
        final int blockX = tileOriginX + P_MIN * blocksPerPixel;
        final int blockZ = tileOriginZ + P_MIN * blocksPerPixel;
        final int[] fluidConfidence = new int[grid.terrainY.length];
        if (!sampler.overviewHeights(
            blockX,
            blockZ,
            BaselineGrid.SIZE,
            BaselineGrid.SIZE,
            blocksPerPixel,
            grid.terrainY
        )) {
            return false;
        }
        if (lod <= MAX_EXACT_RESIDUAL_LOD && !applyExactResiduals(
            sampler, lod, tileOriginX, tileOriginZ, blocksPerPixel, grid,
            fluidConfidence
        )) {
            return false;
        }
        if (!sampleSurfaceBiomes(sampler, lod, tileOriginX, tileOriginZ, grid)
            && !sampleBiomes(sampler, lod, tileOriginX, tileOriginZ, grid)) {
            return false;
        }
        sampleSubBiomes(sampler, lod, tileOriginX, tileOriginZ, grid);
        resolveOverviewFluids(grid, lod, fluidConfidence);
        return true;
    }

    /**
     * Fills the grid's biome sub-samples. Reuses the same height-aware {@code surfaceBiomes} entry
     * point as the per-pixel pass, with each sub-sample handed its pixel's centre height, so the
     * two agree on how a column's biome is chosen and only the sampled position differs.
     *
     * <p>Best-effort: a sampler that cannot resolve surface biomes (the interface's default, used
     * by tests and by non-native samplers) leaves every sub-sample equal to its pixel, which is
     * exactly the pre-supersampling behaviour.
     */
    private static void sampleSubBiomes(
        final BaselineSampler sampler,
        final int lod,
        final int tileOriginX,
        final int tileOriginZ,
        final BaselineGrid grid
    ) {
        if (!grid.supersampled()) {
            return;
        }
        final int sub = grid.subPerAxis;
        final int blocksPerPixel = 1 << lod;
        final int width = BaselineGrid.SIZE * sub;
        final int cells = width * width;
        final int[] heights = new int[cells];
        for (int j = 0; j < width; j++) {
            final int pixelRow = (j / sub) * BaselineGrid.SIZE;
            for (int i = 0; i < width; i++) {
                heights[j * width + i] = grid.terrainY[pixelRow + i / sub];
            }
        }
        final int[] raw = new int[cells];
        final boolean sampled = sampler.surfaceBiomes(
            tileOriginX + P_MIN * blocksPerPixel,
            tileOriginZ + P_MIN * blocksPerPixel,
            width,
            width,
            blocksPerPixel / sub,
            heights,
            raw
        );
        for (int j = 0; j < width; j++) {
            for (int i = 0; i < width; i++) {
                final int pixelIndex = (j / sub) * BaselineGrid.SIZE + i / sub;
                grid.subBiomeId[grid.subIndex(pixelIndex, i % sub, j % sub)] = sampled
                    ? raw[j * width + i]
                    : grid.biomeId[pixelIndex];
            }
        }
    }

    private static boolean applyExactResiduals(
        final BaselineSampler sampler,
        final int lod,
        final int tileOriginX,
        final int tileOriginZ,
        final int blocksPerPixel,
        final BaselineGrid grid,
        final int[] fluidConfidence
    ) {
        final int correctionStride = EXACT_CORRECTION_PIXEL_STRIDE[lod];
        final int pixelSpan = P_MAX - P_MIN;
        final int intervals = Math.floorDiv(
            pixelSpan + correctionStride - 1,
            correctionStride
        );
        final int anchorSize = intervals + 1;
        final int cells = anchorSize * anchorSize;
        final int anchorBlockX = tileOriginX + P_MIN * blocksPerPixel;
        final int anchorBlockZ = tileOriginZ + P_MIN * blocksPerPixel;
        final int anchorBlockStride = correctionStride * blocksPerPixel;
        final int[] overview = new int[cells];
        final int[] exact = new int[cells];
        final int[] fluid = new int[cells];
        final int[] surface = new int[cells];
        final int[] flags = new int[cells];
        if (!sampler.overviewHeights(
            anchorBlockX,
            anchorBlockZ,
            anchorSize,
            anchorSize,
            anchorBlockStride,
            overview
        ) || !sampler.surfaceColumns(
            anchorBlockX,
            anchorBlockZ,
            anchorSize,
            anchorSize,
            anchorBlockStride,
            exact,
            fluid,
            surface,
            flags
        )) {
            return false;
        }

        final int[] residuals = new int[cells];
        for (int i = 0; i < cells; i++) {
            residuals[i] = exact[i] - overview[i];
        }
        for (int pz = P_MIN; pz <= P_MAX; pz++) {
            final int offsetZ = pz - P_MIN;
            final int az0 = offsetZ / correctionStride;
            final int az1 = az0 + 1;
            final int fz = offsetZ % correctionStride;
            for (int px = P_MIN; px <= P_MAX; px++) {
                final int offsetX = px - P_MIN;
                final int ax0 = offsetX / correctionStride;
                final int ax1 = ax0 + 1;
                final int fx = offsetX % correctionStride;
                final int top = lerp(
                    residuals[az0 * anchorSize + ax0],
                    residuals[az0 * anchorSize + ax1],
                    fx,
                    correctionStride
                );
                final int bottom = lerp(
                    residuals[az1 * anchorSize + ax0],
                    residuals[az1 * anchorSize + ax1],
                    fx,
                    correctionStride
                );
                final int residualHeight = grid.terrainY[BaselineGrid.index(px, pz)] + lerp(
                    top, bottom, fz, correctionStride
                );
                final int fluidTop = lerp(
                    anchorFluidConfidence(flags[az0 * anchorSize + ax0]),
                    anchorFluidConfidence(flags[az0 * anchorSize + ax1]),
                    fx,
                    correctionStride
                );
                final int fluidBottom = lerp(
                    anchorFluidConfidence(flags[az1 * anchorSize + ax0]),
                    anchorFluidConfidence(flags[az1 * anchorSize + ax1]),
                    fx,
                    correctionStride
                );
                final int interpolatedFluidConfidence = lerp(
                    fluidTop, fluidBottom, fz, correctionStride
                );
                final int outputIndex = BaselineGrid.index(px, pz);
                fluidConfidence[outputIndex] = interpolatedFluidConfidence;
                if (lod == 0 && interpolatedFluidConfidence > FLUID_CONFIDENCE_THRESHOLD) {
                    final int exactTop = lerp(
                        exact[az0 * anchorSize + ax0],
                        exact[az0 * anchorSize + ax1],
                        fx,
                        correctionStride
                    );
                    final int exactBottom = lerp(
                        exact[az1 * anchorSize + ax0],
                        exact[az1 * anchorSize + ax1],
                        fx,
                        correctionStride
                    );
                    grid.terrainY[outputIndex] = lerp(
                        exactTop, exactBottom, fz, correctionStride
                    );
                } else {
                    grid.terrainY[outputIndex] = residualHeight;
                }
            }
        }
        return true;
    }

    private static int anchorFluidConfidence(final int surfaceFlags) {
        return (surfaceFlags & BaselineGrid.SURFACE_FLUID) != 0 ? FLUID_CONFIDENCE_MAX : 0;
    }

    /**
     * Classifies each column as water or land. Below {@link #MAX_ANCHOR_FLUID_LOD} that means the
     * exact anchors' aquifer answer; above it, the column's own terrain height, which is what puts
     * scattered inland water (swamp ponds most visibly) where it actually is instead of on the
     * anchor lattice. Either way a water biome below sea level still counts, so rivers narrower
     * than the terrain estimate can resolve stay visible.
     */
    private static void resolveOverviewFluids(
        final BaselineGrid grid, final int lod, final int[] fluidConfidence
    ) {
        final boolean anchorFluid = lod <= MAX_ANCHOR_FLUID_LOD;
        for (int i = 0; i < grid.terrainY.length; i++) {
            final int solidY = grid.terrainY[i];
            final boolean belowSeaLevel = solidY < BaselineDeriver.WATER_LEVEL;
            final boolean confidentFluid = anchorFluid
                ? fluidConfidence[i] >= FLUID_CONFIDENCE_THRESHOLD
                : solidY < PREDICTED_FLUID_CEILING;
            if (belowSeaLevel
                && (confidentFluid || BiomeTable.get(grid.biomeId[i]).waterBiome())) {
                grid.fluidY[i] = BaselineDeriver.WATER_LEVEL;
                grid.baseSurfaceY[i] = BaselineDeriver.WATER_LEVEL;
                grid.surfaceFlags[i] = BaselineGrid.SURFACE_FLUID;
            } else {
                grid.fluidY[i] = BaselineGrid.NO_FLUID;
                grid.baseSurfaceY[i] = solidY;
                grid.surfaceFlags[i] = 0;
            }
            if (!grid.supersampled()) {
                continue;
            }
            // The height terms are shared with the pixel, so a sub-sample only changes the
            // outcome through its own biome - which is precisely how a river narrower than the
            // pixel makes itself visible again.
            for (int sz = 0; sz < grid.subPerAxis; sz++) {
                for (int sx = 0; sx < grid.subPerAxis; sx++) {
                    final int s = grid.subIndex(i, sx, sz);
                    if (belowSeaLevel
                        && (confidentFluid || BiomeTable.get(grid.subBiomeId[s]).waterBiome())) {
                        grid.subBaseSurfaceY[s] = BaselineDeriver.WATER_LEVEL;
                        grid.subSurfaceFlags[s] = BaselineGrid.SURFACE_FLUID;
                    } else {
                        grid.subBaseSurfaceY[s] = solidY;
                        grid.subSurfaceFlags[s] = 0;
                    }
                }
            }
        }
    }

    private static boolean sampleSurfaceBiomes(
        final BaselineSampler sampler,
        final int lod,
        final int tileOriginX,
        final int tileOriginZ,
        final BaselineGrid grid
    ) {
        final int blocksPerPixel = 1 << lod;
        return sampler.surfaceBiomes(
            tileOriginX + P_MIN * blocksPerPixel,
            tileOriginZ + P_MIN * blocksPerPixel,
            BaselineGrid.SIZE,
            BaselineGrid.SIZE,
            blocksPerPixel,
            grid.terrainY,
            grid.biomeId
        );
    }

    private static boolean sampleBiomes(
        final BaselineSampler sampler, final int lod, final int tileOriginX, final int tileOriginZ, final BaselineGrid grid
    ) {
        final int scale = BIOME_SCALE[lod];
        final int stride = BIOME_STRIDE[lod];
        final int nativeX0 = Math.floorDiv(tileOriginX, scale) + P_MIN * stride;
        final int nativeZ0 = Math.floorDiv(tileOriginZ, scale) + P_MIN * stride;
        final int[] raw = new int[BaselineGrid.SIZE * BaselineGrid.SIZE];
        if (!sampler.biomesStrided(scale, nativeX0, nativeZ0, BaselineGrid.SIZE, BaselineGrid.SIZE, stride, raw)) {
            return false;
        }
        System.arraycopy(raw, 0, grid.biomeId, 0, raw.length);
        return true;
    }

    /** Coarse Nether pixels use raw 3D-biome samples rather than Overworld surface-biome queries. */
    private static void sampleRawSubBiomes(
        final BaselineSampler sampler,
        final int lod,
        final int tileOriginX,
        final int tileOriginZ,
        final BaselineGrid grid
    ) {
        final int sub = grid.subPerAxis;
        final int blocksPerPixel = 1 << lod;
        final int blockStride = blocksPerPixel / sub;
        final int scale = blockStride < 4 ? 1 : 4;
        final int stride = blockStride / scale;
        final int width = BaselineGrid.SIZE * sub;
        final int nativeX = Math.floorDiv(tileOriginX + P_MIN * blocksPerPixel, scale);
        final int nativeZ = Math.floorDiv(tileOriginZ + P_MIN * blocksPerPixel, scale);
        final int[] raw = new int[width * width];
        final boolean sampled = sampler.biomesStrided(
            scale, nativeX, nativeZ, width, width, stride, raw
        );
        for (int z = 0; z < width; z++) {
            for (int x = 0; x < width; x++) {
                final int pixel = (z / sub) * BaselineGrid.SIZE + x / sub;
                grid.subBiomeId[grid.subIndex(pixel, x % sub, z % sub)] = sampled
                    ? raw[z * width + x]
                    : grid.biomeId[pixel];
            }
        }
    }

    private static boolean sampleEndHeights(
        final BaselineSampler sampler, final int lod, final int tileOriginX, final int tileOriginZ,
        final BaselineGrid grid
    ) {
        switch (lod) {
            case 0:
                return sampleEndHeightsBilinear(sampler, tileOriginX, tileOriginZ, grid, 1);
            case 1:
                // End heights are sampled at the native 1:4 grid and interpolated to this LOD's
                // 2-block pixels.
                return sampleEndHeightsBilinear(sampler, tileOriginX, tileOriginZ, grid, 2);
            case 2:
                return sampleEndHeightsNearest(sampler, tileOriginX, tileOriginZ, grid, 1);
            case 3:
                return sampleEndHeightsMeanPool(sampler, tileOriginX, tileOriginZ, grid);
            case 4:
                // Double the old 64-block grid's outline resolution without paying the cost of a
                // full 16-block surface-noise map. Nearest expansion keeps void categorical: one
                // land anchor owns one 32-block cell and cannot bleed into adjacent cells.
                return sampleEndHeightsNearestCoarse(
                    sampler, tileOriginX, tileOriginZ, grid, 2
                );
            default:
                return false;
        }
    }

    private static int lerp(final int from, final int to, final int offset, final int extent) {
        return from + Math.floorDiv((to - from) * offset, extent);
    }

    /**
     * 4-block native samples, bilinearly interpolated to per-pixel resolution. {@code blocksPerPixel}
     * is this LOD's pixel stride (1 at LOD0, 2 at LOD1): the bilinear fraction comes from where each
     * pixel's block offset lands inside its 4-block native cell, so LOD1 stays as sharp as the 1:4
     * native data allows instead of two pixels snapping to one nearest sample.
     */
    private static boolean sampleEndHeightsBilinear(
        final BaselineSampler sampler, final int tileOriginX, final int tileOriginZ,
        final BaselineGrid grid, final int blocksPerPixel
    ) {
        final int sMin = Math.floorDiv(P_MIN * blocksPerPixel, 4);
        final int sMax = Math.floorDiv(P_MAX * blocksPerPixel, 4) + 1; // +1: bilinear's upper corner sample
        final int sw = sMax - sMin + 1;
        final int x4Origin = Math.floorDiv(tileOriginX, 4) + sMin;
        final int z4Origin = Math.floorDiv(tileOriginZ, 4) + sMin;

        final int[] raw = new int[sw * sw];
        if (!sampler.endHeights(x4Origin, z4Origin, sw, sw, raw)) {
            return false;
        }
        for (int pz = P_MIN; pz <= P_MAX; pz++) {
            final int blockZ = pz * blocksPerPixel;
            final int baseZ = Math.floorDiv(blockZ, 4);
            final int fz = blockZ - baseZ * 4;
            final int sz0 = baseZ - sMin;
            final int sz1 = sz0 + 1;
            for (int px = P_MIN; px <= P_MAX; px++) {
                final int blockX = px * blocksPerPixel;
                final int baseX = Math.floorDiv(blockX, 4);
                final int fx = blockX - baseX * 4;
                final int sx0 = baseX - sMin;
                final int sx1 = sx0 + 1;
                final int h00 = raw[sz0 * sw + sx0];
                final int h10 = raw[sz0 * sw + sx1];
                final int h01 = raw[sz1 * sw + sx0];
                final int h11 = raw[sz1 * sw + sx1];
                final int value;
                if (h00 == 0 && h10 == 0 && h01 == 0 && h11 == 0) {
                    value = BaselineGrid.NO_SURFACE;
                } else {
                    final int top = h00 + Math.floorDiv((h10 - h00) * fx, 4);
                    final int bottom = h01 + Math.floorDiv((h11 - h01) * fx, 4);
                    final int interpolated = top + Math.floorDiv((bottom - top) * fz, 4);
                    value = interpolated;
                }
                grid.terrainY[BaselineGrid.index(px, pz)] = value;
            }
        }
        return true;
    }

    /** 4-block native samples selected at {@code nativeStride} cells per output pixel. */
    private static boolean sampleEndHeightsNearest(
        final BaselineSampler sampler, final int tileOriginX, final int tileOriginZ,
        final BaselineGrid grid, final int nativeStride
    ) {
        final int[] raw = new int[BaselineGrid.SIZE * BaselineGrid.SIZE];
        final int x4 = Math.floorDiv(tileOriginX, 4) + P_MIN * nativeStride;
        final int z4 = Math.floorDiv(tileOriginZ, 4) + P_MIN * nativeStride;
        final boolean sampled = sampler.endHeightsStrided(
            x4, z4, BaselineGrid.SIZE, BaselineGrid.SIZE, nativeStride, raw
        );
        if (!sampled) {
            return false;
        }
        for (int pz = P_MIN; pz <= P_MAX; pz++) {
            for (int px = P_MIN; px <= P_MAX; px++) {
                final int index = BaselineGrid.index(px, pz);
                final int raw0 = raw[index];
                grid.terrainY[index] = raw0 == 0 ? BaselineGrid.NO_SURFACE : raw0;
            }
        }
        return true;
    }

    /**
     * Samples one anchor for each square of {@code pixelsPerSample} output pixels and expands it
     * without interpolation. This is the bounded high-LOD path: terrain edges may step at the
     * sampling-cell boundary, but an End void value can never turn into synthetic land.
     */
    private static boolean sampleEndHeightsNearestCoarse(
        final BaselineSampler sampler,
        final int tileOriginX,
        final int tileOriginZ,
        final BaselineGrid grid,
        final int pixelsPerSample
    ) {
        final int sMin = Math.floorDiv(P_MIN, pixelsPerSample);
        final int sMax = Math.floorDiv(P_MAX, pixelsPerSample);
        final int sw = sMax - sMin + 1;
        final int nativeStride = pixelsPerSample * 4;
        final int x4Origin = Math.floorDiv(tileOriginX, 4) + sMin * nativeStride;
        final int z4Origin = Math.floorDiv(tileOriginZ, 4) + sMin * nativeStride;
        final int[] raw = new int[sw * sw];
        if (!sampler.endHeightsStrided(
            x4Origin, z4Origin, sw, sw, nativeStride, raw
        )) {
            return false;
        }
        for (int pz = P_MIN; pz <= P_MAX; pz++) {
            final int sourceZ = Math.floorDiv(pz, pixelsPerSample) - sMin;
            for (int px = P_MIN; px <= P_MAX; px++) {
                final int sourceX = Math.floorDiv(px, pixelsPerSample) - sMin;
                final int rawY = raw[sourceZ * sw + sourceX];
                grid.terrainY[BaselineGrid.index(px, pz)] = rawY == 0
                    ? BaselineGrid.NO_SURFACE
                    : rawY;
            }
        }
        return true;
    }

    /**
     * Uses the per-output-pixel End biome layer to sharpen the 32-block height cells. A non-small-
     * islands biome may extend land by one pixel only when an exact coarse cell already proves
     * terrain immediately beside it; the copied source plane prevents the refinement itself from
     * growing repeatedly through a large biome region.
     */
    private static void refineEndLod4Coverage(final BaselineGrid grid) {
        final int[] coarse = grid.terrainY.clone();
        for (int pz = P_MIN; pz <= P_MAX; pz++) {
            for (int px = P_MIN; px <= P_MAX; px++) {
                final int index = BaselineGrid.index(px, pz);
                if (coarse[index] != BaselineGrid.NO_SURFACE
                    || grid.biomeId[index] == CubiomesBiomeIds.SMALL_END_ISLANDS) {
                    continue;
                }
                int nearestHeight = BaselineGrid.NO_SURFACE;
                int nearestDistance = Integer.MAX_VALUE;
                for (int dz = -1; dz <= 1; dz++) {
                    final int nearbyZ = pz + dz;
                    if (nearbyZ < P_MIN || nearbyZ > P_MAX) {
                        continue;
                    }
                    for (int dx = -1; dx <= 1; dx++) {
                        final int nearbyX = px + dx;
                        if (nearbyX < P_MIN || nearbyX > P_MAX) {
                            continue;
                        }
                        final int height = coarse[BaselineGrid.index(nearbyX, nearbyZ)];
                        final int distance = dx * dx + dz * dz;
                        if (height != BaselineGrid.NO_SURFACE && distance < nearestDistance) {
                            nearestHeight = height;
                            nearestDistance = distance;
                        }
                    }
                }
                if (nearestHeight != BaselineGrid.NO_SURFACE) {
                    grid.terrainY[index] = nearestHeight;
                }
            }
        }
    }

    /** Two native 4-block samples per LOD3 output-pixel axis, integer-mean pooled down. */
    private static boolean sampleEndHeightsMeanPool(
        final BaselineSampler sampler,
        final int tileOriginX,
        final int tileOriginZ,
        final BaselineGrid grid
    ) {
        final int subMin = 2 * P_MIN;
        final int subMax = 2 * P_MAX + 1;
        final int sw = subMax - subMin + 1;
        final int x4Origin = Math.floorDiv(tileOriginX, 4) + subMin;
        final int z4Origin = Math.floorDiv(tileOriginZ, 4) + subMin;

        final int[] raw = new int[sw * sw];
        if (!sampler.endHeights(x4Origin, z4Origin, sw, sw, raw)) {
            return false;
        }
        for (int pz = P_MIN; pz <= P_MAX; pz++) {
            final int j0 = 2 * pz - subMin;
            final int j1 = j0 + 1;
            for (int px = P_MIN; px <= P_MAX; px++) {
                final int i0 = 2 * px - subMin;
                final int i1 = i0 + 1;
                final int a = raw[j0 * sw + i0];
                final int b = raw[j0 * sw + i1];
                final int c = raw[j1 * sw + i0];
                final int d = raw[j1 * sw + i1];
                final int value;
                if (a == 0 && b == 0 && c == 0 && d == 0) {
                    value = BaselineGrid.NO_SURFACE;
                } else {
                    final int mean = Math.floorDiv(a + b + c + d, 4);
                    value = mean;
                }
                grid.terrainY[BaselineGrid.index(px, pz)] = value;
            }
        }
        return true;
    }

}
