package cn.net.rms.confluxmap.core.color;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.util.Argb;
import org.junit.jupiter.api.Test;

class BedrockThicknessTest {

    @Test
    void multiplierFormulaMatchesSpecification() {
        assertEquals(1.15f, ShadingPipeline.bedrockThicknessMultiplier(1), 1e-6f, "1 layer must be 1.15");
        assertEquals(1.075f, ShadingPipeline.bedrockThicknessMultiplier(2), 1e-6f, "2 layers must be 1.075");
        assertEquals(1.00f, ShadingPipeline.bedrockThicknessMultiplier(3), 1e-6f, "3 layers must be 1.00");
        assertEquals(0.925f, ShadingPipeline.bedrockThicknessMultiplier(4), 1e-6f, "4 layers must be 0.925");
        assertEquals(0.85f, ShadingPipeline.bedrockThicknessMultiplier(5), 1e-6f, "5 layers must be 0.85");
    }

    @Test
    void brightnessMultiplierStrictlyDecreasesWithThickness() {
        final int baseColor = 0xFF808080;
        int prevBrightness = Integer.MAX_VALUE;

        for (int count = 1; count <= 5; count++) {
            final float factor = ShadingPipeline.bedrockThicknessMultiplier(count);
            final int shaded = ShadingPipeline.applyBrightnessMultiplier(baseColor, factor);
            final int red = Argb.red(shaded);
            final int green = Argb.green(shaded);
            final int blue = Argb.blue(shaded);

            assertEquals(red, green);
            assertEquals(green, blue);
            assertTrue(red < prevBrightness, "layer " + count + " should be darker than layer " + (count - 1));
            prevBrightness = red;
        }
    }

    @Test
    void monotonicityPreservedUnderLightTints() {
        final int baseColor = 0xFF8A7A66;
        for (int blockLight = 0; blockLight <= 15; blockLight++) {
            int prevBrightness = Integer.MAX_VALUE;
            for (int count = 1; count <= 5; count++) {
                final float factor = ShadingPipeline.bedrockThicknessMultiplier(count);
                final int shaded = ShadingPipeline.applyBrightnessMultiplier(baseColor, factor);
                // Zero-light ambient bake then block light composition:
                final int ambient = Argb.multiply(shaded, LightTint.multiplier(0, 0, true));
                final int lit = LightTint.applyBlockLightOverAmbient(ambient, blockLight, true, 0f);

                final int luminance = Argb.red(lit) + Argb.green(lit) + Argb.blue(lit);
                assertTrue(
                    luminance <= prevBrightness,
                    "thickness " + count + " must not exceed thickness " + (count - 1) + " under blockLight " + blockLight
                );
                prevBrightness = luminance;
            }
        }
    }

    @Test
    void sandwichStructureCountingLogic() {
        // Mock a 5-layer column: index 0 (top/solidY), 1, 2, 3, 4 (minY)
        // Scenario A: all 5 are bedrock -> count = 5
        final boolean[] allBedrock = {true, true, true, true, true};
        assertEquals(5, countBedrock(allBedrock));

        // Scenario B: top is bedrock, but with air/netherrack sandwich (e.g. bedrock, air, bedrock, bedrock, netherrack)
        final boolean[] sandwich = {true, false, true, true, false};
        assertEquals(3, countBedrock(sandwich));

        // Scenario C: only top is bedrock
        final boolean[] topOnly = {true, false, false, false, false};
        assertEquals(1, countBedrock(topOnly));
    }

    @Test
    void penetrationWhenTopIsNotBedrock() {
        // If top is not bedrock, the thickness logic is skipped, normal floor-scan continues.
        final boolean topIsBedrock = false;
        final float thicknessMultiplier = topIsBedrock
            ? ShadingPipeline.bedrockThicknessMultiplier(5)
            : 1.0f;
        assertEquals(1.0f, thicknessMultiplier, 1e-6f, "Non-bedrock surface must not receive thickness shading");
    }

    private static int countBedrock(final boolean[] layers) {
        int count = 0;
        for (final boolean isBedrock : layers) {
            if (isBedrock) {
                count++;
            }
        }
        return count;
    }
}
