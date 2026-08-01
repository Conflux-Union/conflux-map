package cn.net.rms.confluxmap.core.predict;

/**
 * {@link BaselineGrid}'s biome/height pair turned into per-pixel render data by {@link
 * BaselineDeriver} (water/land classification) and then {@link CanopyStylizer} (tree
 * blobs). Same size and indexing as {@link BaselineGrid} ({@link BaselineGrid#index}) so slope
 * shading can read both diagonal samples from the matching cells here.
 */
public final class DerivedGrid {
    /** Final surface Y (after the water rule's flatten-to-sea-level and canopy's height bump). */
    public final int[] surfaceY = new int[BaselineGrid.SIZE * BaselineGrid.SIZE];
    /** {@link cn.net.rms.confluxmap.core.model.SurfaceKind} ordinal. */
    public final byte[] kind = new byte[BaselineGrid.SIZE * BaselineGrid.SIZE];
    /** 0-255, only meaningful where {@link #kind} is WATER. */
    public final int[] fluidDepth = new int[BaselineGrid.SIZE * BaselineGrid.SIZE];

    /**
     * Per-sub-sample counterparts, indexed by {@link BaselineGrid#subIndex}. Empty unless the
     * source {@link BaselineGrid} was supersampled. Only the colour stage reads these: relief and
     * height shading stay on the per-pixel arrays above, since those follow the shared height
     * field rather than the biome.
     */
    public final byte[] subKind;
    public final int[] subSurfaceY;
    public final int[] subFluidDepth;

    public DerivedGrid() {
        this(BaselineGrid.NO_SUPERSAMPLING);
    }

    public DerivedGrid(final int subPerAxis) {
        final int subCells = subPerAxis == BaselineGrid.NO_SUPERSAMPLING
            ? 0
            : BaselineGrid.SIZE * BaselineGrid.SIZE * subPerAxis * subPerAxis;
        this.subKind = new byte[subCells];
        this.subSurfaceY = new int[subCells];
        this.subFluidDepth = new int[subCells];
    }
}
