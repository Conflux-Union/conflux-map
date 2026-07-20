package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.confluxmap.core.model.SurfaceKind;
import org.junit.jupiter.api.Test;

/** Truth table for {@link BaselineDeriver}'s water rule and its fluidDepth clamp. */
class BaselineDeriverTest {
    private static final int OCEAN = 0;
    private static final int PLAINS = 1;
    private static final int MOUNTAINS = 3;
    private static final int FROZEN_OCEAN = 10;
    private static final int SNOWY_TUNDRA = 12;

    private static DerivedGrid deriveOne(final int biomeId, final int terrainY) {
        final BaselineGrid grid = new BaselineGrid();
        final int idx = BaselineGrid.index(0, 0);
        grid.biomeId[idx] = biomeId;
        grid.terrainY[idx] = terrainY;
        return BaselineDeriver.derive(grid);
    }

    @Test
    void oceanicBiomeBelowSeaLevelBecomesWaterAtSeaLevel() {
        final DerivedGrid derived = deriveOne(OCEAN, 40);
        final int idx = BaselineGrid.index(0, 0);
        assertEquals(SurfaceKind.WATER.ordinal(), derived.kind[idx]);
        assertEquals(BaselineDeriver.WATER_LEVEL, derived.surfaceY[idx]);
        assertEquals(22, derived.fluidDepth[idx]);
    }

    @Test
    void frozenOceanBelowSeaLevelBecomesIceAtSeaLevel() {
        final DerivedGrid derived = deriveOne(FROZEN_OCEAN, 40);
        final int idx = BaselineGrid.index(0, 0);

        assertEquals(SurfaceKind.ICE.ordinal(), derived.kind[idx]);
        assertEquals(BaselineDeriver.WATER_LEVEL, derived.surfaceY[idx]);
        assertEquals(22, derived.fluidDepth[idx]);
    }

    @Test
    void oceanicBiomeAtOrAboveSeaLevelIsNotWater() {
        final DerivedGrid derived = deriveOne(OCEAN, 70);
        final int idx = BaselineGrid.index(0, 0);
        assertEquals(BiomeTable.get(OCEAN).kind().ordinal(), derived.kind[idx]);
        assertEquals(70, derived.surfaceY[idx]);
        assertEquals(0, derived.fluidDepth[idx]);
    }

    @Test
    void nonOceanicBiomeBelowSeaLevelStaysLand() {
        final DerivedGrid derived = deriveOne(PLAINS, 40);
        final int idx = BaselineGrid.index(0, 0);
        assertEquals(SurfaceKind.LAND.ordinal(), derived.kind[idx]);
        assertEquals(40, derived.surfaceY[idx]);
        assertEquals(0, derived.fluidDepth[idx]);
    }

    @Test
    void snowyBiomeGivesSnowKind() {
        final DerivedGrid derived = deriveOne(SNOWY_TUNDRA, 70);
        assertEquals(SurfaceKind.SNOW.ordinal(), derived.kind[BaselineGrid.index(0, 0)]);
    }

    @Test
    void coldMountainSurfaceBecomesSnowCoveredAtHighAltitude() {
        final DerivedGrid low = deriveOne(MOUNTAINS, 80);
        final DerivedGrid high = deriveOne(MOUNTAINS, 120);
        final int idx = BaselineGrid.index(0, 0);

        assertEquals(SurfaceKind.LAND.ordinal(), low.kind[idx]);
        assertEquals(SurfaceKind.SNOW.ordinal(), high.kind[idx]);
    }

    @Test
    void fluidDepthClampsAtByteMax() {
        final DerivedGrid derived = deriveOne(OCEAN, -250);
        assertEquals(255, derived.fluidDepth[BaselineGrid.index(0, 0)]);
    }

    @Test
    void voidSentinelBecomesVoidKind() {
        final DerivedGrid derived = deriveOne(OCEAN, BaselineGrid.NO_SURFACE);
        assertEquals(SurfaceKind.VOID.ordinal(), derived.kind[BaselineGrid.index(0, 0)]);
    }
}
