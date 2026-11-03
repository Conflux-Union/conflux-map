package cn.net.rms.confluxmap.core.color;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ShadingPipelineLutAndSentinelTest {
    private static final double HEIGHT_STEP = 8.0;
    private static final double K_ALONE = 1.8;
    private static final double K_WITH_SLOPE = 3.0;

    private static double computeRawHeightShade(final int diff, final boolean slopeAlsoActive) {
        final double k = slopeAlsoActive ? K_WITH_SLOPE : K_ALONE;
        final double shade = Math.log10(Math.abs(diff) / HEIGHT_STEP + 1.0) / k;
        return diff < 0 ? -shade : shade;
    }

    @Test
    void lutMatchesDirectFormulaBitForBitAcrossRangeAndBoundaries() {
        // Test dense span across [-1100, 1100] covering inner LUT and fallback paths
        for (int diff = -1100; diff <= 1100; diff++) {
            final int columnHeight = ShadingPipeline.REFERENCE_HEIGHT + diff;

            final double expectedAlone = computeRawHeightShade(diff, false);
            final double actualAlone = ShadingPipeline.heightShade(columnHeight, ShadingPipeline.REFERENCE_HEIGHT, false);
            assertEquals(
                Double.doubleToRawLongBits(expectedAlone),
                Double.doubleToRawLongBits(actualAlone),
                "Bit mismatch for alone diff " + diff
            );

            final double expectedSlope = computeRawHeightShade(diff, true);
            final double actualSlope = ShadingPipeline.heightShade(columnHeight, ShadingPipeline.REFERENCE_HEIGHT, true);
            assertEquals(
                Double.doubleToRawLongBits(expectedSlope),
                Double.doubleToRawLongBits(actualSlope),
                "Bit mismatch for slope diff " + diff
            );
        }

        // Test extreme coordinates to confirm no arithmetic overflow or crash
        final int[] extremes = {
            Integer.MIN_VALUE, -30_000_000, -2048, -513, -512, -1, 0, 1, 512, 513, 2048, 30_000_000, Integer.MAX_VALUE
        };
        for (final int h : extremes) {
            final int diff = h - ShadingPipeline.REFERENCE_HEIGHT;
            final double expected = computeRawHeightShade(diff, false);
            final double actual = ShadingPipeline.heightShade(h, ShadingPipeline.REFERENCE_HEIGHT, false);
            assertEquals(
                Double.doubleToRawLongBits(expected),
                Double.doubleToRawLongBits(actual),
                "Bit mismatch for extreme " + h
            );
        }
    }

    @Test
    void directionalReliefMultiplierReturnsNeutralWhenAnyShoulderIsSentinel() {
        final int valid = 70;
        final int no = ShadingPipeline.NO_HEIGHT;

        // Exactly one shoulder missing (NO_HEIGHT) among the 6 samples must return 1.0 (neutral)
        assertEquals(1.0, ShadingPipeline.directionalReliefMultiplierWithSentinels(no, valid, valid, valid, valid, valid, 1));
        assertEquals(1.0, ShadingPipeline.directionalReliefMultiplierWithSentinels(valid, no, valid, valid, valid, valid, 1));
        assertEquals(1.0, ShadingPipeline.directionalReliefMultiplierWithSentinels(valid, valid, no, valid, valid, valid, 1));
        assertEquals(1.0, ShadingPipeline.directionalReliefMultiplierWithSentinels(valid, valid, valid, no, valid, valid, 1));
        assertEquals(1.0, ShadingPipeline.directionalReliefMultiplierWithSentinels(valid, valid, valid, valid, no, valid, 1));
        assertEquals(1.0, ShadingPipeline.directionalReliefMultiplierWithSentinels(valid, valid, valid, valid, valid, no, 1));

        // Null-wrapper compatibility overload must match primitive behavior
        assertEquals(1.0, ShadingPipeline.directionalReliefMultiplier(null, valid, valid, valid, valid, valid, 1));
    }

    @Test
    void filledShoulderHandlesSentinelSubstitutionsAndClamping() {
        final int no = ShadingPipeline.NO_HEIGHT;

        // When shoulder is present, return it directly
        assertEquals(85, ShadingPipeline.filledShoulderWithSentinels(85, no, 70));
        assertEquals(85, ShadingPipeline.filledShoulderWithSentinels(85, 90, 70));

        // When shoulder is absent and opposite is absent, return centerHeight flat
        assertEquals(70, ShadingPipeline.filledShoulderWithSentinels(no, no, 70));

        // When shoulder is absent, extrapolate from opposite clamped to 1 block
        // centerHeight - opposite = 70 - 75 = -5 -> clamped to -1 -> 70 + (-1) = 69
        assertEquals(69, ShadingPipeline.filledShoulderWithSentinels(no, 75, 70));
        // centerHeight - opposite = 70 - 65 = +5 -> clamped to +1 -> 70 + 1 = 71
        assertEquals(71, ShadingPipeline.filledShoulderWithSentinels(no, 65, 70));
        // centerHeight - opposite = 70 - 70 = 0 -> clamped to 0 -> 70
        assertEquals(70, ShadingPipeline.filledShoulderWithSentinels(no, 70, 70));
    }

    @Test
    void slopeShadeWithSentinelReturnsFlat() {
        assertEquals(0.0, ShadingPipeline.slopeShade(70, ShadingPipeline.NO_HEIGHT));
        assertEquals(1.0 / 8.0, ShadingPipeline.slopeShade(70, 75));
        assertEquals(-1.0 / 8.0, ShadingPipeline.slopeShade(70, 65));
        assertEquals(0.0, ShadingPipeline.slopeShade(70, 70));
    }

    @Test
    void boxedMethodsAcceptMixedPrimitiveAndBoxedHeights() {
        final Integer shoulder = Integer.valueOf(75);
        final Integer opposite = Integer.valueOf(68);
        final Integer missing = null;

        assertEquals(70, ShadingPipeline.filledShoulder(70, opposite, 70));
        assertEquals(75, ShadingPipeline.filledShoulder(shoulder, 68, 70));
        assertEquals(71, ShadingPipeline.filledShoulder(missing, 68, 70));
        assertEquals(1.15, ShadingPipeline.directionalReliefMultiplier(
            shoulder, 75, 75, 71, 71, 71, 4
        ));
        assertEquals(1.0, ShadingPipeline.directionalReliefMultiplier(
            missing, 75, 75, 71, 71, 71, 4
        ));
    }

    @Test
    void boxedOverloadsMapNullToNoHeightProperly() {
        // filledShoulder(Integer, Integer, int)
        assertEquals(70, ShadingPipeline.filledShoulder((Integer) null, (Integer) null, 70));
        assertEquals(70, ShadingPipeline.filledShoulder((Integer) null, Integer.valueOf(70), 70));
        assertEquals(85, ShadingPipeline.filledShoulder(Integer.valueOf(85), (Integer) null, 70));

        // slopeShade(int, Integer)
        assertEquals(0.0, ShadingPipeline.slopeShade(70, (Integer) null));
        assertEquals(1.0 / 8.0, ShadingPipeline.slopeShade(70, Integer.valueOf(75)));
    }
}
