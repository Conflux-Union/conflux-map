package cn.net.rms.confluxmap.core.predict;

/**
 * {@link BaselineGrid}'s biome/height pair turned into per-pixel render data by {@link
 * BaselineDeriver} (water/land classification) and then {@link CanopyStylizer} (tree
 * blobs). Same size and indexing as {@link BaselineGrid} ({@link BaselineGrid#index}) so a
 * slope-shading neighbor lookup at a tile's own west/south margin reads the matching cell here.
 */
public final class DerivedGrid {
    /** Final surface Y (after the water rule's flatten-to-sea-level and canopy's height bump). */
    public final int[] surfaceY = new int[BaselineGrid.SIZE * BaselineGrid.SIZE];
    /** {@link cn.net.rms.confluxmap.core.model.SurfaceKind} ordinal. */
    public final byte[] kind = new byte[BaselineGrid.SIZE * BaselineGrid.SIZE];
    /** 0-255, only meaningful where {@link #kind} is WATER. */
    public final int[] fluidDepth = new int[BaselineGrid.SIZE * BaselineGrid.SIZE];
}
