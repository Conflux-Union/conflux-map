package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.net.Proto;
import java.util.Arrays;

/**
 * A dimension whose world generation differs by quadrant of the X/Z axes - the shape produced by
 * quadra-gen, which routes each chunk ({@code x >= 0}/{@code z >= 0} quadrants) to one of four
 * configured generators. Detected by whichever side owns the world ({@code
 * server.QuadraGenLayouts}) and carried to multiplayer clients in {@code QUADRA_LAYOUT_S2C}.
 *
 * <p>Each quadrant holds one {@link Style}:
 * <ul>
 *   <li>{@link Style#NOISE} - vanilla natural terrain; the seeded cubiomes path is correct there
 *       and the sampled column is left untouched.</li>
 *   <li>{@link Style#CLEARED} - vanilla biomes and structure data, but no generated content. The
 *       explored map shows nothing, so the mask clears the predicted surface to {@code VOID} while
 *       keeping the (real) sampled biome.</li>
 *   <li>{@link Style#FLAT} - a uniform configured surface; every masked column becomes that
 *       {@link FlatBaseline}, exactly like a whole-dimension superflat. A flat quadrant with no
 *       layers carries a {@code VOID}-kind baseline and predicts nothing.</li>
 * </ul>
 *
 * <p>The mask is applied <em>after</em> {@link BaselineDeriver}+{@link CanopyStylizer} and before
 * composition, on both the client underlay ({@code PredictionTileService#composeTile}) and the
 * companion's residual baseline ({@code PatchBuilder}) - the same static code, so the two sides
 * keep producing bit-identical columns and residual patches stay near-empty in flat quadrants.
 */
public final class QuadrantLayout {
    /** Wire/model order of the four quadrants; must match quadra-gen's {@code Quadrant} enum. */
    public static final int X_POSITIVE_Z_POSITIVE = 0;
    public static final int X_POSITIVE_Z_NEGATIVE = 1;
    public static final int X_NEGATIVE_Z_POSITIVE = 2;
    public static final int X_NEGATIVE_Z_NEGATIVE = 3;

    /** How one quadrant's columns predict. */
    public enum Style {
        NOISE,
        CLEARED,
        FLAT,
    }

    /** Quadrant index of one block coordinate; {@code 0} on both axes belongs to {@code ++}. */
    public static int quadrantIndex(final int x, final int z) {
        return (x >= 0 ? 0 : 2) + (z >= 0 ? 0 : 1);
    }

    private final Style[] styles = new Style[4];
    /** Baseline per quadrant; non-null exactly where the style is {@link Style#FLAT}. */
    private final FlatBaseline[] flats = new FlatBaseline[4];
    private final boolean uniformNoise;

    public QuadrantLayout(
        final Style stylePP, final FlatBaseline flatPP,
        final Style stylePN, final FlatBaseline flatPN,
        final Style styleNP, final FlatBaseline flatNP,
        final Style styleNN, final FlatBaseline flatNN
    ) {
        set(X_POSITIVE_Z_POSITIVE, stylePP, flatPP);
        set(X_POSITIVE_Z_NEGATIVE, stylePN, flatPN);
        set(X_NEGATIVE_Z_POSITIVE, styleNP, flatNP);
        set(X_NEGATIVE_Z_NEGATIVE, styleNN, flatNN);
        boolean noise = true;
        for (final Style style : styles) {
            noise &= style == Style.NOISE;
        }
        uniformNoise = noise;
    }

    private void set(final int quadrant, final Style style, final FlatBaseline flat) {
        if (style == null) {
            throw new IllegalArgumentException("quadrant " + quadrant + " has no style");
        }
        if (style == Style.FLAT ? flat == null : flat != null) {
            throw new IllegalArgumentException(
                "quadrant " + quadrant + " style " + style + " mismatches its baseline"
            );
        }
        styles[quadrant] = style;
        flats[quadrant] = flat;
    }

    public Style style(final int quadrant) {
        return styles[quadrant];
    }

    /** The uniform surface of a {@link Style#FLAT} quadrant; {@code null} otherwise. */
    public FlatBaseline flat(final int quadrant) {
        return flats[quadrant];
    }

    public Style styleAt(final int x, final int z) {
        return styles[quadrantIndex(x, z)];
    }

    /** Whether cubiomes terrain (and structure markers) are valid at one block coordinate. */
    public boolean predictsTerrainAt(final int x, final int z) {
        return styleAt(x, z) == Style.NOISE;
    }

    /** {@code true} when no quadrant needs masking and every apply is a no-op. */
    public boolean uniformNoise() {
        return uniformNoise;
    }

    /**
     * Result of masking sampled grids: the per-output-pixel {@code baselineMapColorId} overrides a
     * flat quadrant's top block introduces ({@link Proto#MAP_COLOR_NONE} where the scalar still
     * applies), or {@code null} when no flat quadrant paints a literal map color. {@code
     * topMaterials} carries each quadrant's {@link FlatBaseline#topMaterialId()} ({@code null}
     * where the quadrant is not flat or has no material) so composition can paint translucent tops
     * with their own sampled colour.
     */
    public record Mask(int[] mapColorOverrides, String[] topMaterials) {
    }

    /**
     * Overwrites every margin-inclusive column of {@code grid}/{@code derived} that falls in a
     * non-noise quadrant. Runs after derivation and canopy so synthetic trees cannot survive in a
     * cleared or flat quadrant.
     */
    public Mask apply(final BaselineGrid grid, final DerivedGrid derived) {
        return applyWindow(
            grid, derived,
            -BaselineGrid.MARGIN, -BaselineGrid.MARGIN,
            BaselineGrid.PIXELS - 1 + BaselineGrid.MARGIN,
            BaselineGrid.PIXELS - 1 + BaselineGrid.MARGIN
        );
    }

    /** Windowed form used by the companion's cropped region-page baselines. */
    public Mask applyWindow(
        final BaselineGrid grid,
        final DerivedGrid derived,
        final int minPixelX,
        final int minPixelZ,
        final int maxPixelX,
        final int maxPixelZ
    ) {
        if (uniformNoise) {
            return null;
        }
        int[] overrides = null;
        String[] topMaterials = null;
        for (int localZ = minPixelZ; localZ <= maxPixelZ; localZ++) {
            for (int localX = minPixelX; localX <= maxPixelX; localX++) {
                final int i = BaselineGrid.index(localX, localZ);
                final int quadrant = quadrantIndex(grid.blockX(localX), grid.blockZ(localZ));
                final Style style = styles[quadrant];
                if (style == Style.NOISE) {
                    continue;
                }
                final FlatBaseline flat = flats[quadrant];
                if (style == Style.CLEARED) {
                    // Biomes stay real in a cleared quadrant; only the surface goes away.
                    grid.terrainY[i] = BaselineGrid.NO_SURFACE;
                    grid.baseSurfaceY[i] = BaselineGrid.NO_SURFACE;
                    grid.fluidY[i] = BaselineGrid.NO_FLUID;
                    grid.surfaceFlags[i] = 0;
                    derived.kind[i] = (byte) SurfaceKind.VOID.ordinal();
                    derived.surfaceY[i] = 0;
                    derived.fluidDepth[i] = 0;
                } else {
                    applyFlatColumn(grid, derived, i, flat);
                    if (inOutputPixels(localX) && inOutputPixels(localZ)
                        && flat.mapColorId() != Proto.MAP_COLOR_NONE
                        && flat.kind() != SurfaceKind.VOID.ordinal()) {
                        if (overrides == null) {
                            overrides = new int[BaselineGrid.PIXELS * BaselineGrid.PIXELS];
                            Arrays.fill(overrides, Proto.MAP_COLOR_NONE);
                        }
                        overrides[localZ * BaselineGrid.PIXELS + localX] = flat.mapColorId();
                    }
                    if (!flat.topMaterialId().isEmpty()) {
                        if (topMaterials == null) {
                            topMaterials = new String[4];
                        }
                        topMaterials[quadrant] = flat.topMaterialId();
                    }
                }
                if (!grid.supersampled()) {
                    continue;
                }
                for (int sz = 0; sz < grid.subPerAxis; sz++) {
                    for (int sx = 0; sx < grid.subPerAxis; sx++) {
                        final int s = grid.subIndex(i, sx, sz);
                        if (style == Style.CLEARED) {
                            derived.subKind[s] = (byte) SurfaceKind.VOID.ordinal();
                            derived.subSurfaceY[s] = 0;
                            derived.subFluidDepth[s] = 0;
                        } else {
                            grid.subBiomeId[s] = flat.biomeId();
                            grid.subBaseSurfaceY[s] = BaselineGrid.NO_SURFACE;
                            grid.subSurfaceFlags[s] = 0;
                            derived.subKind[s] = (byte) flat.kind();
                            derived.subSurfaceY[s] = flat.surfaceY();
                            derived.subFluidDepth[s] = flat.fluidDepth();
                        }
                    }
                }
            }
        }
        return overrides == null && topMaterials == null
            ? null : new Mask(overrides, topMaterials);
    }

    private static boolean inOutputPixels(final int local) {
        return local >= 0 && local < BaselineGrid.PIXELS;
    }

    /**
     * One uniform column, matching what {@link FlatBaseline#toBaselineGrid}/
     * {@link FlatBaseline#toDerivedGrid} would produce for a whole superflat dimension.
     */
    private static void applyFlatColumn(
        final BaselineGrid grid,
        final DerivedGrid derived,
        final int i,
        final FlatBaseline flat
    ) {
        grid.biomeId[i] = flat.biomeId();
        grid.terrainY[i] = flat.surfaceY();
        grid.baseSurfaceY[i] = BaselineGrid.NO_SURFACE;
        grid.fluidY[i] = BaselineGrid.NO_FLUID;
        grid.surfaceFlags[i] = 0;
        derived.kind[i] = (byte) flat.kind();
        derived.surfaceY[i] = flat.surfaceY();
        derived.fluidDepth[i] = flat.fluidDepth();
    }

    /**
     * Mask for biome-identity tiles ({@code sampleNetherBiomesAtY} slices): only flat quadrants
     * carry a configured uniform biome; cleared and noise quadrants keep their real sampled
     * biomes.
     */
    public void applyBiomes(final BaselineGrid grid) {
        if (uniformNoise) {
            return;
        }
        for (int localZ = -BaselineGrid.MARGIN;
             localZ < BaselineGrid.PIXELS + BaselineGrid.MARGIN; localZ++) {
            for (int localX = -BaselineGrid.MARGIN;
                 localX < BaselineGrid.PIXELS + BaselineGrid.MARGIN; localX++) {
                final int i = BaselineGrid.index(localX, localZ);
                final int quadrant = quadrantIndex(grid.blockX(localX), grid.blockZ(localZ));
                if (styles[quadrant] != Style.FLAT) {
                    continue;
                }
                final int biomeId = flats[quadrant].biomeId();
                grid.biomeId[i] = biomeId;
                if (!grid.supersampled()) {
                    continue;
                }
                for (int sz = 0; sz < grid.subPerAxis; sz++) {
                    for (int sx = 0; sx < grid.subPerAxis; sx++) {
                        grid.subBiomeId[grid.subIndex(i, sx, sz)] = biomeId;
                    }
                }
            }
        }
    }
}
