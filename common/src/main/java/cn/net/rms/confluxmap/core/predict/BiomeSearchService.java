package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.store.ColumnStore;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/** Seed-backed biome candidate sampling shared by the fullscreen search UI. */
public final class BiomeSearchService {
    private static final int MAX_GRID_SIDE = 513;

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
        final List<BiomeCandidateSearch.Candidate> candidates = new ArrayList<>(
            BiomeCandidateSearch.findExplored(
                store, targetBiomeId, centerX, centerZ, radius, limit
            )
        );
        if (candidates.size() < limit && predictedBiomeId.isPresent() && sampler != null) {
            for (final BiomeCandidateSearch.Candidate candidate : samplePredicted(
                sampler,
                predictedBiomeId.getAsInt(),
                centerX,
                centerZ,
                radius,
                limit
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
            centerX, centerZ, radius, limit, candidates
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
        final int radiusInBlocks = Math.max(4, ((radius + 3) / 4) * 4);
        final int spanInBlocks = radiusInBlocks * 2;
        final int minimumStride = (spanInBlocks + MAX_GRID_SIDE - 2) / (MAX_GRID_SIDE - 1);
        final int strideInBlocks = Math.max(4, ((minimumStride + 3) / 4) * 4);
        final int originX = Math.floorDiv(centerX, 4) * 4 - radiusInBlocks;
        final int originZ = Math.floorDiv(centerZ, 4) * 4 - radiusInBlocks;
        final int side = spanInBlocks / strideInBlocks + 1;
        final int[] biomeIds = new int[side * side];
        final int[] terrainY = new int[side * side];
        final boolean sampledSurface = sampler.overviewHeights(
            originX, originZ, side, side, strideInBlocks, terrainY
        ) && sampler.surfaceBiomes(
            originX, originZ, side, side, strideInBlocks, terrainY, biomeIds
        );
        if (!sampledSurface && !sampler.biomesStrided(
            4,
            Math.floorDiv(originX, 4),
            Math.floorDiv(originZ, 4),
            side,
            side,
            strideInBlocks / 4,
            biomeIds
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
                biomeIds
            )
        );
    }
}
