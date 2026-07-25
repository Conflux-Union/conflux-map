package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.confluxmap.core.model.SurfaceKind;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Truth table for {@link BaselineDeriver}'s water rule and its fluidDepth clamp. */
class BaselineDeriverTest {
    private static final int OCEAN = 0;
    private static final int PLAINS = 1;
    private static final int THE_END = 9;
    private static final int MOUNTAINS = 3;
    private static final int FROZEN_OCEAN = 10;
    private static final int DEEP_FROZEN_OCEAN = 50;
    private static final int SNOWY_TUNDRA = 12;

    private static DerivedGrid deriveOne(final int biomeId, final int terrainY) {
        final BaselineGrid grid = new BaselineGrid();
        final int idx = BaselineGrid.index(0, 0);
        grid.biomeId[idx] = biomeId;
        grid.terrainY[idx] = terrainY;
        grid.baseSurfaceY[idx] = terrainY;
        if (terrainY < BaselineDeriver.WATER_LEVEL && !BiomeTable.isEnd(biomeId)) {
            grid.fluidY[idx] = BaselineDeriver.WATER_LEVEL;
            grid.baseSurfaceY[idx] = BaselineDeriver.WATER_LEVEL;
            grid.surfaceFlags[idx] = BaselineGrid.SURFACE_FLUID;
        }
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
    void frozenOceanTemperatureCreatesIceAndWaterWhileDeepFrozenOceanStaysWater() {
        final BaselineGrid frozen = new BaselineGrid(0, 0, 8192);
        Arrays.fill(frozen.biomeId, FROZEN_OCEAN);
        Arrays.fill(frozen.terrainY, 40);
        Arrays.fill(frozen.fluidY, BaselineDeriver.WATER_LEVEL);
        Arrays.fill(frozen.baseSurfaceY, BaselineDeriver.WATER_LEVEL);
        Arrays.fill(frozen.surfaceFlags, BaselineGrid.SURFACE_FLUID);
        final DerivedGrid frozenDerived = BaselineDeriver.derive(frozen);
        int ice = 0;
        int water = 0;
        for (int z = 0; z < BaselineGrid.PIXELS; z++) {
            for (int x = 0; x < BaselineGrid.PIXELS; x++) {
                final int kind = frozenDerived.kind[BaselineGrid.index(x, z)];
                ice += kind == SurfaceKind.ICE.ordinal() ? 1 : 0;
                water += kind == SurfaceKind.WATER.ordinal() ? 1 : 0;
            }
        }

        assertEquals(31_631, ice, "fixed vanilla temperature noise should preserve its ice mask");
        assertEquals(33_905, water, "fixed vanilla temperature noise should preserve its warm water mask");

        final BaselineGrid deepFrozen = new BaselineGrid(0, 0, 8192);
        Arrays.fill(deepFrozen.biomeId, DEEP_FROZEN_OCEAN);
        Arrays.fill(deepFrozen.terrainY, 40);
        Arrays.fill(deepFrozen.fluidY, BaselineDeriver.WATER_LEVEL);
        Arrays.fill(deepFrozen.baseSurfaceY, BaselineDeriver.WATER_LEVEL);
        Arrays.fill(deepFrozen.surfaceFlags, BaselineGrid.SURFACE_FLUID);
        final DerivedGrid deepDerived = BaselineDeriver.derive(deepFrozen);
        for (int z = 0; z < BaselineGrid.PIXELS; z++) {
            for (int x = 0; x < BaselineGrid.PIXELS; x++) {
                assertEquals(
                    SurfaceKind.WATER.ordinal(),
                    deepDerived.kind[BaselineGrid.index(x, z)],
                    "deep frozen ocean's base surface stays water; icebergs are a separate feature"
                );
            }
        }
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
    void overworldLandBiomeBelowSeaLevelUsesTheDefaultWaterFluid() {
        final DerivedGrid derived = deriveOne(PLAINS, 40);
        final int idx = BaselineGrid.index(0, 0);
        assertEquals(SurfaceKind.WATER.ordinal(), derived.kind[idx]);
        assertEquals(BaselineDeriver.WATER_LEVEL, derived.surfaceY[idx]);
        assertEquals(22, derived.fluidDepth[idx]);

        final BaselineGrid unresolved = new BaselineGrid();
        unresolved.biomeId[idx] = PLAINS;
        unresolved.terrainY[idx] = 40;
        final DerivedGrid compatibility = BaselineDeriver.derive(unresolved);
        assertEquals(SurfaceKind.WATER.ordinal(), compatibility.kind[idx]);
        assertEquals(BaselineDeriver.WATER_LEVEL, compatibility.surfaceY[idx]);
    }

    @Test
    void endTerrainBelowOverworldSeaLevelStaysLand() {
        final DerivedGrid derived = deriveOne(THE_END, 40);
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
    void snowyBiomeBelowSeaLevelFreezesTheDefaultWaterSurface() {
        final DerivedGrid derived = deriveOne(SNOWY_TUNDRA, 40);
        final int idx = BaselineGrid.index(0, 0);
        assertEquals(SurfaceKind.ICE.ordinal(), derived.kind[idx]);
        assertEquals(BaselineDeriver.WATER_LEVEL, derived.surfaceY[idx]);
        assertEquals(22, derived.fluidDepth[idx]);
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
