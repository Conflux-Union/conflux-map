package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.model.DimensionId;

/**
 * Fills a {@link BaselineGrid} for one predicted tile. Every Overworld version follows the same
 * two-level layout: a cheap terrain overview at every output pixel, corrected by a globally
 * aligned sparse grid of exact cubiomes columns. The overview keeps LOD3-4 spatially detailed;
 * the exact residual grid removes broad height drift without making a fullscreen viewport pay for
 * a complete world-generation column at every texel. End heights retain their
 * dimension-specific interpolation and pooling path. The biome-identity view bypasses all of
 * the Overworld height work via {@link #sampleOverworldBiomes}.
 * <ul>
 *   <li>LOD0-1: final biomes are sampled at block scale, one lookup per output pixel.
 *   <li>LOD2-4: final biomes are sampled once per output pixel on the anchor-lattice
    *       copy/refine sweep - LOD2 at native scale 4, LOD3-4 at a 2-cell or 4-cell stride so
    *       every output pixel still maps to its own world position instead of falling back to a
    *       coarser pre-zoom layer. The former LOD3-4 2x2 sub-sample sweep is switched off (see
    *       {@link #BIOME_SUB_PER_AXIS}) and its plumbing stays dormant.
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

    /** Final biome layer used per LOD (cubiomes {@code Range} scale: 1 or 4). */
    static final int[] BIOME_SCALE = {1, 1, 4, 4, 4};
    /** Native coordinates advanced per output pixel at each LOD. */
    static final int[] BIOME_STRIDE = {1, 2, 1, 2, 4};
    /**
     * Biome sub-samples per axis per output pixel. LOD0-2 already sample at or below the native
     * 4-block biome grid, so one lookup per pixel loses nothing there. The coarse LODs' 2x2 sweep
     * was switched off: its measured thin-feature retention (88% versus 69% of river/shoreline
     * pixels at LOD3, 80% versus 58% at LOD4, against full 1-block generation) did not justify
     * 4x the biome sampling cost once the composer stopped box-filtering sub-sample colours.
     * Every LOD currently runs {@link BaselineGrid#NO_SUPERSAMPLING} and the sweep machinery
     * stays dormant here.
     */
    static final int[] BIOME_SUB_PER_AXIS = {1, 1, 1, 1, 1};

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
        return sample(sampler, end, lod, tileOriginX, tileOriginZ, null);
    }

    /**
     * Optional per-{@link OverworldStage} wall sink ({@link #OVERWORLD_STAGE_COUNT} longs, or
     * null to sample unmeasured); non-Overworld paths leave it untouched.
     */
    public static BaselineGrid sample(
        final BaselineSampler sampler,
        final boolean end,
        final int lod,
        final int tileOriginX,
        final int tileOriginZ,
        final long[] overworldStageWallNanos
    ) {
        final BaselineGrid grid = new BaselineGrid(
            lod, tileOriginX, tileOriginZ, end ? BaselineGrid.NO_SUPERSAMPLING : BIOME_SUB_PER_AXIS[lod]
        );
        if (!end) {
            return OverworldStages.sampleOverworld(sampler, lod, tileOriginX, tileOriginZ, grid, overworldStageWallNanos)
                ? grid
                : null;
        }

        if (!EndHeightsSampling.sampleEndHeights(sampler, lod, tileOriginX, tileOriginZ, grid)
            || !sampleBiomes(sampler, lod, tileOriginX, tileOriginZ, grid)) {
            return null;
        }
        if (lod == 4) {
            EndHeightsSampling.refineEndLod4Coverage(grid);
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
     * Samples the Overworld biome-identity plane with no terrain work at all: every biome comes
     * from a terrain-free native query and the unsolved height columns are replaced by one flat
     * land plane, which is all the flat-color biome composer ever needs. The shape follows
     * {@link BaselineSampler#terrainFreeBiomeScale()}:
     * <ul>
 *   <li>Scale 1 (pre-1.18 layer stacks): the height-aware pipeline's own per-LOD
    *       {@code BIOME_SCALE}/{@code BIOME_STRIDE} sampling (plus any raw sub-samples the
    *       per-LOD sub-sampling config calls for), so the biome ids stay bit-identical to the
    *       full pipeline - only the height passes drop out. The shim's pre-1.18 {@code
    *       surfaceBiomes} delegates to the same layer-stack queries at the same coordinates,
    *       which is what makes the ids provably unchanged.
  *   <li>Scale 16 (post-1.18 climate model): one dense scale-16 climate grid, each output
     *       pixel taking the biome of the climate cell containing its first block. Climate
     *       answers exist at 16-block granularity only, so every LOD shares the same cells and
     *       supersampling is dropped - a sub-sample inside one cell cannot differ from its
     *       pixel. This is the cubiomes-viewer semantics: hillsides may show the climate biome
     *       rather than the surface biome the height-aware pipeline would have resolved.
     * </ul>
     * Falls back to the full height-aware {@link #sample} when the sampler offers no
     * terrain-free scale or the terrain-free query fails.
     */
    public static BaselineGrid sampleOverworldBiomes(
        final BaselineSampler sampler,
        final int lod,
        final int tileOriginX,
        final int tileOriginZ
    ) {
        final int terrainFreeScale = sampler.terrainFreeBiomeScale();
        if (terrainFreeScale <= 0) {
            return sample(sampler, false, lod, tileOriginX, tileOriginZ);
        }
        final BaselineGrid grid = terrainFreeScale >= 16
            ? sampleClimateCellBiomes(sampler, lod, tileOriginX, tileOriginZ, terrainFreeScale)
            : sampleTerrainFreeLayerBiomes(sampler, lod, tileOriginX, tileOriginZ);
        if (grid == null) {
            return sample(sampler, false, lod, tileOriginX, tileOriginZ);
        }
        fillFlatLandPlane(grid);
        return grid;
    }

    /**
     * Layer-stack shape: the standard per-LOD biome table plus the raw sub-biome sweep, both of
     * which are pure biome-stack queries before 1.18.
     */
    private static BaselineGrid sampleTerrainFreeLayerBiomes(
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
        if (grid.supersampled()) {
            sampleRawSubBiomes(sampler, lod, tileOriginX, tileOriginZ, grid);
        }
        return grid;
    }

    /** Climate shape: one dense {@code climateScale} grid nearest-expanded to output pixels. */
    private static BaselineGrid sampleClimateCellBiomes(
        final BaselineSampler sampler,
        final int lod,
        final int tileOriginX,
        final int tileOriginZ,
        final int climateScale
    ) {
        final BaselineGrid grid = new BaselineGrid(
            lod, tileOriginX, tileOriginZ, BaselineGrid.NO_SUPERSAMPLING
        );
        final int blocksPerPixel = 1 << lod;
        final int cellMinX = Math.floorDiv(tileOriginX + P_MIN * blocksPerPixel, climateScale);
        final int cellMinZ = Math.floorDiv(tileOriginZ + P_MIN * blocksPerPixel, climateScale);
        final int cellMaxX = Math.floorDiv(tileOriginX + P_MAX * blocksPerPixel, climateScale);
        final int cellMaxZ = Math.floorDiv(tileOriginZ + P_MAX * blocksPerPixel, climateScale);
        final int cellsW = cellMaxX - cellMinX + 1;
        final int cellsH = cellMaxZ - cellMinZ + 1;
        final int[] cells = new int[cellsW * cellsH];
        if (!sampler.biomes(climateScale, cellMinX, cellMinZ, cellsW, cellsH, cells)) {
            return null;
        }
        for (int pz = P_MIN; pz <= P_MAX; pz++) {
            final int cellZ = Math.floorDiv(tileOriginZ + pz * blocksPerPixel, climateScale) - cellMinZ;
            for (int px = P_MIN; px <= P_MAX; px++) {
                final int cellX = Math.floorDiv(tileOriginX + px * blocksPerPixel, climateScale) - cellMinX;
                grid.biomeId[BaselineGrid.index(px, pz)] = cells[cellZ * cellsW + cellX];
            }
        }
        return grid;
    }

    /**
     * Stands in for the unsolved terrain: sea level exactly, so no column classifies as
     * compatibility-fluid, and no column can derive as void. The biome-identity composer colors
     * by biome id alone, so these heights exist only to keep the derived-grid shape valid.
     */
    private static void fillFlatLandPlane(final BaselineGrid grid) {
        java.util.Arrays.fill(grid.terrainY, BaselineDeriver.WATER_LEVEL);
        java.util.Arrays.fill(grid.baseSurfaceY, BaselineDeriver.WATER_LEVEL);
        if (grid.supersampled()) {
            java.util.Arrays.fill(grid.subBaseSurfaceY, BaselineDeriver.WATER_LEVEL);
        }
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

    /** Samples a flat biome-identity plane at one Nether block Y without predicting terrain. */
    public static BaselineGrid sampleNetherBiomesAtY(
        final BaselineSampler sampler,
        final int lod,
        final int tileOriginX,
        final int tileOriginZ,
        final int blockY
    ) {
        final BaselineGrid grid = new BaselineGrid(
            lod, tileOriginX, tileOriginZ, BIOME_SUB_PER_AXIS[lod]
        );
        if (!sampleBiomesAtY(sampler, lod, tileOriginX, tileOriginZ, blockY, grid)) {
            return null;
        }
        java.util.Arrays.fill(grid.terrainY, blockY);
        java.util.Arrays.fill(grid.baseSurfaceY, blockY);
        if (grid.supersampled()) {
            sampleRawSubBiomesAtY(sampler, lod, tileOriginX, tileOriginZ, blockY, grid);
            java.util.Arrays.fill(grid.subBaseSurfaceY, blockY);
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

        // The lattice sweep works on whole 2x2-pixel cells, so a window at the lattice LODs
        // (LOD2-4: per-pixel biomes, no sub-sampling) expands by up to one pixel per side to
        // cell boundaries. Everything the expansion touches - cell partition, anchors, overview
        // heights - is absolute to the tile, so the window sees exactly the values the full
        // tile's sweep produced for the same pixels.
        final boolean lattice = sampler.interpolatableOverviewHeights()
            && lod >= OverworldStages.OVERVIEW_ANCHOR_MIN_LOD;
        final int[] terrain = new int[width * height];
        if (lattice) {
            final int sub = grid.subPerAxis;
            final int step = 2 * sub;
            final int minSubX = (minPixelX - P_MIN) * sub;
            final int maxSubX = (minPixelX - P_MIN + width) * sub;
            final int minSubZ = (minPixelZ - P_MIN) * sub;
            final int maxSubZ = (minPixelZ - P_MIN + height) * sub;
            final int cellX0 = Math.floorDiv(minSubX, step);
            final int cellZ0 = Math.floorDiv(minSubZ, step);
            final int cellX1 = (maxSubX + step - 1) / step;
            final int cellZ1 = (maxSubZ + step - 1) / step;
            /*
             * The cell sweep needs the cells' own pixels [2c0-1, 2c1-1]; the widened biome-anchor
             * query below additionally reads the pixel under anchor cells one further out, so the
             * expansion covers [2*anchorCell0, 2*(c1+1)] clamped to the tile grid. Everything is
             * tile-absolute, so the wider window just reproduces more of the full tile.
             */
            final int anchorCellMinX = Math.max(0, cellX0 - 1);
            final int anchorCellMinZ = Math.max(0, cellZ0 - 1);
            final int anchorCellMaxX = Math.min(OverworldStages.LATTICE_CELL_COUNT, cellX1 + 1);
            final int anchorCellMaxZ = Math.min(OverworldStages.LATTICE_CELL_COUNT, cellZ1 + 1);
            final int expMinPx = Math.max(P_MIN, 2 * anchorCellMinX - 1);
            final int expMinPz = Math.max(P_MIN, 2 * anchorCellMinZ - 1);
            final int expMaxPx = Math.min(P_MAX, 2 * anchorCellMaxX - 1);
            final int expMaxPz = Math.min(P_MAX, 2 * anchorCellMaxZ - 1);
            final int expW = expMaxPx - expMinPx + 1;
            final int expH = expMaxPz - expMinPz + 1;
            final int[] expTerrain = new int[expW * expH];
            final OverworldStages.OverviewLattice latticeData = OverworldStages.solveOverviewLattice(
                sampler, lod, tileOriginX, tileOriginZ, expMinPx, expMinPz, expW, expH
            );
            int[] windowAnchors = null;
            final int anchorCellX0 = anchorCellMinX;
            final int anchorCellZ0 = anchorCellMinZ;
            final int anchorCellsW = anchorCellMaxX - anchorCellX0;
            final int anchorCellsH = anchorCellMaxZ - anchorCellZ0;
            if (latticeData != null) {
                OverworldStages.reconstructOverviewLattice(expMinPx, expMinPz, expW, expH, latticeData, expTerrain);
                /*
                 * The band gate reads biome anchors one cell beyond each window edge, so the
                 * anchor query widens by one cell per side - clamped to the tile's own cell
                 * range, because the full tile treats anchors outside it as absent and the
                 * window must resolve the same gate verdicts.
                 */
                windowAnchors = SubBiomeSampling.solveSubBiomeAnchors(
                    sampler, lod, tileOriginX, tileOriginZ, sub,
                    anchorCellX0, anchorCellZ0, anchorCellsW, anchorCellsH,
                    expTerrain, expMinPx + 1, expMinPz + 1, expW
                );
                if (!OverworldStages.resolveOverviewBand(
                    sampler, lod, tileOriginX, tileOriginZ,
                    expMinPx, expMinPz, expW, expH,
                    latticeData, expTerrain, windowAnchors,
                    anchorCellsW + 1, anchorCellsH + 1, anchorCellX0, anchorCellZ0
                )) {
                    return null;
                }
            }
            if (latticeData == null && !sampler.overviewHeights(
                tileOriginX + expMinPx * blocksPerPixel,
                tileOriginZ + expMinPz * blocksPerPixel,
                expW, expH, blocksPerPixel, expTerrain
            )) {
                return null;
            }
            for (int z = 0; z < height; z++) {
                System.arraycopy(
                    expTerrain, (z + minPixelZ - expMinPz) * expW + (minPixelX - expMinPx),
                    terrain, z * width, width
                );
            }
            final int[] expanded = windowAnchors != null
                ? SubBiomeSampling.runSubBiomeCells(
                    sampler, lod, tileOriginX, tileOriginZ, sub,
                    cellX0, cellZ0, cellX1 - cellX0, cellZ1 - cellZ0,
                    expTerrain, expMinPx + 1, expMinPz + 1, expW,
                    windowAnchors, anchorCellsW + 1, anchorCellX0, anchorCellZ0
                )
                : SubBiomeSampling.sampleSubBiomeLatticeRange(
                    sampler, lod, tileOriginX, tileOriginZ, sub,
                    cellX0, cellZ0, cellX1 - cellX0, cellZ1 - cellZ0,
                    expTerrain, expMinPx + 1, expMinPz + 1, expW
                );
            if (expanded != null) {
                final int subWidth = width * sub;
                final int subHeight = height * sub;
                final int rawW = (cellX1 - cellX0) * step;
                final int offsetX = minSubX - cellX0 * step;
                final int offsetZ = minSubZ - cellZ0 * step;
                final int[] windowSubRaw = new int[subWidth * subHeight];
                for (int z = 0; z < subHeight; z++) {
                    System.arraycopy(
                        expanded, (z + offsetZ) * rawW + offsetX,
                        windowSubRaw, z * subWidth, subWidth
                    );
                }
                scatterSubBiomeWindow(grid, windowSubRaw, minPixelX, minPixelZ, width, height);
                copyWindow(grid.terrainY, terrain, minPixelX, minPixelZ, width, height);
                resolveOverviewFluidWindow(grid, minPixelX, minPixelZ, maxPixelX, maxPixelZ);
                return grid;
            }
        } else if (!sampler.overviewHeights(
            blockX, blockZ, width, height, blocksPerPixel, terrain
        )) {
            return null;
        }
        // Same subsumption as the full tile: a successful sub-sample sweep answers the pixel's
        // own biome from its first sub-sample, so the centre pass never runs separately.
        final int[] subRaw = sampleSubBiomeWindowRaw(
            sampler, grid, blockX, blockZ, width, height,
            blocksPerPixel, terrain
        );
        if (subRaw != null) {
            scatterSubBiomeWindow(grid, subRaw, minPixelX, minPixelZ, width, height);
        } else {
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
            copyWindow(grid.biomeId, biomes, minPixelX, minPixelZ, width, height);
            fillSubBiomeWindowFromPixels(grid, minPixelX, minPixelZ, width, height);
        }
        copyWindow(grid.terrainY, terrain, minPixelX, minPixelZ, width, height);
        resolveOverviewFluidWindow(grid, minPixelX, minPixelZ, maxPixelX, maxPixelZ);
        return grid;
    }

    /** Window variant of {@link #sampleSubBiomeRaw}: the raw sweep, or null when unavailable. */
    private static int[] sampleSubBiomeWindowRaw(
        final BaselineSampler sampler,
        final BaselineGrid grid,
        final int blockX,
        final int blockZ,
        final int width,
        final int height,
        final int blocksPerPixel,
        final int[] terrain
    ) {
        if (!grid.supersampled()) {
            return null;
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
        return sampler.surfaceBiomes(
            blockX, blockZ, subWidth, subHeight, blocksPerPixel / sub, heights, biomes
        ) ? biomes : null;
    }

    /** Window variant of {@link #scatterSubBiomes}, including each pixel's own first sub-sample. */
    private static void scatterSubBiomeWindow(
        final BaselineGrid grid,
        final int[] raw,
        final int minPixelX,
        final int minPixelZ,
        final int width,
        final int height
    ) {
        final int sub = grid.subPerAxis;
        final int subWidth = width * sub;
        final int subHeight = height * sub;
        for (int z = 0; z < subHeight; z++) {
            for (int x = 0; x < subWidth; x++) {
                final int pixelX = minPixelX + x / sub;
                final int pixelZ = minPixelZ + z / sub;
                final int pixel = BaselineGrid.index(pixelX, pixelZ);
                if (grid.supersampled()) {
                    grid.subBiomeId[grid.subIndex(pixel, x % sub, z % sub)] = raw[z * subWidth + x];
                }
                if (x % sub == 0 && z % sub == 0) {
                    grid.biomeId[pixel] = raw[z * subWidth + x];
                }
            }
        }
    }

    private static void fillSubBiomeWindowFromPixels(
        final BaselineGrid grid,
        final int minPixelX,
        final int minPixelZ,
        final int width,
        final int height
    ) {
        if (!grid.supersampled()) {
            return;
        }
        final int sub = grid.subPerAxis;
        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                final int pixel = BaselineGrid.index(minPixelX + x, minPixelZ + z);
                for (int sz = 0; sz < sub; sz++) {
                    for (int sx = 0; sx < sub; sx++) {
                        grid.subBiomeId[grid.subIndex(pixel, sx, sz)] = grid.biomeId[pixel];
                    }
                }
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
                final boolean fluid = solidY < OverworldStages.PREDICTED_FLUID_CEILING
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
                        final boolean subFluid = solidY < OverworldStages.PREDICTED_FLUID_CEILING
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

    static boolean sampleBiomes(
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

    private static boolean sampleBiomesAtY(
        final BaselineSampler sampler,
        final int lod,
        final int tileOriginX,
        final int tileOriginZ,
        final int blockY,
        final BaselineGrid grid
    ) {
        final int stride = 1 << lod;
        final int nativeX0 = tileOriginX + P_MIN * stride;
        final int nativeZ0 = tileOriginZ + P_MIN * stride;
        final int[] raw = new int[BaselineGrid.SIZE * BaselineGrid.SIZE];
        if (!sampler.biomesAtYStrided(
            blockY, 1, nativeX0, nativeZ0,
            BaselineGrid.SIZE, BaselineGrid.SIZE, stride, raw
        )) {
            return false;
        }
        System.arraycopy(raw, 0, grid.biomeId, 0, raw.length);
        return true;
    }

    /**
     * Coarse sub-samples straight from the biome source: Nether pixels use raw 3D-biome samples
     * rather than Overworld surface-biome queries, and the pre-1.18 biome-identity view calls it
     * for any raw sub-samples its per-LOD config requests - its layer-stack queries need no
     * heights to sub-sample. Dormant while every LOD runs one sample per pixel.
     */
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

    private static void sampleRawSubBiomesAtY(
        final BaselineSampler sampler,
        final int lod,
        final int tileOriginX,
        final int tileOriginZ,
        final int blockY,
        final BaselineGrid grid
    ) {
        final int sub = grid.subPerAxis;
        final int blocksPerPixel = 1 << lod;
        final int blockStride = blocksPerPixel / sub;
        final int width = BaselineGrid.SIZE * sub;
        final int nativeX = tileOriginX + P_MIN * blocksPerPixel;
        final int nativeZ = tileOriginZ + P_MIN * blocksPerPixel;
        final int[] raw = new int[width * width];
        final boolean sampled = sampler.biomesAtYStrided(
            blockY, 1, nativeX, nativeZ, width, width, blockStride, raw
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

}
