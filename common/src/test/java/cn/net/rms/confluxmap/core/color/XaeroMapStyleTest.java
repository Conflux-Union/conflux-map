package cn.net.rms.confluxmap.core.color;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.confluxmap.core.model.DimensionId;
import org.junit.jupiter.api.Test;

class XaeroMapStyleTest {
    @Test
    void defaultThreeDimensionalSlopeUsesTheObservedDirectionalLightCurve() {
        final int base = 0xFF806040;

        assertEquals(
            0xFF896A4A,
            XaeroMapStyle.applyTerrain(base, 63, 63, 63, 1, true, XaeroMapStyle.Shadow.OVERWORLD),
            "flat terrain keeps Xaero's bright north-west-facing ambient/direct-light balance"
        );
        assertEquals(
            0xFFA27D57,
            XaeroMapStyle.applyTerrain(base, 64, 63, 63, 1, true, XaeroMapStyle.Shadow.OVERWORLD),
            "a one-block rise toward the north and north-west receives maximum direct light"
        );
        assertEquals(
            0xFF4C3C2C,
            XaeroMapStyle.applyTerrain(base, 62, 63, 63, 1, true, XaeroMapStyle.Shadow.OVERWORLD),
            "a one-block fall receives ambient light only"
        );
    }

    @Test
    void dimensionShadowTintsMatchTheObservedDefaults() {
        final int base = 0xFF808080;

        assertEquals(
            0xFF898D95,
            XaeroMapStyle.applyTerrain(base, 63, 63, 63, 1, true, XaeroMapStyle.Shadow.OVERWORLD)
        );
        assertEquals(
            0xFF957C7C,
            XaeroMapStyle.applyTerrain(base, 63, 63, 63, 1, true, XaeroMapStyle.Shadow.NETHER)
        );
        assertEquals(
            0xFF959595,
            XaeroMapStyle.applyTerrain(base, 63, 63, 63, 1, true, XaeroMapStyle.Shadow.END)
        );
        assertEquals(XaeroMapStyle.Shadow.OVERWORLD, XaeroMapStyle.shadowFor(DimensionId.OVERWORLD));
        assertEquals(XaeroMapStyle.Shadow.NETHER, XaeroMapStyle.shadowFor(DimensionId.NETHER));
        assertEquals(XaeroMapStyle.Shadow.END, XaeroMapStyle.shadowFor(DimensionId.END));
    }

    @Test
    void terrainDepthOnlyDarkensLowGroundWithinTheObservedBounds() {
        assertEquals(0.9, XaeroMapStyle.terrainDepthMultiplier(-64), 1.0e-7);
        assertEquals(0.9, XaeroMapStyle.terrainDepthMultiplier(56), 1.0e-7);
        assertEquals(60.0 / 63.0, XaeroMapStyle.terrainDepthMultiplier(60), 1.0e-7);
        assertEquals(1.0, XaeroMapStyle.terrainDepthMultiplier(63), 1.0e-7);
        assertEquals(1.0, XaeroMapStyle.terrainDepthMultiplier(192), 1.0e-7);
    }

    @Test
    void transparentDepthUsesXaerosFifteenLevelLightBlockingFloor() {
        assertEquals(1.0f, XaeroMapStyle.transparentFloorBrightness(0));
        assertEquals(23f / 24f, XaeroMapStyle.transparentFloorBrightness(1));
        assertEquals(0.5f, XaeroMapStyle.transparentFloorBrightness(12));
        assertEquals(0.375f, XaeroMapStyle.transparentFloorBrightness(15));
        assertEquals(0.375f, XaeroMapStyle.transparentFloorBrightness(80));
    }

    @Test
    void daylightAndBlockLightUseXaerosShaderInputs() {
        assertEquals(0.375f, XaeroMapStyle.daylightScale(0f, 0));
        assertEquals(10f / 24f, XaeroMapStyle.daylightScale(0f, 1));
        assertEquals(0.6875f, XaeroMapStyle.daylightScale(0.62f, 0));
        assertEquals(1.0f, XaeroMapStyle.daylightScale(1f, 0));
        assertEquals(1.0f, XaeroMapStyle.daylightScale(0f, 15));
        assertEquals(
            XaeroMapStyle.daylightScale(0.8f, 0) / XaeroMapStyle.daylightScale(0.4f, 0),
            ShadingPipeline.relightRatios(0.4f, 0.8f, MapColorStyle.XAERO)[0]
        );
    }
}
