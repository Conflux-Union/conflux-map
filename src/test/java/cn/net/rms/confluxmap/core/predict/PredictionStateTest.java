package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.nativepredict.McVersions;
import cn.net.rms.confluxmap.nativepredict.NativeLib;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Per-dimension gating. The flat cases run everywhere - a superflat underlay is seedless and
 * native-free by design. The seeded cases include {@code NativeLib.available()} in {@link
 * PredictionState#predictable}, so those assert under the same skip gate as {@code
 * PredictionNativeIntegrationTest}.
 */
class PredictionStateTest {
    private static final long SEED = 146008555L;
    private static final FlatBaseline FLAT_SURFACE =
        new FlatBaseline(1, 3, SurfaceKind.LAND.ordinal(), 11, 0);

    private static PredictionState seeded(final WorldPreset overworld, final WorldPreset end) {
        final PredictionState state = new PredictionState();
        state.setPresets(overworld, end);
        state.setSeed(SEED, McVersions.toCubiomes("1.17").orElseThrow());
        return state;
    }

    @Test
    void flatOverworldPredictsFromItsBaselineWithoutSeedOrNative() {
        final PredictionState state = new PredictionState();
        state.setPresets(WorldPreset.FLAT, WorldPreset.DEFAULT);
        assertFalse(state.predictable(DimensionId.OVERWORLD), "no baseline yet");
        state.setFlatBaseline(FLAT_SURFACE);
        assertTrue(state.predictable(DimensionId.OVERWORLD));
        assertFalse(state.seedKnown());
        assertFalse(state.cubiomesBacked(DimensionId.OVERWORLD));
        assertEquals(FLAT_SURFACE, state.flatBaseline(DimensionId.OVERWORLD));
    }

    @Test
    void flatBaselineNeverLeaksIntoTheEnd() {
        final PredictionState state = new PredictionState();
        state.setPresets(WorldPreset.FLAT, WorldPreset.DEFAULT);
        state.setFlatBaseline(FLAT_SURFACE);
        assertEquals(null, state.flatBaseline(DimensionId.END));
        assertFalse(state.predictable(DimensionId.END), "the End still needs the seeded pipeline");
    }

    @Test
    void clearResetsPresetsAndFlatBaseline() {
        final PredictionState state = new PredictionState();
        state.setPresets(WorldPreset.FLAT, WorldPreset.CUSTOM);
        state.setFlatBaseline(FLAT_SURFACE);
        state.clear();
        assertEquals(WorldPreset.DEFAULT, state.preset(DimensionId.OVERWORLD));
        assertEquals(WorldPreset.DEFAULT, state.preset(DimensionId.END));
        assertEquals(null, state.flatBaseline(DimensionId.OVERWORLD));
        assertFalse(state.predictable(DimensionId.OVERWORLD));
    }

    @Test
    void seededFlatWorldStillGatesTheOverworldOnItsBaseline() {
        Assumptions.assumeTrue(NativeLib.initForTests(), "native prediction library unavailable on this platform");
        final PredictionState state = seeded(WorldPreset.FLAT, WorldPreset.DEFAULT);
        assertFalse(state.predictable(DimensionId.OVERWORLD), "a seed alone cannot predict a superflat overworld");
        assertTrue(state.predictable(DimensionId.END), "the flat world's End generates normally");
        state.setFlatBaseline(FLAT_SURFACE);
        assertTrue(state.predictable(DimensionId.OVERWORLD));
    }

    @Test
    void largeBiomesFlagsReachOnlyTheOverworldContext() {
        Assumptions.assumeTrue(NativeLib.initForTests(), "native prediction library unavailable on this platform");
        final PredictionState state = seeded(WorldPreset.LARGE_BIOMES, WorldPreset.DEFAULT);
        assertTrue(state.predictable(DimensionId.OVERWORLD));
        assertTrue(state.cubiomesBacked(DimensionId.OVERWORLD));
        assertEquals(0x1, state.cubiomesFlags(DimensionId.OVERWORLD));
        assertEquals(0, state.cubiomesFlags(DimensionId.END));
    }

    @Test
    void amplifiedStaysPredictable() {
        Assumptions.assumeTrue(NativeLib.initForTests(), "native prediction library unavailable on this platform");
        final PredictionState state = seeded(WorldPreset.AMPLIFIED, WorldPreset.DEFAULT);
        assertTrue(state.predictable(DimensionId.OVERWORLD));
        assertTrue(state.preset(DimensionId.OVERWORLD).terrainApproximate());
        assertEquals(0, state.cubiomesFlags(DimensionId.OVERWORLD));
    }

    @Test
    void unsupportedDimensionsStayUnpredictableRegardlessOfPreset() {
        Assumptions.assumeTrue(NativeLib.initForTests(), "native prediction library unavailable on this platform");
        final PredictionState state = seeded(WorldPreset.DEFAULT, WorldPreset.DEFAULT);
        assertFalse(state.predictable(DimensionId.NETHER));
    }
}
