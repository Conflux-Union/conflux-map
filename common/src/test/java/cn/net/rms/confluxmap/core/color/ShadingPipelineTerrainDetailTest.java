package cn.net.rms.confluxmap.core.color;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ShadingPipelineTerrainDetailTest {
    @Test
    void directionalReliefIsContinuousAndLodNormalized() {
        assertEquals(1.0, ShadingPipeline.directionalReliefMultiplier(
            80, 80, 80, 80, 80, 80, 1
        ));
        assertEquals(1.15, ShadingPipeline.directionalReliefMultiplier(
            80, 80, 80, 79, 79, 79, 1
        ), 1.0e-9, "a half-block-per-block rise should use half the available contrast");
        assertEquals(1.30, ShadingPipeline.directionalReliefMultiplier(
            81, 81, 81, 79, 79, 79, 1
        ), 1.0e-9);
        assertEquals(0.70, ShadingPipeline.directionalReliefMultiplier(
            79, 79, 79, 81, 81, 81, 1
        ), 1.0e-9);
        assertEquals(1.30, ShadingPipeline.directionalReliefMultiplier(
            84, 84, 84, 76, 76, 76, 4
        ), 1.0e-9, "the same world-space slope must keep its contrast at a coarser LOD");
    }

    @Test
    void directionalReliefFallsBackToNeutralWhenItsStencilIsIncomplete() {
        assertEquals(1.0, ShadingPipeline.directionalReliefMultiplier(
            null, 80, 80, 80, 80, 80, 1
        ));
    }

    @Test
    void detailedHeightShadeRetainsTheCurveAtReducedStrength() {
        assertEquals(
            ShadingPipeline.heightShade(160, ShadingPipeline.REFERENCE_HEIGHT, true) * 0.65,
            ShadingPipeline.detailedHeightShade(160, ShadingPipeline.REFERENCE_HEIGHT),
            1.0e-12
        );
    }

    @Test
    void seafloorBrightnessDarkensOverFortyEightBlocksWithAReadableFloor() {
        assertEquals(1.0f, ShadingPipeline.seafloorBrightness(0));
        assertEquals(0.5f, ShadingPipeline.seafloorBrightness(24));
        assertEquals(0.25f, ShadingPipeline.seafloorBrightness(48));
        assertEquals(0.25f, ShadingPipeline.seafloorBrightness(120));
    }
}
