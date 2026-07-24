package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.confluxmap.core.util.Argb;
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

    @Test
    void sampledGrassNeverRepaintsAFixedGround() {
        // Vanilla pins eroded badlands' grass to a dull olive; the terracotta ground must ignore it.
        final int erodedBadlands = 165;
        final PredictionPalette palette = PredictionPalette.fromSamples(
            Map.of(erodedBadlands, new int[] {0xFF90814D, 0xFF9E814D, 0xFF3F76E4})
        );
        assertEquals(BiomeTable.get(erodedBadlands).groundBase(), palette.groundColor(erodedBadlands));
    }

    @Test
    void sampledFoliageNeverRepaintsAFixedCanopy() {
        // Vanilla's cherry grove foliage color is green; the predicted canopy must stay pink.
        final int cherryGrove = 185;
        final PredictionPalette palette = PredictionPalette.fromSamples(
            Map.of(cherryGrove, new int[] {0xFFB6DB61, 0xFFB6DB61, 0xFF5DB7EF})
        );
        assertEquals(BiomeTable.get(cherryGrove).canopyBase(), palette.canopyColor(cherryGrove));
    }

    @Test
    void sampledFoliageStillTintsANormalCanopy() {
        final int forest = 4;
        final PredictionPalette palette = PredictionPalette.fromSamples(
            Map.of(forest, new int[] {0xFF11AA22, 0xFF33BB44, 0xFF3F76E4})
        );
        assertEquals(
            Argb.multiply(BiomeTable.FOLIAGE_BASE, 0xFF33BB44),
            palette.canopyColor(forest)
        );
    }
}
