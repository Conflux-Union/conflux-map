package cn.net.rms.confluxmap.core.color;

/**
 * The part of a chunk whose biome-blend square is guaranteed to stay inside chunks the
 * client actually has.
 *
 * <p>A biome tint is an average over a square of {@code blendRadius} blocks around the
 * sampled position, so a column near a chunk border reads biomes out of the adjacent
 * chunks. Positions inside a chunk the client has not received answer with the client's
 * plains fallback biome, which drags the average toward plains-blue water and plains-green
 * grass - a chunk grid of wrong tints baked into whatever was sampled at that moment.
 *
 * <p>This window is the answer for columns that cannot be sampled honestly: instead of
 * averaging in a biome that isn't there, the tint is read from the nearest position whose
 * whole blend square is inside loaded data. Inside a uniform biome that is exactly the
 * right color; across a biome boundary it displaces the boundary by at most
 * {@code blendRadius} blocks, until the missing neighbour arrives and the chunk is
 * sampled again at full quality.
 *
 * <p>Chunk-local coordinates throughout: 0..15 on both axes.
 */
public final class BiomeSampleWindow {
    private static final int LAST = 15;

    /** Every neighbour present (or blending disabled): sample wherever the caller asked. */
    public static final BiomeSampleWindow FULL = new BiomeSampleWindow(0, LAST, 0, LAST);

    private final int minLocalX;
    private final int maxLocalX;
    private final int minLocalZ;
    private final int maxLocalZ;

    private BiomeSampleWindow(final int minLocalX, final int maxLocalX, final int minLocalZ, final int maxLocalZ) {
        this.minLocalX = minLocalX;
        this.maxLocalX = maxLocalX;
        this.minLocalZ = minLocalZ;
        this.maxLocalZ = maxLocalZ;
    }

    /**
     * The window for a chunk whose four sides are clear (side = the three neighbours the
     * blend square can reach on that side, so "west clear" means west, north-west and
     * south-west are all loaded). A blocked side is inset by the blend radius, which is
     * what keeps the square off the missing chunk.
     *
     * <p>The inset is capped at {@code LAST / 2} so the window never inverts: vanilla's
     * largest blend radius is 7, which leaves the two center columns.
     */
    public static BiomeSampleWindow of(
        final int blendRadius,
        final boolean westClear,
        final boolean eastClear,
        final boolean northClear,
        final boolean southClear
    ) {
        if (blendRadius <= 0 || (westClear && eastClear && northClear && southClear)) {
            return FULL;
        }
        final int inset = Math.min(blendRadius, LAST / 2);
        return new BiomeSampleWindow(
            westClear ? 0 : inset,
            eastClear ? LAST : LAST - inset,
            northClear ? 0 : inset,
            southClear ? LAST : LAST - inset
        );
    }

    /** Whether this window samples every column where the caller asked. */
    public boolean full() {
        return minLocalX == 0 && maxLocalX == LAST && minLocalZ == 0 && maxLocalZ == LAST;
    }

    public int clampLocalX(final int localX) {
        return Math.min(Math.max(localX, minLocalX), maxLocalX);
    }

    public int clampLocalZ(final int localZ) {
        return Math.min(Math.max(localZ, minLocalZ), maxLocalZ);
    }
}
