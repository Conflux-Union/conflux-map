package cn.net.rms.confluxmap.mc.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
