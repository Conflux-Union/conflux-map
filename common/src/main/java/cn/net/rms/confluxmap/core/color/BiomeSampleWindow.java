package cn.net.rms.confluxmap.core.color;

/**
 * The part of a chunk whose surrounding biome samples are guaranteed to stay inside chunks
 * the client actually has.
 *
 * <p>A biome tint averages a square around the sampled position, while vanilla's biome
 * identity lookup applies a Voronoi offset before reading the quart-biome grid. Both can make
 * a column near a chunk border read biomes out of an adjacent chunk. Positions inside a chunk
 * the client has not received answer with the client's plains fallback biome, which can bake
 * wrong tints or a literal plains identity into the snapshot.
 *
 * <p>This window is the answer for columns that cannot be sampled honestly: instead of
 * reading a biome that isn't there, the sample is resolved at the nearest position whose
 * surrounding sample footprint is inside loaded data. Inside a uniform biome that is exactly
 * the right value; across a biome boundary it displaces the boundary by at most the requested
 * inset, until the missing neighbour arrives and the chunk is sampled again at full quality.
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
     * sample footprint can reach on that side, so "west clear" means west, north-west and
     * south-west are all loaded). A blocked side is inset by the sample radius, which keeps
     * the lookup off the missing chunk.
     *
     * <p>The inset is capped at {@code LAST / 2} so the window never inverts: vanilla's
     * largest blend radius is 7, which leaves the two center columns.
     */
    public static BiomeSampleWindow of(
        final int sampleRadius,
        final boolean westClear,
        final boolean eastClear,
        final boolean northClear,
        final boolean southClear
    ) {
        if (sampleRadius <= 0 || (westClear && eastClear && northClear && southClear)) {
            return FULL;
        }
        final int inset = Math.min(sampleRadius, LAST / 2);
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
