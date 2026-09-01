package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MapSearchModeTest {
    @Test
    void toggleMovesBetweenStructureAndBiomeSearch() {
        assertEquals(MapSearchMode.BIOME, MapSearchMode.STRUCTURE.toggle());
        assertEquals(MapSearchMode.STRUCTURE, MapSearchMode.BIOME.toggle());
    }

    @Test
    void serverStructurePolicyDoesNotDisableBiomeSearch() {
        assertFalse(MapSearchMode.STRUCTURE.allowed(false));
        assertTrue(MapSearchMode.BIOME.allowed(false));
    }

    @Test
    void itemCountUsesResultsForCurrentSearchMode() {
        assertEquals(7, MapSearchMode.STRUCTURE.itemCount(7, 41));
        assertEquals(41, MapSearchMode.BIOME.itemCount(7, 41));
    }
}
