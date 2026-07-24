package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CubiomesBiomeIdsTest {
    @Test
    void everyPredictedBiomeIdRoundTripsThroughItsVanillaName() {
        for (final int id : BiomeTable.knownIds()) {
            final String name = CubiomesBiomeIds.nameForId(id).orElseThrow(
                () -> new AssertionError("missing vanilla name for cubiomes biome " + id)
            );
            assertTrue(CubiomesBiomeIds.idForName(name).isPresent());
            assertEquals(id, CubiomesBiomeIds.idForName(name).getAsInt());
        }
        assertEquals(3, CubiomesBiomeIds.idForName("windswept_hills").orElseThrow());
        assertEquals(155, CubiomesBiomeIds.idForName("old_growth_birch_forest").orElseThrow());
        assertEquals(186, CubiomesBiomeIds.idForName("pale_garden").orElseThrow());
    }
}
