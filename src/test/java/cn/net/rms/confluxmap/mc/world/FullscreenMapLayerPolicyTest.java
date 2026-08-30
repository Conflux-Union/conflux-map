package cn.net.rms.confluxmap.mc.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import org.junit.jupiter.api.Test;

class FullscreenMapLayerPolicyTest {
    @Test
    void liveNetherKeepsTheSelectedHeightPreset() {
        assertEquals(
            MapLayer.netherSlice(64),
            FullscreenMapLayerPolicy.select(
                DimensionId.NETHER, true, MapLayer.netherSlice(64)
            )
        );
    }

    @Test
    void archivedNetherUsesItsPersistentBedrockRoof() {
        assertEquals(
            MapLayer.NETHER_CEILING,
            FullscreenMapLayerPolicy.select(
                DimensionId.NETHER, false, MapLayer.netherSlice(64)
            )
        );
    }

    @Test
    void archivedDimensionsUseTheirPersistentTopSurface() {
        assertEquals(
            MapLayer.SURFACE,
            FullscreenMapLayerPolicy.select(
                DimensionId.OVERWORLD, false, MapLayer.CAVE_AUTO
            )
        );
        assertEquals(
            MapLayer.END_SURFACE,
            FullscreenMapLayerPolicy.select(
                DimensionId.END, false, MapLayer.CAVE_AUTO
            )
        );
    }

    @Test
    void liveOverworldKeepsThePlayersChosenLayer() {
        assertEquals(
            MapLayer.CAVE_AUTO,
            FullscreenMapLayerPolicy.select(
                DimensionId.OVERWORLD, true, MapLayer.CAVE_AUTO
            )
        );
    }

    @Test
    void lowerNetherPredictionIsAvailableOnlyForBiomeMode() {
        assertTrue(FullscreenMapLayerPolicy.predictionAllowed(
            DimensionId.NETHER, MapLayer.NETHER_CURRENT, true
        ));
        assertTrue(FullscreenMapLayerPolicy.predictionAllowed(
            DimensionId.NETHER, MapLayer.netherSlice(48), true
        ));
        assertFalse(FullscreenMapLayerPolicy.predictionAllowed(
            DimensionId.NETHER, MapLayer.NETHER_CURRENT, false
        ));
    }

    @Test
    void heightLayersSampleBiomesAtTheirDebouncedPivot() {
        assertEquals(
            37,
            FullscreenMapLayerPolicy.biomeSampleY(MapLayer.netherSlice(37), 90, 37)
        );
        assertEquals(
            84,
            FullscreenMapLayerPolicy.biomeSampleY(MapLayer.NETHER_CURRENT, 90, 84)
        );
        assertEquals(
            90,
            FullscreenMapLayerPolicy.biomeSampleY(MapLayer.SURFACE, 90, 0)
        );
    }
}
