package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.model.SurfaceKind;

/**
 * Deterministic seed-hashed tree canopy for forested biomes (see {@link BiomeTable#get}'s {@code
 * treeCover}), stylized in place onto an already-{@link BaselineDeriver#derive derived} grid.
 * cubiomes gives no vegetation data at all - this is a synthesized texture, not a real
 * prediction, so both the biome and the algorithm below are fixed/compiled-in (never depend on
 * anything the server could tell us), matching the plan's "same algorithm on both sides" note
 * for future residual coding.
 *
 * <p>Two regimes, both driven by a splitmix64-mixed hash of {@code (seed, blockX, blockZ)}:
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
 * <p>Runs over the whole margin-inclusive grid (not just the rendered 256x256), so the
 * slope-shading neighbor read at a tile's own west/south edge sees the same stylization an
 * ordinary pixel would.
 */
public final class CanopyStylizer {
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
                final int bump = lod <= 1 ? canopyBlob(seed, bx, bz, treeCover) : canopyPoint(seed, bx, bz, treeCover);
                if (bump > 0) {
                    derived.kind[idx] = (byte) SurfaceKind.FOLIAGE.ordinal();
                    derived.surfaceY[idx] += bump;
                }
            }
        }
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
