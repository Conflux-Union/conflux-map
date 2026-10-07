package cn.net.rms.confluxmap.mc.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import org.junit.jupiter.api.Test;

class DimensionLayerPolicyTest {
    @Test
    void theEndClassifiesByIdentityWhateverItsSkyLightMetadataClaims() {
        // 1.21.9+ flipped the_end's has_skylight to true; identity must keep winning either way.
        assertEquals(
            DimensionLayerPolicy.DimensionKind.NO_SKY_NO_CEILING,
            DimensionLayerPolicy.classify(DimensionId.END, false, false)
        );
        assertEquals(
            DimensionLayerPolicy.DimensionKind.NO_SKY_NO_CEILING,
            DimensionLayerPolicy.classify(DimensionId.END, false, true)
        );
    }

    @Test
    void vanillaNetherAndOverworldClassifyByTheirMetadata() {
        assertEquals(
            DimensionLayerPolicy.DimensionKind.HAS_CEILING,
            DimensionLayerPolicy.classify(DimensionId.NETHER, true, false)
        );
        assertEquals(
            DimensionLayerPolicy.DimensionKind.SKY_LIT,
            DimensionLayerPolicy.classify(DimensionId.OVERWORLD, false, true)
        );
    }

    @Test
    void unknownDimensionsFallBackToMetadataClassification() {
        assertEquals(
            DimensionLayerPolicy.DimensionKind.HAS_CEILING,
            DimensionLayerPolicy.classify(DimensionId.of("mod", "roof_world"), true, true)
        );
        assertEquals(
            DimensionLayerPolicy.DimensionKind.NO_SKY_NO_CEILING,
            DimensionLayerPolicy.classify(DimensionId.of("mod", "dark_world"), false, false)
        );
        assertEquals(
            DimensionLayerPolicy.DimensionKind.SKY_LIT,
            DimensionLayerPolicy.classify(DimensionId.of("mod", "sky_world"), false, true)
        );
    }

    @Test
    void theEndAlwaysRendersAsItsSingleSurfaceLayer() {
        assertEquals(
            MapLayer.END_SURFACE,
            DimensionLayerPolicy.layerFor(DimensionLayerPolicy.DimensionKind.NO_SKY_NO_CEILING)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> DimensionLayerPolicy.layerFor(DimensionLayerPolicy.DimensionKind.SKY_LIT)
        );
    }
}
