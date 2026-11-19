package cn.net.rms.confluxmap.core.predict;

/**
 * The Overworld staged sampling pipeline: the six {@link OverworldStage} sweeps a tile
 * runs, the overview anchor lattice and waterline band they solve through, the sparse
 * batch transport they dispatch on, and the fluid classification that composes the
 * surface planes. Tiles enter through {@code LodSampling.sample}; wave batching enters
 * through the package-private query planners ({@link #planLatticeQuery}, {@link
 * #planAnchorBiomeQuery}).
 */
final class OverworldStages {
    private OverworldStages() {
    }

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
    static final int PREDICTED_FLUID_CEILING = BaselineDeriver.WATER_LEVEL - 1;

    /**
     * Lowest LOD whose overview heights may be solved on a sparse anchor lattice and bilinearly
     * reconstructed, for samplers opting in via {@link
     * BaselineSampler#interpolatableOverviewHeights()}. Below it each output position keeps its
     * own exact overview solve: measured at LOD0-1 nearly every land pixel sits in the
     * interpolation-excluded mid-band, so the lattice degenerates into one fallback query per
     * pixel (slower than the direct pass) and still smears the near-zoom waterline badly enough
     * to trip the ocean-to-land gate (0.53% measured).
     */
    static final int OVERVIEW_ANCHOR_MIN_LOD = 2;

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

    /**
     * One overworld sampling stage, in execution order. {@link #runOverworldStage} runs one
     * stage for one tile, so a driver may interleave other work between stages but must keep
     * this order. Lattice LODs (2-4, opting-in samplers) run the coarse stages in dependency
     * order: anchor heights (H_LATTICE), then the biome anchor sweep (ANCHOR_BIOMES; it reads
     * only even anchor-pixel heights, so it may run while band pixels still hold reconstruction
     * values), then the waterline band resolution (BAND) - which consults the biome anchors to
     * skip quads that are provably waterline-irrelevant - and finally the cell copy/refine loop
     * (CELLS), whose guards read the now-final band pixel heights. A failed anchor query leaves
     * the band ungated (every crossing pixel solves exactly) and the biome stage on its own
     * fallbacks. EXACT_COLUMNS drops out on its own from the LOD where the exact residual pass
     * no longer runs.
     */
    enum OverworldStage {
        /** Solves and reconstructs the overview anchor lattice, or fills dense overview heights. */
        H_LATTICE,
        /** Solves the sub-biome corner anchors over the lattice cells (lattice path only). */
        ANCHOR_BIOMES,
        /** Re-solves waterline-band pixels exactly, gated by the biome anchors (lattice path only). */
        BAND,
        /** Applies the sparse exact residual correction and fills the anchor fluid confidence. */
        EXACT_COLUMNS,
        /** Fills biomes via the cell copy/refine sweep or the per-pixel fallbacks. */
        CELLS,
        /** Classifies each column as water or land into the grid's surface planes. */
        COMPOSE
    }

    static final int OVERWORLD_STAGE_COUNT = OverworldStage.values().length;

    /**
     * Per-tile mutable state carried across the overworld stages; one instance per sampling
     * run, or one per wave member when stages are driven tile by tile. Fields are populated and
     * consumed only by the stage functions behind {@link #runOverworldStage}.
     */
    static final class OverworldStageState {
        final int lod;
        final int tileOriginX;
        final int tileOriginZ;
        final int blocksPerPixel;
        final BaselineGrid grid;
        /** Anchor aquifer confidence per output pixel; filled by EXACT_COLUMNS, read by COMPOSE. */
        final int[] fluidConfidence;
        /** Overview anchor lattice; null when the tile takes the dense overview path. */
        OverviewLattice lattice;
        /** Sub-biome corner anchors; null unless the lattice path produced them. */
        int[] biomeAnchors;

        OverworldStageState(
            final int lod,
            final int tileOriginX,
            final int tileOriginZ,
            final BaselineGrid grid
        ) {
            this.lod = lod;
            this.tileOriginX = tileOriginX;
            this.tileOriginZ = tileOriginZ;
            this.blocksPerPixel = 1 << lod;
            this.grid = grid;
            this.fluidConfidence = new int[grid.terrainY.length];
        }
    }

    /**
     * Runs one {@link OverworldStage} for one tile against the sampler. Returns false when the
     * tile failed and must be dropped; the remaining stages must then not run.
     */
    static boolean runOverworldStage(
        final OverworldStage stage,
        final OverworldStageState state,
        final BaselineSampler sampler
    ) {
        return switch (stage) {
            case H_LATTICE -> runLatticeStage(state, sampler);
            case ANCHOR_BIOMES -> runAnchorBiomesStage(state, sampler);
            case BAND -> runBandStage(state, sampler);
            case EXACT_COLUMNS -> runExactColumnsStage(state, sampler);
            case CELLS -> runCellsStage(state, sampler);
            case COMPOSE -> runComposeStage(state, sampler);
        };
    }

    private static boolean runLatticeStage(
        final OverworldStageState state,
        final BaselineSampler sampler
    ) {
        final int lod = state.lod;
        final int tileOriginX = state.tileOriginX;
        final int tileOriginZ = state.tileOriginZ;
        final int blocksPerPixel = state.blocksPerPixel;
        if (lod >= OVERVIEW_ANCHOR_MIN_LOD && sampler.interpolatableOverviewHeights()) {
            state.lattice = solveOverviewLattice(
                sampler, lod, tileOriginX, tileOriginZ,
                P_MIN, P_MIN, BaselineGrid.SIZE, BaselineGrid.SIZE
            );
            if (state.lattice != null) {
                reconstructOverviewLattice(
                    P_MIN, P_MIN, BaselineGrid.SIZE, BaselineGrid.SIZE,
                    state.lattice, state.grid.terrainY
                );
            }
        }
        if (state.lattice == null
            && !sampler.overviewHeights(
                tileOriginX + P_MIN * blocksPerPixel,
                tileOriginZ + P_MIN * blocksPerPixel,
                BaselineGrid.SIZE, BaselineGrid.SIZE, blocksPerPixel, state.grid.terrainY
            )) {
            return false;
        }
        return true;
    }

    private static boolean runAnchorBiomesStage(
        final OverworldStageState state,
        final BaselineSampler sampler
    ) {
        if (state.lattice != null) {
            state.biomeAnchors = SubBiomeSampling.solveSubBiomeAnchors(
                sampler, state.lod, state.tileOriginX, state.tileOriginZ, state.grid.subPerAxis,
                0, 0, LATTICE_CELL_COUNT, LATTICE_CELL_COUNT,
                state.grid.terrainY, 0, 0, BaselineGrid.SIZE
            );
        }
        return true;
    }

    private static boolean runBandStage(
        final OverworldStageState state,
        final BaselineSampler sampler
    ) {
        if (state.lattice != null && !resolveOverviewBand(
            sampler, state.lod, state.tileOriginX, state.tileOriginZ,
            P_MIN, P_MIN, BaselineGrid.SIZE, BaselineGrid.SIZE,
            state.lattice, state.grid.terrainY, state.biomeAnchors,
            LATTICE_CELL_COUNT + 1, LATTICE_CELL_COUNT + 1, 0, 0
        )) {
            return false;
        }
        return true;
    }

    private static boolean runExactColumnsStage(
        final OverworldStageState state,
        final BaselineSampler sampler
    ) {
        if (state.lod <= MAX_EXACT_RESIDUAL_LOD && !applyExactResiduals(
            sampler, state.lod, state.tileOriginX, state.tileOriginZ, state.blocksPerPixel,
            state.grid, state.fluidConfidence
        )) {
            return false;
        }
        return true;
    }

    private static boolean runCellsStage(
        final OverworldStageState state,
        final BaselineSampler sampler
    ) {
        final BaselineGrid grid = state.grid;
        final int lod = state.lod;
        final int tileOriginX = state.tileOriginX;
        final int tileOriginZ = state.tileOriginZ;
        // The lattice cell sweep answers the plain per-pixel table at LOD2-4 directly, so no
        // separate centre pass runs: cell copies and exact samples reproduce what a dense
        // per-pixel query returns. A supersampled grid would scatter the sweep into the dormant
        // sub-sample planes instead. Samplers without the lattice keep the old order.
        final int[] subRaw = state.biomeAnchors != null
            ? SubBiomeSampling.runSubBiomeCells(
                sampler, lod, tileOriginX, tileOriginZ, grid.subPerAxis,
                0, 0, LATTICE_CELL_COUNT, LATTICE_CELL_COUNT,
                grid.terrainY, 0, 0, BaselineGrid.SIZE,
                state.biomeAnchors, LATTICE_CELL_COUNT + 1, 0, 0
            )
            : SubBiomeSampling.sampleSubBiomeRaw(sampler, lod, tileOriginX, tileOriginZ, grid);
        if (subRaw != null) {
            if (grid.supersampled()) {
                SubBiomeSampling.scatterSubBiomes(grid, subRaw);
            } else {
                System.arraycopy(subRaw, 0, grid.biomeId, 0, subRaw.length);
            }
        } else {
            if (!sampleSurfaceBiomes(sampler, lod, tileOriginX, tileOriginZ, grid)
                && !LodSampling.sampleBiomes(sampler, lod, tileOriginX, tileOriginZ, grid)) {
                return false;
            }
            SubBiomeSampling.sampleSubBiomes(sampler, lod, tileOriginX, tileOriginZ, grid);
        }
        return true;
    }

    private static boolean runComposeStage(
        final OverworldStageState state,
        final BaselineSampler sampler
    ) {
        resolveOverviewFluids(state.grid, state.lod, state.fluidConfidence);
        return true;
    }

    /** Drives every overworld stage in order for one tile. */
    static boolean sampleOverworld(
        final BaselineSampler sampler,
        final int lod,
        final int tileOriginX,
        final int tileOriginZ,
        final BaselineGrid grid,
        final long[] stageWallNanos
    ) {
        final OverworldStageState state = new OverworldStageState(lod, tileOriginX, tileOriginZ, grid);
        return runTimedStage(OverworldStage.H_LATTICE, state, sampler, stageWallNanos)
            && runTimedStage(OverworldStage.ANCHOR_BIOMES, state, sampler, stageWallNanos)
            && runTimedStage(OverworldStage.BAND, state, sampler, stageWallNanos)
            && runTimedStage(OverworldStage.EXACT_COLUMNS, state, sampler, stageWallNanos)
            && runTimedStage(OverworldStage.CELLS, state, sampler, stageWallNanos)
            && runTimedStage(OverworldStage.COMPOSE, state, sampler, stageWallNanos);
    }

    private static boolean runTimedStage(
        final OverworldStage stage,
        final OverworldStageState state,
        final BaselineSampler sampler,
        final long[] stageWallNanos
    ) {
        final long start = stageWallNanos != null ? System.nanoTime() : 0L;
        final boolean ok = runOverworldStage(stage, state, sampler);
        if (stageWallNanos != null) {
            stageWallNanos[stage.ordinal()] += System.nanoTime() - start;
        }
        return ok;
    }

    /** Overview anchor lattice over even tile-local pixels for one LOD2-4 window. */
    static final class OverviewLattice {
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
    static OverviewLattice solveOverviewLattice(
        final BaselineSampler sampler,
        final int lod,
        final int tileOriginX,
        final int tileOriginZ,
        final int minPixelX,
        final int minPixelZ,
        final int width,
        final int height
    ) {
        final OverviewLatticePlan plan = planOverviewLattice(
            lod, tileOriginX, tileOriginZ, minPixelX, minPixelZ, width, height
        );
        final int[] anchors = new int[plan.anchorW * plan.anchorH];
        if (!sampler.overviewHeightsCoarse(
            plan.blockX, plan.blockZ, plan.anchorW, plan.anchorH, plan.stride, anchors
        )) {
            return null;
        }
        return new OverviewLattice(anchors, plan.anchorMinKx, plan.anchorMinKz, plan.anchorW, plan.anchorH);
    }

    /** Anchor-lattice query geometry for one pixel window; the single source both the per-tile
     * solve and a wave's batched H_LATTICE dispatch build their queries from. */
    private static final class OverviewLatticePlan {
        final int blockX;
        final int blockZ;
        final int anchorW;
        final int anchorH;
        final int stride;
        final int anchorMinKx;
        final int anchorMinKz;

        OverviewLatticePlan(
            final int blockX, final int blockZ, final int anchorW, final int anchorH, final int stride,
            final int anchorMinKx, final int anchorMinKz
        ) {
            this.blockX = blockX;
            this.blockZ = blockZ;
            this.anchorW = anchorW;
            this.anchorH = anchorH;
            this.stride = stride;
            this.anchorMinKx = anchorMinKx;
            this.anchorMinKz = anchorMinKz;
        }
    }

    private static OverviewLatticePlan planOverviewLattice(
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
        return new OverviewLatticePlan(
            tileOriginX + 2 * anchorMinKx * blocksPerPixel,
            tileOriginZ + 2 * anchorMinKz * blocksPerPixel,
            anchorW, anchorH, 2 * blocksPerPixel, anchorMinKx, anchorMinKz
        );
    }

    /** One wave member's native query for a batchable stage, in the batch entries' flat contract. */
    static final class StageQuery {
        final int blockX;
        final int blockZ;
        final int w;
        final int h;
        final int stride;
        /** Gathered terrain plane ({@code w*h}) feeding ANCHOR_BIOMES; null for H_LATTICE. */
        final int[] terrainY;

        StageQuery(
            final int blockX, final int blockZ, final int w, final int h, final int stride, final int[] terrainY
        ) {
            this.blockX = blockX;
            this.blockZ = blockZ;
            this.w = w;
            this.h = h;
            this.stride = stride;
            this.terrainY = terrainY;
        }
    }

    /** Allocates the grid the staged overworld path composes into, supersampling identical to {@link LodSampling#sample}. */
    static BaselineGrid newOverworldGrid(final int lod, final int tileOriginX, final int tileOriginZ) {
        return new BaselineGrid(lod, tileOriginX, tileOriginZ, LodSampling.BIOME_SUB_PER_AXIS[lod]);
    }

    /**
     * Plans member {@code state}'s H_LATTICE native query: the anchor-lattice geometry on the
     * interpolation path, the dense overview rectangle otherwise (the same choice
     * {@link #runLatticeStage} makes per tile).
     */
    static StageQuery planLatticeQuery(final OverworldStageState state, final BaselineSampler sampler) {
        if (state.lod >= OVERVIEW_ANCHOR_MIN_LOD && sampler.interpolatableOverviewHeights()) {
            final OverviewLatticePlan plan = planOverviewLattice(
                state.lod, state.tileOriginX, state.tileOriginZ,
                P_MIN, P_MIN, BaselineGrid.SIZE, BaselineGrid.SIZE
            );
            return new StageQuery(plan.blockX, plan.blockZ, plan.anchorW, plan.anchorH, plan.stride, null);
        }
        return new StageQuery(
            state.tileOriginX + P_MIN * state.blocksPerPixel,
            state.tileOriginZ + P_MIN * state.blocksPerPixel,
            BaselineGrid.SIZE, BaselineGrid.SIZE, state.blocksPerPixel, null
        );
    }

    /** Applies a successful batched H_LATTICE result: wrap the anchors and reconstruct, or copy the dense plane. */
    static void applyLatticeQuery(final OverworldStageState state, final BaselineSampler sampler, final int[] result) {
        if (state.lod >= OVERVIEW_ANCHOR_MIN_LOD && sampler.interpolatableOverviewHeights()) {
            final OverviewLatticePlan plan = planOverviewLattice(
                state.lod, state.tileOriginX, state.tileOriginZ,
                P_MIN, P_MIN, BaselineGrid.SIZE, BaselineGrid.SIZE
            );
            state.lattice = new OverviewLattice(
                result, plan.anchorMinKx, plan.anchorMinKz, plan.anchorW, plan.anchorH
            );
            reconstructOverviewLattice(
                P_MIN, P_MIN, BaselineGrid.SIZE, BaselineGrid.SIZE, state.lattice, state.grid.terrainY
            );
        } else {
            System.arraycopy(result, 0, state.grid.terrainY, 0, state.grid.terrainY.length);
        }
    }

    /**
     * Plans member {@code state}'s ANCHOR_BIOMES native query, gathering the anchor terrain
     * plane the biome solve consumes; null when the tile took no lattice and the stage queries
     * nothing.
     */
    static StageQuery planAnchorBiomeQuery(final OverworldStageState state) {
        if (state.lattice == null) {
            return null;
        }
        return SubBiomeSampling.planSubBiomeAnchors(
            state.lod, state.tileOriginX, state.tileOriginZ, state.grid.subPerAxis,
            0, 0, LATTICE_CELL_COUNT, LATTICE_CELL_COUNT,
            state.grid.terrainY, 0, 0, BaselineGrid.SIZE
        );
    }

    /** Applies a successful batched ANCHOR_BIOMES result; layout identical to {@link SubBiomeSampling#solveSubBiomeAnchors}. */
    static void applyAnchorBiomeQuery(final OverworldStageState state, final int[] result) {
        state.biomeAnchors = result;
    }

    /**
     * Fills {@code out} (dense {@code width}x{@code height} window layout) with the bilinear
     * reconstruction of {@link #solveOverviewLattice}'s anchors. Every odd pixel gets its
     * interpolated value, including waterline-band pixels - {@link #resolveOverviewBand} replaces
     * those afterwards once the biome anchors are known.
     */
    static void reconstructOverviewLattice(
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
                    // Read the right-hand anchor only when it exists: at the last lattice column the
                    // unconditional read wrapped into the next row (dead value when oddX == 0, but a
                    // stray read on the last row).
                    final boolean hasRight = anchorX + 1 < lattice.w;
                    final int h10 = hasRight ? lattice.heights[anchorZ * lattice.w + anchorX + 1] : h00;
                    final int top = oddX != 0 ? lerp(h00, h10, 1, 2) : h00;
                    if (oddZ != 0) {
                        final int h01 = lattice.heights[(anchorZ + 1) * lattice.w + anchorX];
                        final int bottom = oddX != 0 && hasRight
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
    static boolean resolveOverviewBand(
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
        final SparseRequests requests = new SparseRequests();
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
                int lowest = h00;
                int highest = h00;
                /*
                 * Bounds check, not a parity check. The envelope must keep the right-hand anchor
                 * for every pixel that has one - at the last lattice column anchorX + 1 wraps into
                 * the next row (and past the array on the last row), so only that column skips it.
                 * Keying this on `oddX != 0` instead would silently drop the right anchor for every
                 * even pixel, narrowing the band and skipping exact waterline solves it used to do.
                 */
                final boolean hasRight = anchorX + 1 < lattice.w;
                if (hasRight) {
                    final int h10 = lattice.heights[anchorZ * lattice.w + anchorX + 1];
                    lowest = Math.min(lowest, h10);
                    highest = Math.max(highest, h10);
                }
                if (oddZ != 0) {
                    final int h01 = lattice.heights[(anchorZ + 1) * lattice.w + anchorX];
                    lowest = Math.min(lowest, h01);
                    highest = Math.max(highest, h01);
                    if (hasRight) {
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
                requests.add(j * width + i,
                    tileOriginX + pixelX * blocksPerPixel,
                    tileOriginZ + pixelZ * blocksPerPixel);
            }
        }
        return resolveBandRequests(sampler, requests, out);
    }

    /**
     * Sparse-transport thresholds for the collected band/cells requests: below {@code
     * SPARSE_MIN_POINTS} the per-point entries stay cheaper than packing one sparse call, and
     * larger collections dispatch in bounded chunks so a coastline tile's measured 0..~2000
     * waterline points never grow an unbounded native batch.
     */
    static final int SPARSE_MIN_POINTS = 16;
    /**
     * Sparse chunk caps. The per-dispatch fixed cost (~0.6ms) amortizes over the chunk, so
     * larger chunks cut the serialized queue length until per-point compute dominates; 32K
     * points / 8K cells is the measured sweet spot on the RTX 4060 (wall still falls from
     * 4096 to 16K chunks but is flat past 32K).
     */
    static final int SPARSE_CHUNK_POINTS = 32768;
    /** Cells per packed sparse call; each cell carries {@code edge*edge} points. */
    static final int SPARSE_CHUNK_CELLS = 8192;

    /**
     * Executes a collected band: an empty collection resolves without touching the sampler, a
     * sub-threshold one stays on the per-point entries, and larger ones dispatch bounded sparse
     * chunks whose failure repairs through the per-point entry in collection order - so the
     * first failing request aborts exactly where the per-point loop would have.
     */
    private static boolean resolveBandRequests(
        final BaselineSampler sampler,
        final SparseRequests requests,
        final int[] out
    ) {
        if (requests.count == 0) {
            return true;
        }
        final int[] exact = new int[1];
        if (requests.count < SPARSE_MIN_POINTS) {
            for (int k = 0; k < requests.count; k++) {
                if (!sampler.overviewHeights(requests.blockX(k), requests.blockZ(k), 1, 1, 4, exact)) {
                    return false;
                }
                out[requests.destIndex(k)] = exact[0];
            }
            return true;
        }
        final int chunkPoints = Math.min(requests.count, SPARSE_CHUNK_POINTS);
        final int[] chunkX = new int[chunkPoints];
        final int[] chunkZ = new int[chunkPoints];
        final int[] chunkY = new int[chunkPoints];
        for (int start = 0; start < requests.count; start += SPARSE_CHUNK_POINTS) {
            final int n = Math.min(SPARSE_CHUNK_POINTS, requests.count - start);
            for (int k = 0; k < n; k++) {
                chunkX[k] = requests.blockX(start + k);
                chunkZ[k] = requests.blockZ(start + k);
            }
            if (!sampler.overviewHeightsSparse(chunkX, chunkZ, n, chunkY)) {
                for (int k = 0; k < n; k++) {
                    if (!sampler.overviewHeights(chunkX[k], chunkZ[k], 1, 1, 4, exact)) {
                        return false;
                    }
                    out[requests.destIndex(start + k)] = exact[0];
                }
                continue;
            }
            for (int k = 0; k < n; k++) {
                out[requests.destIndex(start + k)] = chunkY[k];
            }
        }
        return true;
    }

    /**
     * Growable (destIndex, blockX, blockZ) request triplets collected for one refinement sweep.
     * Collection order is execution order, so a chunked dispatch and its repair walk the same
     * requests the pre-split per-point loop issued.
     */
    private static final class SparseRequests {
        private int[] data = new int[3 * 64];
        private int count;

        void add(final int destIndex, final int blockX, final int blockZ) {
            if (data.length < (count + 1) * 3) {
                data = java.util.Arrays.copyOf(data, data.length * 2);
            }
            data[count * 3] = destIndex;
            data[count * 3 + 1] = blockX;
            data[count * 3 + 2] = blockZ;
            count++;
        }

        int destIndex(final int i) {
            return data[i * 3];
        }

        int blockX(final int i) {
            return data[i * 3 + 1];
        }

        int blockZ(final int i) {
            return data[i * 3 + 2];
        }
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
     * Lattice cells per axis: one cell spans two output pixels ({@code step = 2 * sub}
     * sub-samples) and the cells partition the sweep, so {@code SIZE / 2} cells cover the grid.
     */
    static final int LATTICE_CELL_COUNT = BaselineGrid.SIZE / 2;

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

    private static int lerp(final int from, final int to, final int offset, final int extent) {
        return from + Math.floorDiv((to - from) * offset, extent);
    }

}
