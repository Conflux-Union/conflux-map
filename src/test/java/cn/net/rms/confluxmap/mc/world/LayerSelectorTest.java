package cn.net.rms.confluxmap.mc.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.MapLayer;
import org.junit.jupiter.api.Test;

class LayerSelectorTest {
    private static final int NETHER_LOGICAL_HEIGHT = 128;

    @Test
    void autoUsesCurrentLayerBelowTheBedrockCeiling() {
        assertEquals(
            MapLayer.NETHER_CURRENT,
            LayerSelector.resolveNether(ConfluxConfig.LayerOverride.AUTO, 127, NETHER_LOGICAL_HEIGHT)
        );
    }

    @Test
    void autoUsesTopDownRoofLayerAboveTheBedrockCeiling() {
        final MapLayer layer = LayerSelector.resolveNether(
            ConfluxConfig.LayerOverride.AUTO, 128, NETHER_LOGICAL_HEIGHT
        );

        assertEquals(MapLayer.NETHER_CEILING, layer);
        assertEquals(255, LayerSelector.pivotFor(layer, 256, 128));
    }

    @Test
    void manualRoofLayerRemainsAvailableBelowTheBedrockCeiling() {
        assertEquals(
            MapLayer.NETHER_CEILING,
            LayerSelector.resolveNether(ConfluxConfig.LayerOverride.FORCE_UNDERGROUND, 80, NETHER_LOGICAL_HEIGHT)
        );
    }
}
