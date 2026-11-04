package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.store.ColumnStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Seed-backed biome candidate sampling shared by the fullscreen search UI. */
public final class BiomeSearchService {
    private static final int MAX_GRID_SIDE = 513;

    /**
     * Side of the per-candidate surface refinement grid. One scale-16 biome cell spans 16
     * blocks, so a 9x9 grid at a 4-block stride covers the coarse cell plus one neighbour in
     * every direction and pins the candidate to 4-block (quart) accuracy.
     */
    private static final int REFINE_SIDE = 9;
    private static final int REFINE_STRIDE = 4;
    private static final int REFINE_HALF_SPAN = 16;

    /** Outer radius of the innermost ring; nearby matches earn the densest sampling grid. */
    private static final int FIRST_RING_RADIUS = 5_000;
    /** Ring growth factor; keeps the ring count logarithmic in the requested radius. */
    private static final int RING_GROWTH = 3;

    private BiomeSearchService() {
    }

    public static List<BiomeCandidateSearch.Candidate> search(
        final ColumnStore store,
        final String targetBiomeId,
        final OptionalInt predictedBiomeId,
        final BaselineSampler sampler,
        final int centerX,
        final int centerZ,
        final int radius,
        final int limit
    ) {
        return search(
            store, targetBiomeId, predictedBiomeId, sampler,
            centerX, centerZ, radius, limit, SearchOrder.NEAREST
        );
    }

    public static List<BiomeCandidateSearch.Candidate> search(
        final ColumnStore store,
        final String targetBiomeId,
        final OptionalInt predictedBiomeId,
        final BaselineSampler sampler,
        final int centerX,
        final int centerZ,
        final int radius,
        final int limit,
        final SearchOrder order
    ) {
        return search(
            store, targetBiomeId, predictedBiomeId, sampler,
            centerX, centerZ, radius, limit, order, null, 1
        );
    }

    /**
     * Full search with an optional executor to run the predicted-scan row bands in parallel.
     * A {@code null} executor (or {@code parallelism <= 1}) scans single-threaded; the results
     * are identical either way because only the row-parallel scan phase is distributed - the
     * connected-component analysis always sees the merged whole grid.
     */
    public static List<BiomeCandidateSearch.Candidate> search(
        final ColumnStore store,
        final String targetBiomeId,
        final OptionalInt predictedBiomeId,
        final BaselineSampler sampler,
        final int centerX,
        final int centerZ,
        final int radius,
        final int limit,
        final SearchOrder order,
        final Executor executor,
        final int parallelism
    ) {
        final List<BiomeCandidateSearch.Candidate> candidates = new ArrayList<>(
            BiomeCandidateSearch.findExplored(
                store, targetBiomeId, centerX, centerZ, radius, limit, order
            )
        );
        if ((candidates.size() < limit || order == SearchOrder.FARTHEST)
            && predictedBiomeId.isPresent() && sampler != null) {
            for (final BiomeCandidateSearch.Candidate candidate : samplePredicted(
                sampler,
                predictedBiomeId.getAsInt(),
                centerX,
                centerZ,
                radius,
                limit,
                order,
                executor,
                parallelism
            )) {
                if (!store.hasRealChunk(
                    Math.floorDiv(candidate.blockX(), 16),
                    Math.floorDiv(candidate.blockZ(), 16)
                )) {
                    candidates.add(candidate);
                }
            }
        }
        return BiomeCandidateSearch.select(
            centerX, centerZ, radius, limit, candidates, order
        );
    }

    static List<BiomeCandidateSearch.Candidate> samplePredicted(
        final BaselineSampler sampler,
        final int targetBiomeId,
        final int centerX,
        final int centerZ,
        final int radius,
        final int limit
    ) {
        return samplePredicted(sampler, targetBiomeId, centerX, centerZ, radius, limit, SearchOrder.NEAREST);
    }

    static List<BiomeCandidateSearch.Candidate> samplePredicted(
        final BaselineSampler sampler,
        final int targetBiomeId,
        final int centerX,
        final int centerZ,
        final int radius,
        final int limit,
        final SearchOrder order
    ) {
        return samplePredicted(
            sampler, targetBiomeId, centerX, centerZ, radius, limit, order, null, 1
        );
    }

    static List<BiomeCandidateSearch.Candidate> samplePredicted(
        final BaselineSampler sampler,
        final int targetBiomeId,
        final int centerX,
        final int centerZ,
        final int radius,
        final int limit,
        final SearchOrder order,
        final Executor executor,
        final int parallelism
    ) {
        final int[] rings = ringRadii(radius);
        final List<BiomeCandidateSearch.Candidate> merged = new ArrayList<>();
        if (order == SearchOrder.FARTHEST) {
            for (int index = rings.length - 1; index >= 0; index--) {
                merged.addAll(scanRing(
                    sampler, targetBiomeId, centerX, centerZ, rings[index], limit, order,
                    executor, parallelism
                ));
                if (distinctCount(centerX, centerZ, radius, merged, order) >= limit) {
                    break;
                }
            }
        } else {
            for (final int ring : rings) {
                merged.addAll(scanRing(
                    sampler, targetBiomeId, centerX, centerZ, ring, limit, order,
                    executor, parallelism
                ));
                if (distinctCount(centerX, centerZ, radius, merged, order) >= limit) {
                    break;
                }
            }
        }
        return BiomeCandidateSearch.select(centerX, centerZ, radius, limit, merged, order);
    }

    /**
     * Ring schedule for a search radius: the innermost ring is capped at {@link
     * #FIRST_RING_RADIUS}, each subsequent ring grows by {@link #RING_GROWTH}, and the last
     * ring always equals the requested radius. Each ring grid is capped at {@link
     * #MAX_GRID_SIDE} cells per side, so sampling density falls with distance: a match 3k
     * blocks out resolves at a ~20-block stride while a 100k search still finishes. A
     * radius below the first-ring cap yields the old single-ring scan, grid math identical.
     */
    static int[] ringRadii(final int radius) {
        int[] rings = new int[8];
        int count = 0;
        int outer = Math.min(Math.max(4, radius), FIRST_RING_RADIUS);
        while (true) {
            if (count == rings.length) {
                rings = Arrays.copyOf(rings, rings.length * 2);
            }
            rings[count++] = outer;
            if (outer >= radius) {
                return Arrays.copyOf(rings, count);
            }
            outer = (int) Math.min((long) radius, (long) outer * RING_GROWTH);
        }
    }

    /**
     * Runs the sampling cascade over one ring's full square. Nearest-first scans inner to
     * outer and stops once the merged candidates fill the limit, so a large radius never
     * reports a farther "nearest" than a small one would; farthest-first mirrors that.
     * Overlapping rings can rediscover one occurrence, which {@code select}'s separation
     * rule absorbs.
     */
    private static List<BiomeCandidateSearch.Candidate> scanRing(
        final BaselineSampler sampler,
        final int targetBiomeId,
        final int centerX,
        final int centerZ,
        final int radius,
        final int limit,
        final SearchOrder order,
        final Executor executor,
        final int parallelism
    ) {
        final int radiusInBlocks = Math.max(4, ((radius + 3) / 4) * 4);
        final int spanInBlocks = radiusInBlocks * 2;
        final int minimumStride = (spanInBlocks + MAX_GRID_SIDE - 2) / (MAX_GRID_SIDE - 1);
        final int strideInBlocks = Math.max(4, ((minimumStride + 3) / 4) * 4);
        final int originX = Math.floorDiv(centerX, 4) * 4 - radiusInBlocks;
        final int originZ = Math.floorDiv(centerZ, 4) * 4 - radiusInBlocks;
        final int side = spanInBlocks / strideInBlocks + 1;

        // Sparse coarse pass first: cells that cannot be the target are rejected by a cheap
        // climate-parameter check inside the scan query, and only survivors pay the full
        // biome query. The native scan also re-tests each surviving cell's climate on the
        // surface slice (depth 0), because the scale-16 grid itself samples at quart y=0,
        // where a column under tall terrain reads as an underground biome. Scale 16
        // (Overworld climate, End) hits are refined back to surface-biome agreement below;
        // scale 4 (Nether, End source grid) matches the dense fallback's semantics and
        // returns directly. A ring whose coarse scan finds nothing at all falls through to
        // the surface pass; per-occurrence misses stay possible up to that ring's stride,
        // which is the resolution a grid capped at MAX_GRID_SIDE can promise.
        final int[] biomeIds = new int[side * side];

        // Scale 16: stride rounded up so the coarse grid covers at least the search span.
        final int stride16 = Math.max(1, (strideInBlocks + 15) / 16);
        final int originX16 = Math.floorDiv(originX, 16);
        final int originZ16 = Math.floorDiv(originZ, 16);
        if (scanCoarseGrid(
            sampler, 16, originX16, originZ16, side, side, stride16, targetBiomeId,
            executor, parallelism, biomeIds
        )) {
            final List<BiomeCandidateSearch.Candidate> coarse16 = BiomeCandidateSearch.fromGrid(
                targetBiomeId,
                originX16 * 16,
                originZ16 * 16,
                side,
                side,
                stride16 * 16,
                biomeIds,
                order
            );
            if (!coarse16.isEmpty()) {
                // Refine only the winners: a large coarse grid can hold hundreds of
                // connected components, and each refinement is a small but non-free surface grid.
                final List<BiomeCandidateSearch.Candidate> winners = BiomeCandidateSearch.select(
                    centerX, centerZ, radius, limit, coarse16, order
                );
                return BiomeCandidateSearch.select(
                    centerX, centerZ, radius, limit,
                    refineCoarseCandidates(sampler, targetBiomeId, winners),
                    order
                );
            }
        }

        // Scale 4: exact quart stride (strideInBlocks is a multiple of 4).
        if (scanCoarseGrid(
            sampler, 4, Math.floorDiv(originX, 4), Math.floorDiv(originZ, 4), side, side,
            strideInBlocks / 4, targetBiomeId, executor, parallelism, biomeIds
        )) {
            final List<BiomeCandidateSearch.Candidate> coarse4 = BiomeCandidateSearch.fromGrid(
                targetBiomeId, originX, originZ, side, side, strideInBlocks, biomeIds, order
            );
            if (!coarse4.isEmpty()) {
                return BiomeCandidateSearch.select(centerX, centerZ, radius, limit, coarse4, order);
            }
        }

        return surfaceScan(
            sampler, targetBiomeId, originX, originZ, side, strideInBlocks,
            centerX, centerZ, radius, limit, order, executor, parallelism
        );
    }

    /** Distinct-candidate count for the ring loop's early exit; reuses {@code select}'s
     *  separation rule so ring overlaps cannot fake a filled limit. */
    private static int distinctCount(
        final int centerX,
        final int centerZ,
        final int radius,
        final List<BiomeCandidateSearch.Candidate> merged,
        final SearchOrder order
    ) {
        return BiomeCandidateSearch.select(
            centerX, centerZ, radius, Integer.MAX_VALUE, merged, order
        ).size();
    }

    /**
     * The coarse climate grid can disagree with surface biomes near elevation changes; each
     * candidate gets one small surface-grid lookup around its coarse hit, and keeps the coarse
     * coordinates when the refinement cannot confirm the target (matching the coarse-grid
     * accuracy the dense fallback already delivers).
     */
    private static List<BiomeCandidateSearch.Candidate> refineCoarseCandidates(
        final BaselineSampler sampler,
        final int targetBiomeId,
        final List<BiomeCandidateSearch.Candidate> coarse
    ) {
        final List<BiomeCandidateSearch.Candidate> refined = new ArrayList<>(coarse.size());
        for (final BiomeCandidateSearch.Candidate candidate : coarse) {
            final int originX = candidate.blockX() - REFINE_HALF_SPAN;
            final int originZ = candidate.blockZ() - REFINE_HALF_SPAN;
            final int[] terrainY = new int[REFINE_SIDE * REFINE_SIDE];
            final int[] biomeIds = new int[REFINE_SIDE * REFINE_SIDE];
            if (sampler.overviewHeights(
                originX, originZ, REFINE_SIDE, REFINE_SIDE, REFINE_STRIDE, terrainY
            ) && sampler.surfaceBiomes(
                originX, originZ, REFINE_SIDE, REFINE_SIDE, REFINE_STRIDE, terrainY, biomeIds
            )) {
                final List<BiomeCandidateSearch.Candidate> hits = BiomeCandidateSearch.fromGrid(
                    targetBiomeId, originX, originZ, REFINE_SIDE, REFINE_SIDE, REFINE_STRIDE, biomeIds,
                    SearchOrder.NEAREST
                );
                if (!hits.isEmpty()) {
                    refined.add(hits.get(0));
                    continue;
                }
            }
            refined.add(candidate);
        }
        return refined;
    }

    /**
     * Runs one strided coarse scan, optionally split into row bands across the executor. Each
     * band is an independent scan query over its own rows and writes into its slice of
     * {@code out}, so the merged grid is identical to a single-threaded scan. The bands share
     * the sampler: {@code NativeBaselineSampler} resolves a per-thread context (see {@code
     * CubiomesContexts}), so the same instance is safe to call from several threads at once.
     */
    private static boolean scanCoarseGrid(
        final BaselineSampler sampler,
        final int scale,
        final int x,
        final int z,
        final int w,
        final int h,
        final int stride,
        final int targetBiomeId,
        final Executor executor,
        final int parallelism,
        final int[] out
    ) {
        return runRowBands(h, executor, parallelism, (z0, rows) -> {
            final int[] band = new int[rows * w];
            if (!sampler.biomesScanStrided(
                scale, x, z + z0 * stride, w, rows, stride, targetBiomeId, band
            )) {
                return false;
            }
            System.arraycopy(band, 0, out, z0 * w, rows * w);
            return true;
        });
    }

    private static List<BiomeCandidateSearch.Candidate> surfaceScan(
        final BaselineSampler sampler,
        final int targetBiomeId,
        final int originX,
        final int originZ,
        final int side,
        final int strideInBlocks,
        final int centerX,
        final int centerZ,
        final int radius,
        final int limit,
        final SearchOrder order,
        final Executor executor,
        final int parallelism
    ) {
        final int[] biomeIds = new int[side * side];
        final int[] terrainY = new int[side * side];
        final boolean sampledSurface = runRowBands(
            side, executor, parallelism,
            (z0, rows) -> {
                final int count = rows * side;
                final int[] bandTerrain = new int[count];
                final int[] bandBiomes = new int[count];
                final int bandZ = originZ + z0 * strideInBlocks;
                if (!sampler.overviewHeights(originX, bandZ, side, rows, strideInBlocks, bandTerrain)
                    || !sampler.surfaceBiomes(
                        originX, bandZ, side, rows, strideInBlocks, bandTerrain, bandBiomes
                    )) {
                    return false;
                }
                System.arraycopy(bandTerrain, 0, terrainY, z0 * side, count);
                System.arraycopy(bandBiomes, 0, biomeIds, z0 * side, count);
                return true;
            }
        );
        if (!sampledSurface && !runRowBands(
            side, executor, parallelism,
            (z0, rows) -> {
                final int[] band = new int[rows * side];
                if (!sampler.biomesStrided(
                    4,
                    Math.floorDiv(originX, 4),
                    Math.floorDiv(originZ, 4) + z0 * (strideInBlocks / 4),
                    side,
                    rows,
                    strideInBlocks / 4,
                    band
                )) {
                    return false;
                }
                System.arraycopy(band, 0, biomeIds, z0 * side, rows * side);
                return true;
            }
        )) {
            return List.of();
        }
        return BiomeCandidateSearch.select(
            centerX,
            centerZ,
            radius,
            limit,
            BiomeCandidateSearch.fromGrid(
                targetBiomeId,
                originX,
                originZ,
                side,
                side,
                strideInBlocks,
                biomeIds,
                order
            ),
            order
        );
    }

    /**
     * Splits {@code h} rows into up to {@code parallelism} bands and runs {@code band} on each.
     * The first band runs on the calling thread, so a caller that itself sits inside the same
     * bounded worker pool cannot deadlock waiting on bands queued behind it. A band failure or
     * exception fails the whole call (matching single-threaded semantics); the surviving bands
     * still finish so the caller's scratch buffers are never written concurrently afterwards.
     */
    private static boolean runRowBands(
        final int h,
        final Executor executor,
        final int parallelism,
        final RowBand band
    ) {
        if (executor == null || parallelism <= 1 || h <= 1) {
            return band.run(0, h);
        }
        final int bandHeight = (h + parallelism - 1) / parallelism;
        final List<int[]> bands = new ArrayList<>();
        for (int first = 0; first < h; first += bandHeight) {
            bands.add(new int[] {first, Math.min(bandHeight, h - first)});
        }
        final CountDownLatch done = new CountDownLatch(bands.size());
        final AtomicBoolean ok = new AtomicBoolean(true);
        final AtomicReference<RuntimeException> fault = new AtomicReference<>();
        for (int index = 0; index < bands.size(); index++) {
            final int z0 = bands.get(index)[0];
            final int rows = bands.get(index)[1];
            final Runnable task = () -> {
                try {
                    if (!band.run(z0, rows)) {
                        ok.set(false);
                    }
                } catch (final RuntimeException e) {
                    fault.compareAndSet(null, e);
                } finally {
                    done.countDown();
                }
            };
            if (index == 0) {
                task.run();
            } else {
                try {
                    executor.execute(task);
                } catch (final RejectedExecutionException e) {
                    task.run();
                }
            }
        }
        try {
            done.await();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        final RuntimeException failure = fault.get();
        if (failure != null) {
            throw failure;
        }
        return ok.get();
    }

    @FunctionalInterface
    private interface RowBand {
        boolean run(int z0, int rows);
    }
}
