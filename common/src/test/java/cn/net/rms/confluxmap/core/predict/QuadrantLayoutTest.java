package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.net.Proto;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link QuadrantLayout}'s mask is the one piece of code both the client underlay and the
 * companion residual baseline run, so its per-column contract decides whether quadra-gen
 * corrections stay near-empty: a flat quadrant's masked column must equal what the live summary
 * of that quadrant reports, and a cleared quadrant must leave nothing to predict.
 */
class QuadrantLayoutTest {
    /** (+X,+Z): one layer of stained glass on plains - renders as a translucent glass plane. */
    private static final FlatBaseline GLASS = new FlatBaseline(
        1, -60, SurfaceKind.LAND.ordinal(), 2, 0, "minecraft:white_stained_glass"
    );
    /** (-X,+Z): the_void flat with no layers - a flat quadrant that predicts nothing. */
    private static final FlatBaseline VOID_FLAT = new FlatBaseline(
        47, 0, SurfaceKind.VOID.ordinal(), Proto.MAP_COLOR_NONE, 0
    );

    /**
     * (+X,+Z) flat glass, (+X,-Z) cleared, (-X,+Z) void flat, (-X,-Z) ordinary noise - the
     * default quadra-gen layout transposed onto every style.
     */
    private static QuadrantLayout defaultLikeLayout() {
        return new QuadrantLayout(
            QuadrantLayout.Style.FLAT, GLASS,
            QuadrantLayout.Style.CLEARED, null,
            QuadrantLayout.Style.FLAT, VOID_FLAT,
            QuadrantLayout.Style.NOISE, null
        );
    }

    @Test
    void quadrantIndexFollowsTheSignOfBothAxesWithZeroPositive() {
        assertEquals(QuadrantLayout.X_POSITIVE_Z_POSITIVE, QuadrantLayout.quadrantIndex(0, 0));
        assertEquals(QuadrantLayout.X_POSITIVE_Z_POSITIVE, QuadrantLayout.quadrantIndex(5, 7));
        assertEquals(QuadrantLayout.X_POSITIVE_Z_NEGATIVE, QuadrantLayout.quadrantIndex(5, -7));
        assertEquals(QuadrantLayout.X_NEGATIVE_Z_POSITIVE, QuadrantLayout.quadrantIndex(-5, 7));
        assertEquals(QuadrantLayout.X_NEGATIVE_Z_NEGATIVE, QuadrantLayout.quadrantIndex(-5, -7));
    }

    @Test
    void allNoiseLayoutsSkipMaskingEntirely() {
        final QuadrantLayout allNoise = new QuadrantLayout(
            QuadrantLayout.Style.NOISE, null, QuadrantLayout.Style.NOISE, null,
            QuadrantLayout.Style.NOISE, null, QuadrantLayout.Style.NOISE, null
        );
        assertTrue(allNoise.uniformNoise());
        assertTrue(allNoise.predictsTerrainAt(-1, -1));
        assertNull(allNoise.apply(new BaselineGrid(0, 0, 0), new DerivedGrid()));
    }

    @Test
    void flatQuadrantColumnsBecomeTheUniformSurface() {
        final BaselineGrid grid = new BaselineGrid(1, -8, -8);
        final DerivedGrid derived = new DerivedGrid();
        fill(grid, derived);

        final QuadrantLayout.Mask mask = defaultLikeLayout().apply(grid, derived);

        // localX=10 -> blockX=12 (positive), localZ=10 -> blockZ=12 (positive): the glass quadrant.
        final int i = BaselineGrid.index(10, 10);
        assertEquals(1, grid.biomeId[i]);
        assertEquals(-60, grid.terrainY[i]);
        assertEquals(SurfaceKind.LAND.ordinal(), derived.kind[i] & 0xFF);
        assertEquals(-60, derived.surfaceY[i]);
        assertEquals(2, mask.mapColorOverrides()[10 * BaselineGrid.PIXELS + 10]);
        assertEquals("minecraft:white_stained_glass",
            mask.topMaterials()[QuadrantLayout.X_POSITIVE_Z_POSITIVE]);
        assertNull(mask.topMaterials()[QuadrantLayout.X_POSITIVE_Z_NEGATIVE],
            "only flat quadrants with a known top block declare a material");
        // A margin column of the same quadrant is masked too (relief reads it).
        final int margin = BaselineGrid.index(BaselineGrid.PIXELS, BaselineGrid.PIXELS);
        assertEquals(-60, grid.terrainY[margin]);
    }

    @Test
    void clearedQuadrantsKeepTheirBiomeButLoseTheirSurface() {
        final BaselineGrid grid = new BaselineGrid(1, -8, -8);
        final DerivedGrid derived = new DerivedGrid();
        fill(grid, derived);

        defaultLikeLayout().apply(grid, derived);

        // blockZ stays negative until localZ=4 (blockZ = -8 + 2*localZ); (10, 0) is (+X,-Z).
        final int i = BaselineGrid.index(10, 0);
        assertEquals(5, grid.biomeId[i], "cleared quadrants keep their real sampled biome");
        assertEquals(BaselineGrid.NO_SURFACE, grid.terrainY[i]);
        assertEquals(SurfaceKind.VOID.ordinal(), derived.kind[i] & 0xFF);
        assertEquals(0, derived.surfaceY[i]);
    }

    @Test
    void voidFlatQuadrantsMaskToTransparentWithoutAnOverride() {
        final BaselineGrid grid = new BaselineGrid(1, -8, -8);
        final DerivedGrid derived = new DerivedGrid();
        fill(grid, derived);

        final QuadrantLayout.Mask mask = defaultLikeLayout().apply(grid, derived);

        // (-X,+Z): blockX negative, blockZ non-negative - the empty the_void quadrant.
        final int i = BaselineGrid.index(0, 10);
        assertEquals(47, grid.biomeId[i], "the configured biome replaces the sampled one");
        assertEquals(SurfaceKind.VOID.ordinal(), derived.kind[i] & 0xFF);
        assertEquals(0, derived.surfaceY[i]);
        assertEquals(
            Proto.MAP_COLOR_NONE,
            mask.mapColorOverrides()[10 * BaselineGrid.PIXELS],
            "a void-flat quadrant's own pixels get no literal colour override"
        );
    }

    @Test
    void noiseQuadrantsKeepTheSampledColumnUntouched() {
        final BaselineGrid grid = new BaselineGrid(1, -8, -8);
        final DerivedGrid derived = new DerivedGrid();
        fill(grid, derived);

        defaultLikeLayout().apply(grid, derived);

        // (-X,-Z): the ordinary-noise quadrant.
        final int i = BaselineGrid.index(0, 0);
        assertEquals(5, grid.biomeId[i]);
        assertEquals(70, grid.terrainY[i]);
        assertEquals(SurfaceKind.LAND.ordinal(), derived.kind[i] & 0xFF);
        assertEquals(70, derived.surfaceY[i]);
    }

    @Test
    void supersampledSubColumnsFollowTheirOwnQuadrant() {
        final BaselineGrid grid = new BaselineGrid(2, -8, -8, 2);
        final DerivedGrid derived = new DerivedGrid(2);
        fill(grid, derived);
        for (int s = 0; s < grid.subBiomeId.length; s++) {
            grid.subBiomeId[s] = 5;
            derived.subKind[s] = (byte) SurfaceKind.LAND.ordinal();
            derived.subSurfaceY[s] = 70;
            derived.subFluidDepth[s] = 0;
        }

        defaultLikeLayout().apply(grid, derived);

        // Pixel 10 spans blocks 12..15 (all +X,+Z): every sub-sample becomes the glass surface.
        final int pixel = BaselineGrid.index(10, 10);
        for (int s = grid.subIndex(pixel, 0, 0);
             s <= grid.subIndex(pixel, grid.subPerAxis - 1, grid.subPerAxis - 1); s++) {
            assertEquals(1, grid.subBiomeId[s]);
            assertEquals(SurfaceKind.LAND.ordinal(), derived.subKind[s] & 0xFF);
            assertEquals(-60, derived.subSurfaceY[s]);
        }
    }

    @Test
    void biomeSlicesOnlyReplaceFlatQuadrantBiomes() {
        final BaselineGrid grid = new BaselineGrid(1, -8, -8);
        fill(grid, new DerivedGrid());

        defaultLikeLayout().applyBiomes(grid);

        assertEquals(1, grid.biomeId[BaselineGrid.index(10, 10)], "flat glass quadrant biome");
        assertEquals(47, grid.biomeId[BaselineGrid.index(0, 10)], "void flat quadrant biome");
        assertEquals(5, grid.biomeId[BaselineGrid.index(10, 0)], "cleared keeps the real biome");
        assertEquals(5, grid.biomeId[BaselineGrid.index(0, 0)], "noise keeps the real biome");
    }

    @Test
    void terrainPredictionIsOnlyClaimedInTheNoiseQuadrant() {
        final QuadrantLayout layout = defaultLikeLayout();
        assertTrue(layout.predictsTerrainAt(-1, -1));
        assertFalse(layout.predictsTerrainAt(1, 1));
        assertFalse(layout.predictsTerrainAt(1, -1));
        assertFalse(layout.predictsTerrainAt(-1, 1));
    }

    private static void fill(final BaselineGrid grid, final DerivedGrid derived) {
        java.util.Arrays.fill(grid.biomeId, 5);
        java.util.Arrays.fill(grid.terrainY, 70);
        java.util.Arrays.fill(grid.baseSurfaceY, 70);
        java.util.Arrays.fill(derived.kind, (byte) SurfaceKind.LAND.ordinal());
        java.util.Arrays.fill(derived.surfaceY, 70);
        java.util.Arrays.fill(derived.fluidDepth, 0);
    }
}
