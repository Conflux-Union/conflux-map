package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WorldPresetTest {

    @Test
    void wireIdsRoundTripForEveryPreset() {
        for (final WorldPreset preset : WorldPreset.values()) {
            assertEquals(preset, WorldPreset.fromWireId(preset.wireId()));
            assertTrue(preset.wireId() >= 0 && preset.wireId() <= 7, "wire id must fit 3 bits");
        }
    }

    @Test
    void zeroWireIdIsDefaultForPrePresetPeers() {
        assertEquals(WorldPreset.DEFAULT, WorldPreset.fromWireId(0));
    }

    @Test
    void unknownWireIdsDecodeAsCustom() {
        assertEquals(WorldPreset.CUSTOM, WorldPreset.fromWireId(6));
        assertEquals(WorldPreset.CUSTOM, WorldPreset.fromWireId(7));
        assertEquals(WorldPreset.CUSTOM, WorldPreset.fromWireId(-1));
        assertEquals(WorldPreset.CUSTOM, WorldPreset.fromWireId(8));
    }

    @Test
    void predictabilityMatchesCubiomesCoverage() {
        assertTrue(WorldPreset.DEFAULT.predictable());
        assertTrue(WorldPreset.LARGE_BIOMES.predictable());
        assertTrue(WorldPreset.AMPLIFIED.predictable());
        assertFalse(WorldPreset.FLAT.predictable());
        assertFalse(WorldPreset.DEBUG.predictable());
        assertFalse(WorldPreset.CUSTOM.predictable());
    }

    @Test
    void onlyLargeBiomesCarriesGeneratorFlags() {
        for (final WorldPreset preset : WorldPreset.values()) {
            assertEquals(
                preset == WorldPreset.LARGE_BIOMES ? 0x1 : 0,
                preset.cubiomesFlags(),
                preset.name()
            );
        }
    }

    @Test
    void onlyAmplifiedHasApproximateTerrain() {
        for (final WorldPreset preset : WorldPreset.values()) {
            assertEquals(preset == WorldPreset.AMPLIFIED, preset.terrainApproximate(), preset.name());
        }
    }
}
