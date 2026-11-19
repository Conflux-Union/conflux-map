package cn.net.rms.confluxmap.core.predict;

/**
 * Sub-biome lattice solver shared by the Overworld stages and the window sampler: corner
 * anchor solves ({@link #solveSubBiomeAnchors}), the copy-or-refine cell sweep ({@link
 * #runSubBiomeCells}), and the dense raw sweep fallback ({@link #sampleSubBiomeRaw}).
 */
final class SubBiomeSampling {
    private SubBiomeSampling() {
    }
    private static final int P_MIN = -BaselineGrid.MARGIN;

    /**
     * Runs the raw sub-biome sweep: one height-aware {@code surfaceBiomes} query at
     * {@code sub}-per-pixel resolution, each sub-sample handed its pixel's centre height, exactly
     * as the per-pixel pass would resolve that column. Returns null when the grid is not
     * supersampled or the sampler cannot serve the query.
     *
     * <p>The sparse-lattice path ({@link #sampleSubBiomeLatticeRange}) is chosen by the callers
     * before falling back here; this method itself always runs the dense sweep and exists as that
     * fallback and as the value-level contract the lattice path must reproduce outside its copied
     * cells. A supersampled grid from a re-enabled sub-sampling config would use it for the 2x2
     * sweep.
     */
    static int[] sampleSubBiomeRaw(
        final BaselineSampler sampler,
        final int lod,
        final int tileOriginX,
        final int tileOriginZ,
        final BaselineGrid grid
    ) {
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
     * True for the one-block height window where the fluid classification reads the biome id: a
     * column at or above sea level is land and one under {@link #PREDICTED_FLUID_CEILING} is
     * water whatever the biome says, but in between water is decided by the biome alone (see
     * {@code resolveOverviewFluids}). Lattice copies must never span this window.
     */
    private static boolean biomeDecidesFluid(final int solidY) {
        return solidY >= OverworldStages.PREDICTED_FLUID_CEILING && solidY < BaselineDeriver.WATER_LEVEL;
    }

    /** Whether all four corner anchors of one lattice cell are water biomes. */
    private static int waterFreezeClass(final int biomeId) {
        if (biomeId == 10 || biomeId == 11) {
            return 1; // frozen ocean / frozen river (surface ice)
        }
        if (biomeId == 50) {
            return 2; // deep frozen ocean
        }
        return 0; // standard non-frozen water
    }

    private static boolean allAnchorsWater(
        final int[] anchorBiomes, final int anchorsW, final int localKx, final int localKz
    ) {
        final int b00 = anchorBiomes[localKz * anchorsW + localKx];
        final int b10 = anchorBiomes[localKz * anchorsW + localKx + 1];
        final int b01 = anchorBiomes[(localKz + 1) * anchorsW + localKx];
        final int b11 = anchorBiomes[(localKz + 1) * anchorsW + localKx + 1];

        if (!BiomeTable.get(b00).waterBiome()
            || !BiomeTable.get(b10).waterBiome()
            || !BiomeTable.get(b01).waterBiome()
            || !BiomeTable.get(b11).waterBiome()) {
            return false;
        }

        // Must preserve identical freeze semantics across all four corners
        final int fClass = waterFreezeClass(b00);
        return fClass == waterFreezeClass(b10)
            && fClass == waterFreezeClass(b01)
            && fClass == waterFreezeClass(b11);
    }

    /** Whether all four pixel heights of one lattice cell sit under the fluid-decides band. */
    private static boolean pixelHeightsDeep(
        final int[] pixelHeights, final int heightStride,
        final int heightBaseRow, final int heightBaseCol, final int cz, final int cx
    ) {
        for (int j = 0; j < 2; j++) {
            for (int i = 0; i < 2; i++) {
                if (pixelHeights[(2 * cz + j - heightBaseRow) * heightStride
                    + 2 * cx + i - heightBaseCol] >= OverworldStages.PREDICTED_FLUID_CEILING) {
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
    static int[] solveSubBiomeAnchors(
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
        final OverworldStages.StageQuery plan = planSubBiomeAnchors(
            lod, tileOriginX, tileOriginZ, sub, cellX0, cellZ0, cellsW, cellsH,
            pixelHeights, heightBaseCol, heightBaseRow, heightStride
        );
        final int[] anchorBiomes = new int[plan.w * plan.h];
        if (!sampler.surfaceBiomes(
            plan.blockX, plan.blockZ, plan.w, plan.h, plan.stride, plan.terrainY, anchorBiomes
        )) {
            return null;
        }
        return anchorBiomes;
    }

    /** Anchor-sweep query geometry plus the gathered anchor terrain plane; the shared source for
     * the per-tile solve and a wave's batched ANCHOR_BIOMES dispatch. */
    static OverworldStages.StageQuery planSubBiomeAnchors(
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
        return new OverworldStages.StageQuery(
            blockX0 + cellX0 * step * subStride,
            blockZ0 + cellZ0 * step * subStride,
            anchorsW, anchorsH, step * subStride, anchorHeights
        );
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
     * cell boundaries reproduces the full tile's sweep bit-for-bit. Refining cells are collected
     * and dispatched in bounded chunks (per-cell below the sparse threshold), so the first
     * failing chunk repairs cell by cell exactly where the dense sweep would have failed.
     * Returns the raw sweep for the requested cell range (window layout, {@code cellsW*step}
     * wide), or null on failure.
     */
    static int[] runSubBiomeCells(
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
        final SparseCellRequests requests = new SparseCellRequests(step * step);
        final int[] cellHeights = new int[step * step];
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
                    copyable = allAnchorsWater(anchorBiomes, anchorsW, localKx, localKz)
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
                requests.add(z0 * rawW + x0,
                    blockX0 + cx * step * subStride,
                    blockZ0 + cz * step * subStride,
                    cellHeights);
            }
        }
        return resolveCellRequests(sampler, requests, step, subStride, raw, rawW);
    }

    /**
     * Executes collected cell requests with the same threshold/chunk/repair structure as the
     * band: sub-threshold collections stay per cell, larger ones dispatch bounded sparse chunks
     * whose failure repairs cell by cell in collection order, and the first failed repair cell
     * returns null exactly where the per-cell loop would have.
     */
    private static int[] resolveCellRequests(
        final BaselineSampler sampler,
        final SparseCellRequests requests,
        final int edge,
        final int stride,
        final int[] raw,
        final int rawW
    ) {
        final int points = edge * edge;
        if (requests.count == 0) {
            return raw;
        }
        final int[] refined = new int[points];
        if (requests.count < OverworldStages.SPARSE_MIN_POINTS) {
            final int[] heights = new int[points];
            for (int k = 0; k < requests.count; k++) {
                requests.copyHeights(k, heights);
                if (!sampler.surfaceBiomes(
                    requests.cellX(k), requests.cellZ(k), edge, edge, stride, heights, refined
                )) {
                    return null;
                }
                scatterCellRows(raw, rawW, requests.destRawOffset(k), refined, 0, edge);
            }
            return raw;
        }
        final int chunkCells = Math.min(requests.count, OverworldStages.SPARSE_CHUNK_CELLS);
        final int[] chunkX = new int[chunkCells];
        final int[] chunkZ = new int[chunkCells];
        final int[] chunkHeights = new int[chunkCells * points];
        final int[] chunkBiomes = new int[chunkCells * points];
        final int[] heights = new int[points];
        for (int start = 0; start < requests.count; start += OverworldStages.SPARSE_CHUNK_CELLS) {
            final int n = Math.min(OverworldStages.SPARSE_CHUNK_CELLS, requests.count - start);
            for (int k = 0; k < n; k++) {
                chunkX[k] = requests.cellX(start + k);
                chunkZ[k] = requests.cellZ(start + k);
            }
            requests.copyHeightsRange(start, n, chunkHeights);
            if (!sampler.surfaceBiomesSparse(chunkX, chunkZ, edge, stride, chunkHeights, n, chunkBiomes)) {
                for (int k = 0; k < n; k++) {
                    System.arraycopy(chunkHeights, k * points, heights, 0, points);
                    if (!sampler.surfaceBiomes(chunkX[k], chunkZ[k], edge, edge, stride, heights, refined)) {
                        return null;
                    }
                    scatterCellRows(raw, rawW, requests.destRawOffset(start + k), refined, 0, edge);
                }
                continue;
            }
            for (int k = 0; k < n; k++) {
                scatterCellRows(raw, rawW, requests.destRawOffset(start + k), chunkBiomes, k * points, edge);
            }
        }
        return raw;
    }

    /** Copies one cell's {@code edge x edge} points into the raw sweep at its collected offset. */
    private static void scatterCellRows(
        final int[] raw,
        final int rawW,
        final int destRawOffset,
        final int[] src,
        final int srcPos,
        final int edge
    ) {
        for (int z = 0; z < edge; z++) {
            System.arraycopy(src, srcPos + z * edge, raw, destRawOffset + z * rawW, edge);
        }
    }

    /**
     * Growable (destRawOffset, cellX, cellZ) triplets plus each cell's packed {@code edge*edge}
     * height inputs, collected for one cells sweep. Triplets and heights share one capacity so
     * both grow in lockstep with the cell count.
     */
    private static final class SparseCellRequests {
        private final int pointsPerCell;
        private int[] data = new int[3 * 64];
        private int[] heights;
        private int count;

        SparseCellRequests(final int pointsPerCell) {
            this.pointsPerCell = pointsPerCell;
            this.heights = new int[pointsPerCell * 64];
        }

        void add(final int destRawOffset, final int cellX, final int cellZ, final int[] cellHeights) {
            if (data.length < (count + 1) * 3) {
                data = java.util.Arrays.copyOf(data, data.length * 2);
                heights = java.util.Arrays.copyOf(heights, heights.length * 2);
            }
            data[count * 3] = destRawOffset;
            data[count * 3 + 1] = cellX;
            data[count * 3 + 2] = cellZ;
            System.arraycopy(cellHeights, 0, heights, count * pointsPerCell, pointsPerCell);
            count++;
        }

        int destRawOffset(final int i) {
            return data[i * 3];
        }

        int cellX(final int i) {
            return data[i * 3 + 1];
        }

        int cellZ(final int i) {
            return data[i * 3 + 2];
        }

        void copyHeights(final int i, final int[] dst) {
            System.arraycopy(heights, i * pointsPerCell, dst, 0, pointsPerCell);
        }

        void copyHeightsRange(final int first, final int n, final int[] dst) {
            System.arraycopy(heights, first * pointsPerCell, dst, 0, n * pointsPerCell);
        }
    }

    /**
     * Sparse-lattice sub-biome sweep: {@link #solveSubBiomeAnchors} followed by {@link
     * * runSubBiomeCells} over the requested cell range. Callers that already hold the band
     * resolution's biome anchors call the two stages separately.
     */
    static int[] sampleSubBiomeLatticeRange(
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
    static void scatterSubBiomes(final BaselineGrid grid, final int[] raw) {
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
    static void sampleSubBiomes(
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

}
