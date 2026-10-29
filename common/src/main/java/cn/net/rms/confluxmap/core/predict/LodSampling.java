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
 *   <li>LOD2: final biomes are sampled at native scale 4, one lookup per output pixel, solved on
 *       the same anchor-lattice copy/refine sweep the supersampled LODs use.
 *   <li>LOD3-4: scale-4 biomes use a 2-cell or 4-cell stride so every output pixel still maps to
 *       its own world position instead of falling back to a coarser pre-zoom layer. Because a
 *       pixel there spans 8 or 16 blocks - wider than a river - biomes are additionally sampled
 *       2x2 per pixel and box-filtered by the composer, mirroring how the captured map averages
 *       finer pixels into a coarse tile. The 2x2 sweep is also the pixel's own biome source: its
 *       first sample sits exactly at the pixel position, so no separate per-pixel biome pass runs
 *       at those LODs. See {@link #BIOME_SUB_PER_AXIS}.
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

    /**
     * Lowest LOD whose overview heights may be solved on a sparse anchor lattice and bilinearly
     * reconstructed, for samplers opting in via {@link
     * BaselineSampler#interpolatableOverviewHeights()}. Below it each output position keeps its
     * own exact overview solve: measured at LOD0-1 nearly every land pixel sits in the
     * interpolation-excluded mid-band, so the lattice degenerates into one fallback query per
     * pixel (slower than the direct pass) and still smears the near-zoom waterline badly enough
     * to trip the ocean-to-land gate (0.53% measured).
     */
    private static final int OVERVIEW_ANCHOR_MIN_LOD = 2;

    /**
     * Height band the anchor lattice refuses to interpolate across. A reconstructed value is only
     * used where every anchor corner sits deep on one side of the waterline: open water at least
     * {@link #OVERVIEW_INTERPOLATION_MAX_WATER_HEIGHT} below sea level, or highland at least
     * {@code OVERVIEW_INTERPOLATION_MIN_HEIGHT} above it. River beds carve roughly a dozen blocks
     * below their surrounding plains, so a plains-side anchor pair can hide a sub-anchor river the
     * reconstruction would pave over - measured as a 1.1% ocean-to-land flip rate, four times the
     * hard gate - and shorelines cut anchor quads diagonally; near-shelf quads can equally hide a
     * small islet the other way. Those zones keep exact per-pixel solves; only the flat flanks
     * (deep ocean, mountain interiors) interpolate.
     */
    private static final int OVERVIEW_INTERPOLATION_MIN_HEIGHT = BaselineDeriver.WATER_LEVEL + 18;
    /**
     * Water-side counterpart: a quad whose highest anchor reaches this close to sea level can
     * hide a shelf islet, so it stays exact. Only deeper open water interpolates.
     */
    private static final int OVERVIEW_INTERPOLATION_MAX_WATER_HEIGHT = BaselineDeriver.WATER_LEVEL - 6;

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
     * Samples the Overworld biome-identity plane with no terrain work at all: every biome comes
     * from a terrain-free native query and the unsolved height columns are replaced by one flat
     * land plane, which is all the flat-color biome composer ever needs. The shape follows
     * {@link BaselineSampler#terrainFreeBiomeScale()}:
     * <ul>
     *   <li>Scale 1 (pre-1.18 layer stacks): the height-aware pipeline's own per-LOD
     *       {@code BIOME_SCALE}/{@code BIOME_STRIDE} sampling and LOD3-4 raw sub-samples, so
     *       the biome ids stay bit-identical to the full pipeline - only the height passes drop
     *       out. The shim's pre-1.18 {@code surfaceBiomes} delegates to the same layer-stack
     *       queries at the same coordinates, which is what makes the ids provably unchanged.
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
        // (supersampled LOD3-4, plain LOD2) expands by up to one pixel per side to cell
        // boundaries. Everything the expansion touches - cell partition, anchors, overview
        // heights - is absolute to the tile, so the window sees exactly the values the full
        // tile's sweep produced for the same sub-samples.
        final boolean lattice = sampler.interpolatableOverviewHeights()
            && (grid.supersampled() || lod == 2);
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
            final int anchorCellMaxX = Math.min(LATTICE_CELL_COUNT, cellX1 + 1);
            final int anchorCellMaxZ = Math.min(LATTICE_CELL_COUNT, cellZ1 + 1);
            final int expMinPx = Math.max(P_MIN, 2 * anchorCellMinX - 1);
            final int expMinPz = Math.max(P_MIN, 2 * anchorCellMinZ - 1);
            final int expMaxPx = Math.min(P_MAX, 2 * anchorCellMaxX - 1);
            final int expMaxPz = Math.min(P_MAX, 2 * anchorCellMaxZ - 1);
            final int expW = expMaxPx - expMinPx + 1;
            final int expH = expMaxPz - expMinPz + 1;
            final int[] expTerrain = new int[expW * expH];
            final OverviewLattice latticeData = solveOverviewLattice(
                sampler, lod, tileOriginX, tileOriginZ, expMinPx, expMinPz, expW, expH
            );
            int[] windowAnchors = null;
            int anchorCellX0 = cellX0;
            int anchorCellZ0 = cellZ0;
            int anchorCellsW = cellX1 - cellX0;
            int anchorCellsH = cellZ1 - cellZ0;
            if (latticeData != null) {
                reconstructOverviewLattice(expMinPx, expMinPz, expW, expH, latticeData, expTerrain);
                /*
                 * The band gate reads biome anchors one cell beyond each window edge, so the
                 * anchor query widens by one cell per side - clamped to the tile's own cell
                 * range, because the full tile treats anchors outside it as absent and the
                 * window must resolve the same gate verdicts.
                 */
                anchorCellX0 = anchorCellMinX;
                anchorCellZ0 = anchorCellMinZ;
                anchorCellsW = anchorCellMaxX - anchorCellX0;
                anchorCellsH = anchorCellMaxZ - anchorCellZ0;
                windowAnchors = solveSubBiomeAnchors(
                    sampler, lod, tileOriginX, tileOriginZ, sub,
                    anchorCellX0, anchorCellZ0, anchorCellsW, anchorCellsH,
                    expTerrain, expMinPx + 1, expMinPz + 1, expW
                );
                if (!resolveOverviewBand(
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
                ? runSubBiomeCells(
                    sampler, lod, tileOriginX, tileOriginZ, sub,
                    cellX0, cellZ0, cellX1 - cellX0, cellZ1 - cellZ0,
                    expTerrain, expMinPx + 1, expMinPz + 1, expW,
                    windowAnchors, anchorCellsW + 1, anchorCellX0, anchorCellZ0
                )
                : sampleSubBiomeLatticeRange(
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
        // Same subsumption as the full tile: a successful 2x2 sweep answers the pixel's own
        // biome from its first sub-sample, so the centre pass never runs separately.
        final int[] subRaw = sampleSubBiomeWindowRaw(
            sampler, grid, blockX, blockZ, minPixelX, minPixelZ, width, height,
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
        final int minPixelX,
        final int minPixelZ,
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
        final int[] fluidConfidence = new int[grid.terrainY.length];
        /*
         * Lattice LODs (2-4, opting-in samplers) run the coarse stages in dependency order:
         * anchor heights, then the sub-biome anchor sweep (it reads only even anchor-pixel
         * heights, so it may run while band pixels still hold reconstruction values), then the
         * waterline band resolution - which consults the biome anchors to skip quads that are
         * provably waterline-irrelevant - and finally the cell copy/refine loop, whose guards
         * read the now-final band pixel heights. A failed anchor query leaves the band ungated
         * (every crossing pixel solves exactly) and the biome stage on its own fallbacks.
         */
        OverviewLattice lattice = null;
        int[] biomeAnchors = null;
        if (lod >= OVERVIEW_ANCHOR_MIN_LOD && sampler.interpolatableOverviewHeights()
            && (grid.supersampled() || lod == 2)) {
            lattice = solveOverviewLattice(
                sampler, lod, tileOriginX, tileOriginZ,
                P_MIN, P_MIN, BaselineGrid.SIZE, BaselineGrid.SIZE
            );
            if (lattice != null) {
                reconstructOverviewLattice(
                    P_MIN, P_MIN, BaselineGrid.SIZE, BaselineGrid.SIZE,
                    lattice, grid.terrainY
                );
                biomeAnchors = solveSubBiomeAnchors(
                    sampler, lod, tileOriginX, tileOriginZ, grid.subPerAxis,
                    0, 0, LATTICE_CELL_COUNT, LATTICE_CELL_COUNT,
                    grid.terrainY, 0, 0, BaselineGrid.SIZE
                );
                if (!resolveOverviewBand(
                    sampler, lod, tileOriginX, tileOriginZ,
                    P_MIN, P_MIN, BaselineGrid.SIZE, BaselineGrid.SIZE,
                    lattice, grid.terrainY, biomeAnchors,
                    LATTICE_CELL_COUNT + 1, LATTICE_CELL_COUNT + 1, 0, 0
                )) {
                    return false;
                }
            }
        }
        if (lattice == null
            && !sampler.overviewHeights(
                tileOriginX + P_MIN * blocksPerPixel,
                tileOriginZ + P_MIN * blocksPerPixel,
                BaselineGrid.SIZE, BaselineGrid.SIZE, blocksPerPixel, grid.terrainY
            )) {
            return false;
        }
        if (lod <= MAX_EXACT_RESIDUAL_LOD && !applyExactResiduals(
            sampler, lod, tileOriginX, tileOriginZ, blocksPerPixel, grid,
            fluidConfidence
        )) {
            return false;
        }
        // The supersampled 2x2 sweep subsumes the per-pixel pass: its first sub-sample sits at
        // the pixel's own position with the pixel's own height, so the pixel biome is that sample
        // and the centre pass would only repeat it. At LOD2 the same lattice answers the plain
        // per-pixel table directly. Samplers without the sweep keep the old order.
        final int[] subRaw = biomeAnchors != null
            ? runSubBiomeCells(
                sampler, lod, tileOriginX, tileOriginZ, grid.subPerAxis,
                0, 0, LATTICE_CELL_COUNT, LATTICE_CELL_COUNT,
                grid.terrainY, 0, 0, BaselineGrid.SIZE,
                biomeAnchors, LATTICE_CELL_COUNT + 1, 0, 0
            )
            : sampleSubBiomeRaw(sampler, lod, tileOriginX, tileOriginZ, grid);
        if (subRaw != null) {
            if (grid.supersampled()) {
                scatterSubBiomes(grid, subRaw);
            } else {
                System.arraycopy(subRaw, 0, grid.biomeId, 0, subRaw.length);
            }
        } else {
            if (!sampleSurfaceBiomes(sampler, lod, tileOriginX, tileOriginZ, grid)
                && !sampleBiomes(sampler, lod, tileOriginX, tileOriginZ, grid)) {
                return false;
            }
            sampleSubBiomes(sampler, lod, tileOriginX, tileOriginZ, grid);
        }
        resolveOverviewFluids(grid, lod, fluidConfidence);
        return true;
    }

    /** Overview anchor lattice over even tile-local pixels for one LOD2-4 window. */
    private static final class OverviewLattice {
        final int[] heights;
        /** Tile-local anchor index of {@code heights[0][0]}; anchor k sits at pixel {@code 2k}. */
        final int minKx;
        final int minKz;
        final int w;
        final int h;

        OverviewLattice(final int[] heights, final int minKx, final int minKz, final int w, final int h) {
            this.heights = heights;
            this.minKx = minKx;
            this.minKz = minKz;
            this.w = w;
            this.h = h;
        }
    }

    /**
     * Solves the overview anchor lattice covering the tile-local pixel window, or returns null
     * when the query fails so the caller can fall back to plain per-pixel solves. The lattice
     * aligns to even tile-local pixel indices - so a window update and its containing full tile
     * reconstruct from identical anchors and agree pixel-for-pixel - and anchors run one beyond
     * an odd last pixel so it has a pair to interpolate from. Anchor solves go through the
     * coarse entry: their stride is double the pixel stride, detail finer than that is dropped.
     */
    private static OverviewLattice solveOverviewLattice(
        final BaselineSampler sampler,
        final int lod,
        final int tileOriginX,
        final int tileOriginZ,
        final int minPixelX,
        final int minPixelZ,
        final int width,
        final int height
    ) {
        final int blocksPerPixel = 1 << lod;
        final int anchorMinKx = Math.floorDiv(minPixelX, 2);
        final int anchorMinKz = Math.floorDiv(minPixelZ, 2);
        final int maxPixelX = minPixelX + width - 1;
        final int maxPixelZ = minPixelZ + height - 1;
        final int anchorW = Math.floorDiv(maxPixelX, 2) + (maxPixelX & 1) - anchorMinKx + 1;
        final int anchorH = Math.floorDiv(maxPixelZ, 2) + (maxPixelZ & 1) - anchorMinKz + 1;
        final int[] anchors = new int[anchorW * anchorH];
        if (!sampler.overviewHeightsCoarse(
            tileOriginX + 2 * anchorMinKx * blocksPerPixel,
            tileOriginZ + 2 * anchorMinKz * blocksPerPixel,
            anchorW, anchorH, 2 * blocksPerPixel, anchors
        )) {
            return null;
        }
        return new OverviewLattice(anchors, anchorMinKx, anchorMinKz, anchorW, anchorH);
    }

    /**
     * Fills {@code out} (dense {@code width}x{@code height} window layout) with the bilinear
     * reconstruction of {@link #solveOverviewLattice}'s anchors. Every odd pixel gets its
     * interpolated value, including waterline-band pixels - {@link #resolveOverviewBand} replaces
     * those afterwards once the biome anchors are known.
     */
    private static void reconstructOverviewLattice(
        final int minPixelX,
        final int minPixelZ,
        final int width,
        final int height,
        final OverviewLattice lattice,
        final int[] out
    ) {
        for (int j = 0; j < height; j++) {
            final int pixelZ = minPixelZ + j;
            final int anchorZ = Math.floorDiv(pixelZ, 2) - lattice.minKz;
            final int oddZ = pixelZ & 1;
            for (int i = 0; i < width; i++) {
                final int pixelX = minPixelX + i;
                final int anchorX = Math.floorDiv(pixelX, 2) - lattice.minKx;
                final int oddX = pixelX & 1;
                final int h00 = lattice.heights[anchorZ * lattice.w + anchorX];
                int value = h00;
                if (oddX != 0 || oddZ != 0) {
                    final int h10 = lattice.heights[anchorZ * lattice.w + anchorX + 1];
                    final int top = oddX != 0 ? lerp(h00, h10, 1, 2) : h00;
                    if (oddZ != 0) {
                        final int h01 = lattice.heights[(anchorZ + 1) * lattice.w + anchorX];
                        final int bottom = oddX != 0
                            ? lerp(h01, lattice.heights[(anchorZ + 1) * lattice.w + anchorX + 1], 1, 2)
                            : h01;
                        value = lerp(top, bottom, 1, 2);
                    } else {
                        value = top;
                    }
                }
                out[j * width + i] = value;
            }
        }
    }

    /**
     * Height above sea level from which a crossing quad is only waterline-relevant when the
     * biome anchors say so. Quads whose anchors all sit under this height can themselves
     * straddle the waterline, so they always keep exact solves; above it, only the +18 hidden
     * carve protection of {@link #OVERVIEW_INTERPOLATION_MIN_HEIGHT} applied, which made every
     * mid-altitude quad exact. A river biome cell announces itself at an anchor at these LODs'
     * spacings (16 blocks wide versus 8/16/32-block anchors), so quads with no water-biome
     * anchor in their neighbourhood carry no hidden river and interpolate.
     */
    private static final int OVERVIEW_INTERPOLATION_BIOME_GATE_HEIGHT = BaselineDeriver.WATER_LEVEL + 2;

    /** Whether a biome id marks water-relevant terrain for the band gate. */
    private static boolean waterRelevantBiome(final int biomeId) {
        return BiomeTable.get(biomeId).waterBiome();
    }

    /**
     * Resolves the waterline band for the reconstructed window: every odd pixel whose anchor
     * quad crosses {@link #OVERVIEW_INTERPOLATION_MAX_WATER_HEIGHT}..{@link
     * * OVERVIEW_INTERPOLATION_MIN_HEIGHT} keeps an exact per-pixel solve unless the quad sits
     * entirely above {@link #OVERVIEW_INTERPOLATION_BIOME_GATE_HEIGHT} with no water-relevant
     * biome anchor in the 3x3 anchor neighbourhood around it - those interpolate, because the
     * band's job there was only hidden-river protection and the biome lattice answers that
     * directly. {@code biomeAnchors} null (the anchor sweep failed) disables the gate and keeps
     * every crossing pixel exact. Stride 4, not 1, for the solves: a stride-1 query bilinearly
     * blends neighbouring quart cells, while 4 keeps the per-cell solve the replaced per-pixel
     * pass had; full detail, not the coarse entry, because these pixels are the waterline.
     */
    private static boolean resolveOverviewBand(
        final BaselineSampler sampler,
        final int lod,
        final int tileOriginX,
        final int tileOriginZ,
        final int minPixelX,
        final int minPixelZ,
        final int width,
        final int height,
        final OverviewLattice lattice,
        final int[] out,
        final int[] biomeAnchors,
        final int biomeAnchorsW,
        final int biomeAnchorsH,
        final int biomeAnchorCellsX0,
        final int biomeAnchorCellsZ0
    ) {
        final int blocksPerPixel = 1 << lod;
        final int[] exact = new int[1];
        for (int j = 0; j < height; j++) {
            final int pixelZ = minPixelZ + j;
            final int anchorZ = Math.floorDiv(pixelZ, 2) - lattice.minKz;
            final int oddZ = pixelZ & 1;
            for (int i = 0; i < width; i++) {
                final int pixelX = minPixelX + i;
                final int anchorX = Math.floorDiv(pixelX, 2) - lattice.minKx;
                final int oddX = pixelX & 1;
                if (oddX == 0 && oddZ == 0) {
                    continue;
                }
                final int h00 = lattice.heights[anchorZ * lattice.w + anchorX];
                final int h10 = lattice.heights[anchorZ * lattice.w + anchorX + 1];
                int lowest = Math.min(h00, h10);
                int highest = Math.max(h00, h10);
                if (oddZ != 0) {
                    final int h01 = lattice.heights[(anchorZ + 1) * lattice.w + anchorX];
                    lowest = Math.min(lowest, h01);
                    highest = Math.max(highest, h01);
                    if (oddX != 0) {
                        final int h11 = lattice.heights[(anchorZ + 1) * lattice.w + anchorX + 1];
                        lowest = Math.min(lowest, h11);
                        highest = Math.max(highest, h11);
                    }
                }
                if (highest < OVERVIEW_INTERPOLATION_MAX_WATER_HEIGHT
                    || lowest >= OVERVIEW_INTERPOLATION_MIN_HEIGHT) {
                    continue;
                }
                if (biomeAnchors != null && lowest >= OVERVIEW_INTERPOLATION_BIOME_GATE_HEIGHT
                    && !waterRelevantNeighbourhood(
                        biomeAnchors, biomeAnchorsW, biomeAnchorsH,
                        biomeAnchorCellsX0, biomeAnchorCellsZ0,
                        anchorX + lattice.minKx, anchorZ + lattice.minKz)) {
                    continue;
                }
                if (!sampler.overviewHeights(
                    tileOriginX + pixelX * blocksPerPixel,
                    tileOriginZ + pixelZ * blocksPerPixel,
                    1, 1, 4, exact
                )) {
                    return false;
                }
                out[j * width + i] = exact[0];
            }
        }
        return true;
    }

    /*
     * Whether any biome anchor around an overview quad is water-relevant. The quad spans even
     * pixels 2k..2k+2; the biome lattice's odd anchors sit at pixels 2k-1, 2k+1, 2k+3, i.e.
     * array indices k..k+2 (shifted by the sweep's cell origin), so the 3x3 block centred on
     * the quad's interior anchor covers the quad plus one anchor of margin on each side.
     */
    private static boolean waterRelevantNeighbourhood(
        final int[] biomeAnchors,
        final int anchorsW,
        final int anchorsH,
        final int cellsX0,
        final int cellsZ0,
        final int quadKx,
        final int quadKz
    ) {
        for (int dz = 0; dz <= 2; dz++) {
            final int az = quadKz + dz - cellsZ0;
            if (az < 0 || az >= anchorsH) {
                continue;
            }
            for (int dx = 0; dx <= 2; dx++) {
                final int ax = quadKx + dx - cellsX0;
                if (ax < 0 || ax >= anchorsW) {
                    continue;
                }
                if (waterRelevantBiome(biomeAnchors[az * anchorsW + ax])) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Runs the raw sub-biome sweep: one height-aware {@code surfaceBiomes} query at
     * {@code sub}-per-pixel resolution, each sub-sample handed its pixel's centre height, exactly
     * as the per-pixel pass would resolve that column. Returns null when the grid is not
     * supersampled or the sampler cannot serve the query.
     *
     * <p>Opting-in samplers first take the sparse-lattice path ({@link
     * #sampleSubBiomeLatticeRange}) - at LOD3-4 for the supersampled sweep, and at LOD2 where the
     * same cell lattice covers plain per-pixel biomes with no supersampling at all; the dense
     * sweep here stays as the fallback and as the value-level contract that path must reproduce
     * outside its copied cells.
     */
    private static int[] sampleSubBiomeRaw(
        final BaselineSampler sampler,
        final int lod,
        final int tileOriginX,
        final int tileOriginZ,
        final BaselineGrid grid
    ) {
        if (sampler.interpolatableOverviewHeights()
            && (grid.supersampled() || lod == 2)) {
            final int[] fromLattice = sampleSubBiomeLatticeRange(
                sampler, lod, tileOriginX, tileOriginZ, grid.subPerAxis,
                0, 0, LATTICE_CELL_COUNT, LATTICE_CELL_COUNT,
                grid.terrainY, 0, 0, BaselineGrid.SIZE
            );
            if (fromLattice != null) {
                return fromLattice;
            }
        }
        if (!grid.supersampled()) {
            return null;
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
        return sampler.surfaceBiomes(
            tileOriginX + P_MIN * blocksPerPixel,
            tileOriginZ + P_MIN * blocksPerPixel,
            width,
            width,
            blocksPerPixel / sub,
            heights,
            raw
        ) ? raw : null;
    }

    /**
     * Lattice cells per axis: one cell spans two output pixels ({@code step = 2 * sub}
     * sub-samples) and the cells partition the sweep, so {@code SIZE / 2} cells cover the grid.
     */
    private static final int LATTICE_CELL_COUNT = BaselineGrid.SIZE / 2;

    /**
     * True for the one-block height window where the fluid classification reads the biome id: a
     * column at or above sea level is land and one under {@link #PREDICTED_FLUID_CEILING} is
     * water whatever the biome says, but in between water is decided by the biome alone (see
     * {@code resolveOverviewFluids}). Lattice copies must never span this window.
     */
    private static boolean biomeDecidesFluid(final int solidY) {
        return solidY >= PREDICTED_FLUID_CEILING && solidY < BaselineDeriver.WATER_LEVEL;
    }

    /** Whether all four corner anchors of one lattice cell are water biomes. */
    private static boolean allAnchorsWater(
        final int[] anchorBiomes, final int anchorsW, final int localKx, final int localKz
    ) {
        return BiomeTable.get(anchorBiomes[localKz * anchorsW + localKx + 1]).waterBiome()
            && BiomeTable.get(anchorBiomes[(localKz + 1) * anchorsW + localKx]).waterBiome()
            && BiomeTable.get(anchorBiomes[(localKz + 1) * anchorsW + localKx + 1]).waterBiome();
    }

    /** Whether all four pixel heights of one lattice cell sit under the fluid-decides band. */
    private static boolean pixelHeightsDeep(
        final int[] pixelHeights, final int heightStride,
        final int heightBaseRow, final int heightBaseCol, final int cz, final int cx
    ) {
        for (int j = 0; j < 2; j++) {
            for (int i = 0; i < 2; i++) {
                if (pixelHeights[(2 * cz + j - heightBaseRow) * heightStride
                    + 2 * cx + i - heightBaseCol] >= PREDICTED_FLUID_CEILING) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Solves the sub-biome lattice's corner anchors for the cell range {@code [cellX0,
     * cellX0+cellsW) x [cellZ0, cellZ0+cellsH)}: one height-aware {@code surfaceBiomes} query at
     * every second output pixel. Anchor heights come from the caller's dense pixel grid (the
     * lattice's far corner anchors sit one sub-sample past the grid; their height input falls
     * back to the margin pixel, and the query still samples the anchor's own block - only copy
     * decisions read those anchors, refined cells use per-sub heights, so the approximation
     * never reaches a solved value). Returns the anchors in {@code (cellsW+1) x (cellsH+1)}
     * layout, or null when the query fails. Reading only even anchor-pixel rows, this may run
     * while odd band pixels still hold reconstruction values.
     */
    private static int[] solveSubBiomeAnchors(
        final BaselineSampler sampler,
        final int lod,
        final int tileOriginX,
        final int tileOriginZ,
        final int sub,
        final int cellX0,
        final int cellZ0,
        final int cellsW,
        final int cellsH,
        final int[] pixelHeights,
        final int heightBaseCol,
        final int heightBaseRow,
        final int heightStride
    ) {
        final int blocksPerPixel = 1 << lod;
        final int subStride = blocksPerPixel / sub;
        final int step = 2 * sub;
        final int blockX0 = tileOriginX + P_MIN * blocksPerPixel;
        final int blockZ0 = tileOriginZ + P_MIN * blocksPerPixel;

        final int anchorsW = cellsW + 1;
        final int anchorsH = cellsH + 1;
        final int[] anchorHeights = new int[anchorsW * anchorsH];
        for (int kz = 0; kz < anchorsH; kz++) {
            final int row = Math.min(2 * (cellZ0 + kz), BaselineGrid.SIZE - 1) - heightBaseRow;
            for (int kx = 0; kx < anchorsW; kx++) {
                final int col = Math.min(2 * (cellX0 + kx), BaselineGrid.SIZE - 1) - heightBaseCol;
                anchorHeights[kz * anchorsW + kx] = pixelHeights[row * heightStride + col];
            }
        }
        final int[] anchorBiomes = new int[anchorsW * anchorsH];
        if (!sampler.surfaceBiomes(
            blockX0 + cellX0 * step * subStride,
            blockZ0 + cellZ0 * step * subStride,
            anchorsW, anchorsH, step * subStride, anchorHeights, anchorBiomes
        )) {
            return null;
        }
        return anchorBiomes;
    }

    /**
     * Runs the sub-biome cell loop over {@code [cellX0, cellX0+cellsW) x [cellZ0,
     * cellZ0+cellsH)} with pre-solved {@code anchorBiomes} (see {@link
     * * solveSubBiomeAnchors} for layout). A cell whose four corner biomes agree copies that
     * biome across its sub-samples, but only when none of the cell's own four pixel heights
     * falls in the biome-decides-fluid band: there a copied id could flip a water/land verdict,
     * everywhere else the terrain height alone has already decided it and the copy can only
     * refine biome colour. A deep-water cell - every corner a water biome, every pixel under
     * the fluid band - copies any corner: all water biomes render with the same tint and the
     * height alone classifies the column. Every other cell solves its sub-samples exactly, each
     * with its own pixel's height - value-for-value what the dense sweep returns. Because
     * cells, anchors and heights are all absolute to the tile, a window update that expands to
     * cell boundaries reproduces the full tile's sweep bit-for-bit. Returns the raw sweep for
     * the requested cell range (window layout, {@code cellsW*step} wide), or null on failure.
     */
    private static int[] runSubBiomeCells(
        final BaselineSampler sampler,
        final int lod,
        final int tileOriginX,
        final int tileOriginZ,
        final int sub,
        final int cellX0,
        final int cellZ0,
        final int cellsW,
        final int cellsH,
        final int[] pixelHeights,
        final int heightBaseCol,
        final int heightBaseRow,
        final int heightStride,
        final int[] anchorBiomes,
        final int anchorsW,
        final int anchorCellX0,
        final int anchorCellZ0
    ) {
        final int blocksPerPixel = 1 << lod;
        final int subStride = blocksPerPixel / sub;
        final int step = 2 * sub;
        final int blockX0 = tileOriginX + P_MIN * blocksPerPixel;
        final int blockZ0 = tileOriginZ + P_MIN * blocksPerPixel;

        final int rawW = cellsW * step;
        final int rawH = cellsH * step;
        final int[] raw = new int[rawW * rawH];
        final int[] cellHeights = new int[step * step];
        final int[] refined = new int[step * step];
        for (int cz = cellZ0; cz < cellZ0 + cellsH; cz++) {
            final int z0 = (cz - cellZ0) * step;
            for (int cx = cellX0; cx < cellX0 + cellsW; cx++) {
                final int x0 = (cx - cellX0) * step;
                final int localKx = cx - anchorCellX0;
                final int localKz = cz - anchorCellZ0;
                final int corner = anchorBiomes[localKz * anchorsW + localKx];
                boolean copyable;
                if (corner == anchorBiomes[localKz * anchorsW + localKx + 1]
                    && corner == anchorBiomes[(localKz + 1) * anchorsW + localKx]
                    && corner == anchorBiomes[(localKz + 1) * anchorsW + localKx + 1]) {
                    copyable = true;
                    for (int j = 0; j < 2 && copyable; j++) {
                        for (int i = 0; i < 2; i++) {
                            if (biomeDecidesFluid(pixelHeights[(2 * cz + j - heightBaseRow) * heightStride
                                + 2 * cx + i - heightBaseCol])) {
                                copyable = false;
                                break;
                            }
                        }
                    }
                } else {
                    /*
                     * Deep-water variant: every water biome renders with the same tint and every
                     * column this deep classifies as fluid from height alone, so a cell whose
                     * corners are all water biomes and whose pixels all sit under the
                     * fluid-decides band copies any corner regardless of which water biome it is.
                     */
                    copyable = BiomeTable.get(corner).waterBiome()
                        && allAnchorsWater(anchorBiomes, anchorsW, localKx, localKz)
                        && pixelHeightsDeep(pixelHeights, heightStride, heightBaseRow, heightBaseCol,
                            cz, cx);
                }
                if (copyable) {
                    for (int z = 0; z < step; z++) {
                        java.util.Arrays.fill(raw, (z0 + z) * rawW + x0, (z0 + z) * rawW + x0 + step, corner);
                    }
                    continue;
                }
                for (int z = 0; z < step; z++) {
                    for (int x = 0; x < step; x++) {
                        cellHeights[z * step + x] = pixelHeights[
                            ((cz * step + z) / sub - heightBaseRow) * heightStride
                                + (cx * step + x) / sub - heightBaseCol];
                    }
                }
                if (!sampler.surfaceBiomes(
                    blockX0 + cx * step * subStride,
                    blockZ0 + cz * step * subStride,
                    step, step, subStride, cellHeights, refined
                )) {
                    return null;
                }
                for (int z = 0; z < step; z++) {
                    System.arraycopy(refined, z * step, raw, (z0 + z) * rawW + x0, step);
                }
            }
        }
        return raw;
    }

    /**
     * Sparse-lattice sub-biome sweep: {@link #solveSubBiomeAnchors} followed by {@link
     * * runSubBiomeCells} over the requested cell range. Callers that already hold the band
     * resolution's biome anchors call the two stages separately.
     */
    private static int[] sampleSubBiomeLatticeRange(
        final BaselineSampler sampler,
        final int lod,
        final int tileOriginX,
        final int tileOriginZ,
        final int sub,
        final int cellX0,
        final int cellZ0,
        final int cellsW,
        final int cellsH,
        final int[] pixelHeights,
        final int heightBaseCol,
        final int heightBaseRow,
        final int heightStride
    ) {
        final int[] anchorBiomes = solveSubBiomeAnchors(
            sampler, lod, tileOriginX, tileOriginZ, sub,
            cellX0, cellZ0, cellsW, cellsH,
            pixelHeights, heightBaseCol, heightBaseRow, heightStride
        );
        if (anchorBiomes == null) {
            return null;
        }
        return runSubBiomeCells(
            sampler, lod, tileOriginX, tileOriginZ, sub,
            cellX0, cellZ0, cellsW, cellsH,
            pixelHeights, heightBaseCol, heightBaseRow, heightStride,
            anchorBiomes, cellsW + 1, cellX0, cellZ0
        );
    }

    /**
     * Scatters one raw sub-biome sweep into the grid. Every sub-sample lands in its pixel's slot,
     * and each pixel's own biome id takes its first sub-sample - the sweep samples that exact
     * position and height, so the value is the one a separate centre pass would have returned.
     */
    private static void scatterSubBiomes(final BaselineGrid grid, final int[] raw) {
        final int sub = grid.subPerAxis;
        final int width = BaselineGrid.SIZE * sub;
        for (int j = 0; j < width; j++) {
            for (int i = 0; i < width; i++) {
                final int pixelIndex = (j / sub) * BaselineGrid.SIZE + i / sub;
                grid.subBiomeId[grid.subIndex(pixelIndex, i % sub, j % sub)] = raw[j * width + i];
                if (i % sub == 0 && j % sub == 0) {
                    grid.biomeId[pixelIndex] = raw[j * width + i];
                }
            }
        }
    }

    /**
     * Fills the grid's biome sub-samples after a per-pixel pass. Reuses the same height-aware
     * {@code surfaceBiomes} entry point, with each sub-sample handed its pixel's centre height,
     * so the two agree on how a column's biome is chosen and only the sampled position differs.
     *
     * <p>Best-effort fallback path: a sampler that cannot resolve surface biomes (the interface's
     * default, used by tests and by non-native samplers) leaves every sub-sample equal to its
     * pixel, which is exactly the pre-supersampling behaviour.
     */
    private static void sampleSubBiomes(
        final BaselineSampler sampler,
        final int lod,
        final int tileOriginX,
        final int tileOriginZ,
        final BaselineGrid grid
    ) {
        final int[] raw = sampleSubBiomeRaw(sampler, lod, tileOriginX, tileOriginZ, grid);
        if (raw != null) {
            scatterSubBiomes(grid, raw);
            return;
        }
        if (!grid.supersampled()) {
            return;
        }
        final int sub = grid.subPerAxis;
        final int width = BaselineGrid.SIZE * sub;
        for (int j = 0; j < width; j++) {
            for (int i = 0; i < width; i++) {
                final int pixelIndex = (j / sub) * BaselineGrid.SIZE + i / sub;
                grid.subBiomeId[grid.subIndex(pixelIndex, i % sub, j % sub)]
                    = grid.biomeId[pixelIndex];
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
     * rather than Overworld surface-biome queries, and the pre-1.18 biome-identity view reuses it
     * because its layer-stack queries need no heights to sub-sample.
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
