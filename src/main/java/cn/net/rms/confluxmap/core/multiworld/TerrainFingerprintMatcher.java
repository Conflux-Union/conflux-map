package cn.net.rms.confluxmap.core.multiworld;

import cn.net.rms.confluxmap.core.cache.RegionFileCodec;
import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import cn.net.rms.confluxmap.core.store.RegionColumns;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Last-resort comparison between loaded terrain and each profile's existing disk cache. */
public final class TerrainFingerprintMatcher {
    public static final int MIN_CHUNKS = 4;
    public static final int MIN_COLUMNS = 512;
    public static final double MIN_SCORE = 0.85D;
    public static final double MIN_WINNER_GAP = 0.15D;
    private static final int MIN_COLUMNS_PER_CHUNK = 128;

    public record RegionPos(int x, int z) {
    }

    public record Candidate(String profileId, Map<RegionPos, RegionFileCodec.RegionData> regions) {
        public Candidate {
            Objects.requireNonNull(profileId, "profileId");
            regions = Map.copyOf(Objects.requireNonNull(regions, "regions"));
        }
    }

    public record Result(Optional<String> profileId, double bestScore, double runnerUpScore) {
    }

    private TerrainFingerprintMatcher() {
    }

    public static Result match(
        final List<ChunkSnapshot> probes,
        final List<Candidate> candidates
    ) {
        String bestProfile = null;
        double best = 0.0D;
        double runnerUp = 0.0D;
        for (final Candidate candidate : candidates) {
            final Score score = score(probes, candidate.regions());
            if (!score.sufficient()) {
                continue;
            }
            if (score.value() > best) {
                runnerUp = best;
                best = score.value();
                bestProfile = candidate.profileId();
            } else if (score.value() > runnerUp) {
                runnerUp = score.value();
            }
        }
        final boolean confident = bestProfile != null
            && best >= MIN_SCORE
            && best - runnerUp >= MIN_WINNER_GAP;
        return new Result(confident ? Optional.of(bestProfile) : Optional.empty(), best, runnerUp);
    }

    private static Score score(
        final List<ChunkSnapshot> probes,
        final Map<RegionPos, RegionFileCodec.RegionData> regions
    ) {
        int comparableChunks = 0;
        int comparableColumns = 0;
        double similarity = 0.0D;
        for (final ChunkSnapshot probe : probes) {
            final RegionFileCodec.RegionData cached = regions.get(new RegionPos(probe.chunkX >> 4, probe.chunkZ >> 4));
            if (cached == null) {
                continue;
            }
            int chunkColumns = 0;
            double chunkSimilarity = 0.0D;
            final int baseX = (probe.chunkX & (RegionColumns.CHUNKS - 1)) * 16;
            final int baseZ = (probe.chunkZ & (RegionColumns.CHUNKS - 1)) * 16;
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    final int probeIndex = z * 16 + x;
                    final int cachedIndex = (baseZ + z) * RegionColumns.SIZE + baseX + x;
                    if (probe.surfaceY[probeIndex] == ChunkSnapshot.NO_SURFACE
                        || cached.surfaceY()[cachedIndex] == ChunkSnapshot.NO_SURFACE) {
                        continue;
                    }
                    chunkColumns++;
                    chunkSimilarity += columnSimilarity(probe, probeIndex, cached, cachedIndex);
                }
            }
            if (chunkColumns >= MIN_COLUMNS_PER_CHUNK) {
                comparableChunks++;
                comparableColumns += chunkColumns;
                similarity += chunkSimilarity;
            }
        }
        final boolean sufficient = comparableChunks >= MIN_CHUNKS && comparableColumns >= MIN_COLUMNS;
        return new Score(sufficient ? similarity / comparableColumns : 0.0D, sufficient);
    }

    private static double columnSimilarity(
        final ChunkSnapshot probe,
        final int probeIndex,
        final RegionFileCodec.RegionData cached,
        final int cachedIndex
    ) {
        double score = 0.0D;
        final int heightDifference = Math.abs(probe.surfaceY[probeIndex] - cached.surfaceY()[cachedIndex]);
        if (heightDifference <= 1) {
            score += 0.45D;
        } else if (heightDifference <= 3) {
            score += 0.25D;
        }
        if (Objects.equals(probe.biomeId[probeIndex], cached.biomeId()[cachedIndex])) {
            score += 0.30D;
        }
        if (probe.kind[probeIndex] == cached.kind()[cachedIndex]) {
            score += 0.15D;
        }
        final int fluidDifference = Math.abs(
            Byte.toUnsignedInt(probe.fluidDepth[probeIndex]) - Byte.toUnsignedInt(cached.fluidDepth()[cachedIndex])
        );
        if (fluidDifference == 0) {
            score += 0.10D;
        } else if (fluidDifference == 1) {
            score += 0.05D;
        }
        return score;
    }

    private record Score(double value, boolean sufficient) {
    }
}
