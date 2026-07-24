package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.model.SurfaceKind;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Stylizes tree canopy in place onto an already-{@link BaselineDeriver#derive derived} grid.
 * LOD0-1 uses cubiomes' versioned natural tree candidates where the sampler supports the biome
 * decorator. Unsupported chunks retain the deterministic seed-hashed fallback based on
 * {@link BiomeTable#get}'s {@code treeCover}. Candidates are still honest
 * estimates: the first accepted candidate for each supported placed feature has exact X/Z and
 * type, while later candidates can drift after terrain-dependent random consumption, and vanilla
 * may reject any candidate.
 *
 * <p>The deterministic fallback has two regimes, both driven by a splitmix64-mixed hash of
 * {@code (seed, blockX, blockZ)}:
 * <ul>
 *   <li>LOD 0-1: "grid-cell anchors" - the world is partitioned into 4-block cells; each cell
 *       independently rolls (via its own origin's hash) whether it's a tree-blob anchor at
 *       probability {@code treeCover}, and if so covers a small radius-1-or-2 disc around its
 *       center. A pixel is foliage if it falls inside any of the 9 candidate cells' (its own
 *       plus the 8 neighbors, since a radius-2 blob can spill across a cell boundary) discs.
 *       This clumps canopy into small blobs instead of single-pixel noise, which would look
 *       wrong at 1-2 blocks/pixel.
 *   <li>LOD &ge; 2: a direct per-pixel Bernoulli test - at 4+ blocks/pixel individual trees
 *       aren't distinguishable anyway, so per-pixel noise reads as an aggregate canopy texture.
 * </ul>
 *
 * <p>Runs over the whole margin-inclusive grid (not just the rendered 256x256), so both diagonal
 * slope samples at tile edges see the same stylization ordinary pixels would.
 */
public final class CanopyStylizer {
    private static final int MAX_NATURAL_LOD = 1;
    private static final int MAX_CANDIDATES_PER_CHUNK = 256;
    // cubiomes terrain_features.h enum NaturalTreeType ordinals.
    private static final int TREE_JUNGLE = 3;
    private static final int TREE_DARK_OAK = 5;
    private static final int TREE_JUNGLE_BUSH = 6;
    private static final int TREE_HUGE_BROWN_MUSHROOM = 7;
    private static final int TREE_HUGE_RED_MUSHROOM = 8;
    private static final int TREE_MANGROVE = 9;
    private static final int TREE_CHERRY = 10;
    private static final int TREE_BAMBOO = 11;
    private static final int FEATURE_CANDIDATE_HEIGHT_SHIFT = 16;
    private static final int FEATURE_CANDIDATE_HEIGHT_MASK = 0xFF;

    /** Fixed salt distinguishing this hash from any other seed-derived synthetic feature. */
    private static final long SALT = 0x27220A5FA9A9A797L;
    private static final long MIX_X = 0x9E3779B185EBCA87L;
    private static final long MIX_Z = 0xC2B2AE3D27D4EB4FL;
    /** {@code treeCover} (a [0,1] double) is tested against this many low bits of the hash. */
    private static final double PROBABILITY_SCALE = 4294967296.0; // 2^32
    /** Each canopy anchor owns a 4x4-block cell ({@link #canopyBlob}). */
    private static final double CELL_AREA = 16.0;
    /**
     * Average pixel count of a radius-1-or-2 digital disc: radius 1 covers 5 pixels (the center
     * plus the four orthogonal neighbors), radius 2 covers 13 (a 5x5 square minus the four
     * corner pairs at distance {@code sqrt(8) > 2}); the 50/50 radius pick averages to 9.
     */
    private static final double AVG_BLOB_AREA = 9.0;

    private CanopyStylizer() {
    }

    public static void apply(
        final DerivedGrid derived, final BaselineGrid grid, final long seed, final int lod, final int tileOriginX, final int tileOriginZ
    ) {
        applySynthetic(derived, grid, seed, lod, tileOriginX, tileOriginZ);
    }

    /** Uses native natural features when available, with per-chunk or full-tile synthetic fallback. */
    public static void apply(
        final DerivedGrid derived, final BaselineGrid grid, final BaselineSampler sampler,
        final long seed, final int lod, final int tileOriginX, final int tileOriginZ
    ) {
        if (sampler != null && lod <= MAX_NATURAL_LOD
            && applyNatural(derived, grid, sampler, seed, lod, tileOriginX, tileOriginZ)) {
            return;
        }
        applySynthetic(derived, grid, seed, lod, tileOriginX, tileOriginZ);
    }

    private static void applySynthetic(
        final DerivedGrid derived, final BaselineGrid grid, final long seed,
        final int lod, final int tileOriginX, final int tileOriginZ
    ) {
        applySynthetic(derived, grid, seed, lod, tileOriginX, tileOriginZ, null);
    }

    private static void applySynthetic(
        final DerivedGrid derived, final BaselineGrid grid, final long seed,
        final int lod, final int tileOriginX, final int tileOriginZ, final Set<Long> chunks
    ) {
        final int bpp = 1 << lod;
        final int min = -BaselineGrid.MARGIN;
        final int max = BaselineGrid.PIXELS - 1 + BaselineGrid.MARGIN;
        for (int pz = min; pz <= max; pz++) {
            for (int px = min; px <= max; px++) {
                final int idx = BaselineGrid.index(px, pz);
                final SurfaceKind kind = SurfaceKind.byOrdinal(derived.kind[idx]);
                if (kind == SurfaceKind.WATER || kind == SurfaceKind.ICE || kind == SurfaceKind.VOID) {
                    continue;
                }
                final double treeCover = BiomeTable.get(grid.biomeId[idx]).treeCover();
                if (treeCover <= 0.0) {
                    continue;
                }
                final int bx = tileOriginX + px * bpp;
                final int bz = tileOriginZ + pz * bpp;
                if (chunks != null && !chunks.contains(chunkKey(Math.floorDiv(bx, 16), Math.floorDiv(bz, 16)))) {
                    continue;
                }
                final int bump = lod <= 1 ? canopyBlob(seed, bx, bz, treeCover) : canopyPoint(seed, bx, bz, treeCover);
                if (bump > 0) {
                    derived.kind[idx] = (byte) SurfaceKind.FOLIAGE.ordinal();
                    derived.surfaceY[idx] += bump;
                }
            }
        }
    }

    private static boolean applyNatural(
        final DerivedGrid derived, final BaselineGrid grid, final BaselineSampler sampler,
        final long seed, final int lod, final int tileOriginX, final int tileOriginZ
    ) {
        final int blocksPerPixel = 1 << lod;
        final int minBlockX = tileOriginX - BaselineGrid.MARGIN * blocksPerPixel;
        final int minBlockZ = tileOriginZ - BaselineGrid.MARGIN * blocksPerPixel;
        final int maxBlockX = tileOriginX
            + (BaselineGrid.PIXELS - 1 + BaselineGrid.MARGIN) * blocksPerPixel;
        final int maxBlockZ = tileOriginZ
            + (BaselineGrid.PIXELS - 1 + BaselineGrid.MARGIN) * blocksPerPixel;
        final int minChunkX = Math.floorDiv(minBlockX, 16);
        final int minChunkZ = Math.floorDiv(minBlockZ, 16);
        final int maxChunkX = Math.floorDiv(maxBlockX, 16);
        final int maxChunkZ = Math.floorDiv(maxBlockZ, 16);
        final TreeCandidate[] chunkCandidates = new TreeCandidate[MAX_CANDIDATES_PER_CHUNK];
        final List<TreeCandidate> candidates = new ArrayList<>();
        final Set<Long> fallbackChunks = new HashSet<>();

        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                final int count = sampler.treeCandidates(chunkX, chunkZ, chunkCandidates);
                if (count == BaselineSampler.TREES_UNSUPPORTED) {
                    fallbackChunks.add(chunkKey(chunkX, chunkZ));
                    continue;
                }
                if (count < 0 || count > chunkCandidates.length) {
                    return false;
                }
                for (int i = 0; i < count; i++) {
                    final TreeCandidate candidate = chunkCandidates[i];
                    if (candidate == null) {
                        return false;
                    }
                    candidates.add(candidate);
                }
            }
        }

        final int[] baseSurfaceY = derived.surfaceY.clone();
        if (!fallbackChunks.isEmpty()) {
            applySynthetic(derived, grid, seed, lod, tileOriginX, tileOriginZ, fallbackChunks);
        }
        for (final TreeCandidate candidate : candidates) {
            paintCandidate(derived, baseSurfaceY, candidate, lod, tileOriginX, tileOriginZ);
        }
        return true;
    }

    private static long chunkKey(final int chunkX, final int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    private static void paintCandidate(
        final DerivedGrid derived, final int[] baseSurfaceY,
        final TreeCandidate candidate, final int lod, final int tileOriginX, final int tileOriginZ
    ) {
        final int blocksPerPixel = 1 << lod;
        final int radius = canopyRadius(candidate.type()) + blocksPerPixel / 2;
        final int minPixelX = Math.max(
            -BaselineGrid.MARGIN,
            Math.floorDiv(candidate.x() - radius - tileOriginX, blocksPerPixel)
        );
        final int maxPixelX = Math.min(
            BaselineGrid.PIXELS - 1 + BaselineGrid.MARGIN,
            Math.floorDiv(candidate.x() + radius - tileOriginX, blocksPerPixel)
        );
        final int minPixelZ = Math.max(
            -BaselineGrid.MARGIN,
            Math.floorDiv(candidate.z() - radius - tileOriginZ, blocksPerPixel)
        );
        final int maxPixelZ = Math.min(
            BaselineGrid.PIXELS - 1 + BaselineGrid.MARGIN,
            Math.floorDiv(candidate.z() + radius - tileOriginZ, blocksPerPixel)
        );
        final int centerOffset = blocksPerPixel / 2;
        final int bump = candidateHeight(candidate);
        final boolean foliage = candidate.type() != TREE_BAMBOO;

        for (int pixelZ = minPixelZ; pixelZ <= maxPixelZ; pixelZ++) {
            final int dz = tileOriginZ + pixelZ * blocksPerPixel + centerOffset - candidate.z();
            for (int pixelX = minPixelX; pixelX <= maxPixelX; pixelX++) {
                final int dx = tileOriginX + pixelX * blocksPerPixel + centerOffset - candidate.x();
                if (dx * dx + dz * dz > radius * radius) {
                    continue;
                }
                final int index = BaselineGrid.index(pixelX, pixelZ);
                final SurfaceKind kind = SurfaceKind.byOrdinal(derived.kind[index]);
                if (kind == SurfaceKind.WATER || kind == SurfaceKind.ICE || kind == SurfaceKind.VOID) {
                    continue;
                }
                if (foliage) {
                    derived.kind[index] = (byte) SurfaceKind.FOLIAGE.ordinal();
                }
                derived.surfaceY[index] = Math.max(derived.surfaceY[index], baseSurfaceY[index] + bump);
            }
        }
    }

    private static int canopyRadius(final int type) {
        return switch (type) {
            case TREE_BAMBOO -> 0;
            case TREE_MANGROVE -> 3;
            case TREE_CHERRY -> 4;
            case TREE_JUNGLE, TREE_DARK_OAK, TREE_HUGE_BROWN_MUSHROOM, TREE_HUGE_RED_MUSHROOM -> 2;
            default -> 1;
        };
    }

    private static int candidateHeight(final TreeCandidate candidate) {
        if (candidate.type() == TREE_BAMBOO) {
            final int stalkHeight = (candidate.flags() >>> FEATURE_CANDIDATE_HEIGHT_SHIFT)
                & FEATURE_CANDIDATE_HEIGHT_MASK;
            return stalkHeight + 1;
        }
        return canopyHeight(candidate.type());
    }

    private static int canopyHeight(final int type) {
        return switch (type) {
            case TREE_JUNGLE -> 10;
            case TREE_MANGROVE -> 8;
            case TREE_CHERRY -> 10;
            case TREE_DARK_OAK, TREE_HUGE_BROWN_MUSHROOM, TREE_HUGE_RED_MUSHROOM -> 4;
            case TREE_JUNGLE_BUSH -> 2;
            default -> 3;
        };
    }

    /**
     * Returns the height bump if {@code (bx, bz)} falls inside a triggered cell's blob, else 0.
     * The per-cell trigger probability is scaled up from raw {@code treeCover} by the
     * cell-area/blob-area ratio so the <em>effective areal coverage</em> of all blobs matches
     * {@code treeCover} - a bare {@code treeCover} roll would only achieve that fraction times
     * the blob/cell area ratio, leaving canopy looking far sparser than the table implies.
     */
    private static int canopyBlob(final long seed, final int bx, final int bz, final double treeCover) {
        final int myCellX = Math.floorDiv(bx, 4);
        final int myCellZ = Math.floorDiv(bz, 4);
        final double anchorProb = Math.min(1.0, treeCover * CELL_AREA / AVG_BLOB_AREA);
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                final int originX = (myCellX + dx) * 4;
                final int originZ = (myCellZ + dz) * 4;
                final long hash = hash(seed, originX, originZ);
                if (!bernoulli(hash, anchorProb)) {
                    continue;
                }
                final int radius = 1 + (int) ((hash >>> 40) & 1L);
                final int ddx = bx - (originX + 2);
                final int ddz = bz - (originZ + 2);
                if (ddx * ddx + ddz * ddz <= radius * radius) {
                    return 3 + (int) ((hash >>> 41) & 3L);
                }
            }
        }
        return 0;
    }

    private static int canopyPoint(final long seed, final int bx, final int bz, final double treeCover) {
        return bernoulli(hash(seed, bx, bz), treeCover) ? 2 : 0;
    }

    private static boolean bernoulli(final long hash, final double probability) {
        final long threshold = (long) (probability * PROBABILITY_SCALE);
        final long sample = hash & 0xFFFFFFFFL;
        return sample < threshold;
    }

    private static long hash(final long seed, final int bx, final int bz) {
        long h = splitmix64(seed ^ SALT);
        h = splitmix64(h ^ (long) bx * MIX_X);
        h = splitmix64(h ^ (long) bz * MIX_Z);
        return h;
    }

    private static long splitmix64(final long input) {
        long x = input + 0x9E3779B97F4A7C15L;
        x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
        x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
        return x ^ (x >>> 31);
    }
}
