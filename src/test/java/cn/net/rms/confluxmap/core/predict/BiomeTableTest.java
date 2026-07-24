package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Parity check between {@link BiomeTable}'s known ids and the union of supported-version
 * Overworld biome ids plus the End ids, transcribed from {@code native/cubiomes/biomes.c}'s
 * {@code biomeExists}/{@code isOverworld}/{@code getDimension} rules.
 */
class BiomeTableTest {
    /** Every id where {@code isOverworld(21, id)} or {@code getDimension(id) == DIM_END} holds, per biomes.c. */
    private static final Set<Integer> EXPECTED_IDS = Set.of(
        // Oceans + rivers.
        0, 24, 44, 45, 46, 48, 49, 50, 10, 7, 11,
        // Plains, desert.
        1, 129, 2, 17, 130,
        // Mountains.
        3, 131, 162, 34,
        // Forest.
        4, 18, 27, 28, 132, 155, 156, 29, 157,
        // Taiga.
        5, 19, 133, 32, 33, 160, 161, 30, 31, 158,
        // Jungle.
        21, 22, 149, 168, 169, 23, 151,
        // Savanna.
        35, 36, 163, 164,
        // Badlands.
        37, 39, 165, 167, 38, 166,
        // Snowy tundra.
        12, 13, 140,
        // Mushroom.
        14, 15,
        // Beach.
        16, 26,
        // Stone shore.
        25,
        // Swamp.
        6, 134,
        // Modern Overworld biomes with distinct numeric ids.
        174, 175, 177, 178, 179, 180, 181, 182, 183, 184, 185, 186,
        // The End.
        9, 40, 41, 42, 43
    );

    @Test
    void everyExpectedIdHasAnEntry() {
        for (final int id : EXPECTED_IDS) {
            org.junit.jupiter.api.Assertions.assertTrue(
                BiomeTable.knownIds().contains(id), "BiomeTable is missing an entry for biome id " + id
            );
        }
    }

    @Test
    void everyKnownIdIsExpected() {
        for (final int id : BiomeTable.knownIds()) {
            org.junit.jupiter.api.Assertions.assertTrue(
                EXPECTED_IDS.contains(id), "BiomeTable has an unexpected extra entry for biome id " + id
            );
        }
    }

    @Test
    void parityListsAreExactlyEqual() {
        assertEquals(EXPECTED_IDS, BiomeTable.knownIds());
    }

    @Test
    void expectedIdCountMatchesTheDocumentedTotal() {
        // 78 distinct Overworld ids across supported versions + 5 End ids.
        assertEquals(83, EXPECTED_IDS.size());
    }

    @Test
    void altitudeSnowLinesCoverEveryColdRainyFamily() {
        assertFalse(BiomeTable.hasAltitudeSnow(3, 94));
        assertTrue(BiomeTable.hasAltitudeSnow(3, 95));

        assertFalse(BiomeTable.hasAltitudeSnow(5, 124));
        assertTrue(BiomeTable.hasAltitudeSnow(5, 125));

        assertFalse(BiomeTable.hasAltitudeSnow(32, 154));
        assertTrue(BiomeTable.hasAltitudeSnow(32, 155));

        assertFalse(BiomeTable.hasAltitudeSnow(4, 255), "warm forest must not gain a false snow line");
    }
}
