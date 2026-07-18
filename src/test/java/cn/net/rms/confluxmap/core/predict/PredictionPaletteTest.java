package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link PredictionPalette}'s fallback-to-{@link BiomeTable} behavior. */
class PredictionPaletteTest {
    private static final int UNKNOWN_ID = 987_654;

    @Test
    void perKindBaseColorsComeFromBiomeTable() {
        final PredictionPalette palette = PredictionPalette.defaults();
        assertEquals(BiomeTable.LAND_BASE, palette.landBase);
        assertEquals(BiomeTable.SAND_BASE, palette.sandBase);
        assertEquals(BiomeTable.SNOW_BASE, palette.snowBase);
        assertEquals(BiomeTable.ICE_BASE, palette.iceBase);
        assertEquals(BiomeTable.WATER_BASE, palette.waterBase);
        assertEquals(BiomeTable.FOLIAGE_BASE, palette.foliageBase);
    }

    @Test
    void unknownBiomeIdFallsBackToBiomeTableTints() {
        final PredictionPalette palette = PredictionPalette.defaults();
        final BiomeTable.Entry fallback = BiomeTable.get(UNKNOWN_ID);
        assertEquals(fallback.grassTint(), palette.grassTint(UNKNOWN_ID));
        assertEquals(fallback.foliageTint(), palette.foliageTint(UNKNOWN_ID));
        assertEquals(fallback.waterTint(), palette.waterTint(UNKNOWN_ID));
    }

    @Test
    void knownBiomeUsesTheLiveSampleInstead() {
        final int plainsId = 1;
        final PredictionPalette palette = PredictionPalette.fromSamples(
            Map.of(plainsId, new int[] {0x11223344, 0x55667788, 0x99AABBCC})
        );
        assertEquals(0x11223344, palette.grassTint(plainsId));
        assertEquals(0x55667788, palette.foliageTint(plainsId));
        assertEquals(0x99AABBCC, palette.waterTint(plainsId));

        // An id absent from the sample map still falls back, even on a non-default palette.
        final int desertId = 2;
        assertEquals(BiomeTable.get(desertId).grassTint(), palette.grassTint(desertId));
    }
}
