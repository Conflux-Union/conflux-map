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
            LayerSelector.resolveNether(
                ConfluxConfig.LayerOverride.AUTO, 127, NETHER_LOGICAL_HEIGHT, 64
            )
        );
    }

    @Test
    void autoUsesTopDownRoofLayerAboveTheBedrockCeiling() {
        final MapLayer layer = LayerSelector.resolveNether(
            ConfluxConfig.LayerOverride.AUTO, 128, NETHER_LOGICAL_HEIGHT, 64
        );

        assertEquals(MapLayer.NETHER_CEILING, layer);
        assertEquals(255, LayerSelector.pivotFor(layer, 256, 128));
    }

    @Test
    void manualRoofLayerRemainsAvailableBelowTheBedrockCeiling() {
        assertEquals(
            MapLayer.NETHER_CEILING,
            LayerSelector.resolveNether(
                ConfluxConfig.LayerOverride.FORCE_UNDERGROUND, 80, NETHER_LOGICAL_HEIGHT, 64
            )
        );
    }

    @Test
    void fixedNetherPresetUsesTheConfiguredHeightBelowTheBedrockCeiling() {
        final MapLayer layer = LayerSelector.resolveNether(
            ConfluxConfig.LayerOverride.FORCE_SLICE, 80, NETHER_LOGICAL_HEIGHT, 42
        );

        assertEquals(MapLayer.netherSlice(42), layer);
        assertEquals(42, LayerSelector.pivotFor(layer, 256, 80));
    }

    @Test
    void netherCyclesCurrentRoofAndBelowBedrockPresets() {
        assertEquals(
            ConfluxConfig.LayerOverride.FORCE_UNDERGROUND,
            LayerSelector.nextOverride(
                LayerSelector.DimensionKind.HAS_CEILING, ConfluxConfig.LayerOverride.AUTO
            )
        );
        assertEquals(
            ConfluxConfig.LayerOverride.FORCE_SLICE,
            LayerSelector.nextOverride(
                LayerSelector.DimensionKind.HAS_CEILING,
                ConfluxConfig.LayerOverride.FORCE_UNDERGROUND
            )
        );
        assertEquals(
            ConfluxConfig.LayerOverride.AUTO,
            LayerSelector.nextOverride(
                LayerSelector.DimensionKind.HAS_CEILING,
                ConfluxConfig.LayerOverride.FORCE_SLICE
            )
        );
    }

    @Test
    void overworldCyclesAutomaticSurfaceCurrentCaveAndFixedHeightPresets() {
        assertEquals(
            ConfluxConfig.LayerOverride.FORCE_SURFACE,
            LayerSelector.nextOverride(
                LayerSelector.DimensionKind.SKY_LIT, ConfluxConfig.LayerOverride.AUTO
            )
        );
        assertEquals(
            ConfluxConfig.LayerOverride.FORCE_UNDERGROUND,
            LayerSelector.nextOverride(
                LayerSelector.DimensionKind.SKY_LIT,
                ConfluxConfig.LayerOverride.FORCE_SURFACE
            )
        );
        assertEquals(
            ConfluxConfig.LayerOverride.FORCE_SLICE,
            LayerSelector.nextOverride(
                LayerSelector.DimensionKind.SKY_LIT,
                ConfluxConfig.LayerOverride.FORCE_UNDERGROUND
            )
        );
        assertEquals(
            ConfluxConfig.LayerOverride.AUTO,
            LayerSelector.nextOverride(
                LayerSelector.DimensionKind.SKY_LIT,
                ConfluxConfig.LayerOverride.FORCE_SLICE
            )
        );
    }
}
