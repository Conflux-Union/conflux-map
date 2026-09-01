package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.model.SampleSource;
import cn.net.rms.confluxmap.core.store.ColumnStore;
import cn.net.rms.confluxmap.core.store.RegionColumns;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Selects useful, distinct biome locations from explored and predicted samples. */
public final class BiomeCandidateSearch {
    private static final long MIN_SEPARATION_SQUARED = 128L * 128L;

    public enum Source {
        EXPLORED,
        PREDICTED
    }

    public record Candidate(int blockX, int blockZ, Source source) {
    }

    private BiomeCandidateSearch() {
    }

    public static List<Candidate> findExplored(
        final ColumnStore store,
        final String targetBiomeId,
        final int centerX,
        final int centerZ,
        final int radius,
        final int limit
    ) {
        final long radiusSquared = (long) radius * radius;
        final List<Candidate> samples = new ArrayList<>();
        for (final RegionColumns region : store.allRegions()) {
            final int originX = region.regionX * RegionColumns.SIZE;
            final int originZ = region.regionZ * RegionColumns.SIZE;
            if (!intersects(originX, originZ, centerX, centerZ, radiusSquared)) {
                continue;
            }
            final String[] biomeIds = new String[RegionColumns.SIZE * RegionColumns.SIZE];
            final byte[] chunkSources = new byte[RegionColumns.CHUNKS * RegionColumns.CHUNKS];
            region.copyBiomeSearchData(biomeIds, chunkSources);
            final Candidate[] nearestByCell = new Candidate[4];
            for (int localZ = 0; localZ < RegionColumns.SIZE; localZ++) {
                for (int localX = 0; localX < RegionColumns.SIZE; localX++) {
                    final int index = localZ * RegionColumns.SIZE + localX;
                    final int chunkIndex = (localZ / 16) * RegionColumns.CHUNKS + localX / 16;
                    if (!targetBiomeId.equals(biomeIds[index])
                        || SampleSource.byOrdinal(chunkSources[chunkIndex]).priority()
                            < SampleSource.REAL_CACHED.priority()) {
                        continue;
                    }
                    final Candidate candidate = new Candidate(
                        originX + localX, originZ + localZ, Source.EXPLORED
                    );
                    if (distanceSquared(candidate, centerX, centerZ) > radiusSquared) {
                        continue;
                    }
                    final int cell = (localZ / 128) * 2 + localX / 128;
                    if (nearestByCell[cell] == null
                        || distanceSquared(candidate, centerX, centerZ)
                            < distanceSquared(nearestByCell[cell], centerX, centerZ)) {
                        nearestByCell[cell] = candidate;
                    }
                }
            }
            for (final Candidate candidate : nearestByCell) {
                if (candidate != null) {
                    samples.add(candidate);
                }
            }
        }
        return select(centerX, centerZ, radius, limit, samples);
    }

    private static boolean intersects(
        final int originX,
        final int originZ,
        final int centerX,
        final int centerZ,
        final long radiusSquared
    ) {
        final long nearestX = Math.max(originX, Math.min((long) centerX, originX + 255L));
        final long nearestZ = Math.max(originZ, Math.min((long) centerZ, originZ + 255L));
        final long dx = centerX - nearestX;
        final long dz = centerZ - nearestZ;
        return dx * dx + dz * dz <= radiusSquared;
    }

    public static List<Candidate> fromGrid(
        final String targetBiomeId,
        final int originX,
        final int originZ,
        final int width,
        final int height,
        final int stride,
        final String[] biomeIds
    ) {
        final List<Candidate> candidates = new ArrayList<>();
        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                if (targetBiomeId.equals(biomeIds[z * width + x])) {
                    candidates.add(new Candidate(
                        originX + x * stride,
                        originZ + z * stride,
                        Source.EXPLORED
                    ));
                }
            }
        }
        return candidates;
    }

    public static List<Candidate> fromGrid(
        final int targetBiomeId,
        final int originX,
        final int originZ,
        final int width,
        final int height,
        final int stride,
        final int[] biomeIds
    ) {
        final List<Candidate> candidates = new ArrayList<>();
        final boolean[] visited = new boolean[width * height];
        final int[] queue = new int[width * height];
        final int centerGridX = width / 2;
        final int centerGridZ = height / 2;
        for (int start = 0; start < biomeIds.length; start++) {
            if (visited[start] || biomeIds[start] != targetBiomeId) {
                continue;
            }
            int head = 0;
            int tail = 0;
            int best = start;
            queue[tail++] = start;
            visited[start] = true;
            while (head < tail) {
                final int current = queue[head++];
                if (gridDistanceSquared(current, width, centerGridX, centerGridZ)
                    < gridDistanceSquared(best, width, centerGridX, centerGridZ)) {
                    best = current;
                }
                final int x = current % width;
                final int z = current / width;
                if (x > 0) {
                    tail = enqueue(current - 1, targetBiomeId, biomeIds, visited, queue, tail);
                }
                if (x + 1 < width) {
                    tail = enqueue(current + 1, targetBiomeId, biomeIds, visited, queue, tail);
                }
                if (z > 0) {
                    tail = enqueue(current - width, targetBiomeId, biomeIds, visited, queue, tail);
                }
                if (z + 1 < height) {
                    tail = enqueue(current + width, targetBiomeId, biomeIds, visited, queue, tail);
                }
            }
            candidates.add(new Candidate(
                originX + (best % width) * stride,
                originZ + (best / width) * stride,
                Source.PREDICTED
            ));
        }
        return candidates;
    }

    private static int enqueue(
        final int index,
        final int targetBiomeId,
        final int[] biomeIds,
        final boolean[] visited,
        final int[] queue,
        final int tail
    ) {
        if (visited[index] || biomeIds[index] != targetBiomeId) {
            return tail;
        }
        visited[index] = true;
        queue[tail] = index;
        return tail + 1;
    }

    private static long gridDistanceSquared(
        final int index,
        final int width,
        final int centerX,
        final int centerZ
    ) {
        final long dx = index % width - (long) centerX;
        final long dz = index / width - (long) centerZ;
        return dx * dx + dz * dz;
    }

    public static List<Candidate> select(
        final int centerX,
        final int centerZ,
        final int radius,
        final int limit,
        final List<Candidate> samples
    ) {
        final long radiusSquared = (long) radius * radius;
        final List<Candidate> sorted = new ArrayList<>(samples);
        sorted.sort(
            Comparator.comparing(Candidate::source)
                .thenComparingLong(candidate -> distanceSquared(candidate, centerX, centerZ))
        );
        final List<Candidate> selected = new ArrayList<>();
        for (final Candidate candidate : sorted) {
            if (distanceSquared(candidate, centerX, centerZ) > radiusSquared
                || tooClose(candidate, selected)) {
                continue;
            }
            selected.add(candidate);
            if (selected.size() == limit) {
                break;
            }
        }
        return List.copyOf(selected);
    }

    private static boolean tooClose(final Candidate candidate, final List<Candidate> selected) {
        for (final Candidate existing : selected) {
            if (distanceSquared(candidate, existing.blockX(), existing.blockZ())
                < MIN_SEPARATION_SQUARED) {
                return true;
            }
        }
        return false;
    }

    private static long distanceSquared(final Candidate candidate, final int x, final int z) {
        final long dx = candidate.blockX() - (long) x;
        final long dz = candidate.blockZ() - (long) z;
        return dx * dx + dz * dz;
    }
}
