package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.compat.Ids;
import cn.net.rms.confluxmap.core.model.DimensionId;
import org.junit.jupiter.api.Test;

class StructureSearchScreenTest {
    @Test
    void netherSearchExcludesVanillaBiomesThatNeverGenerateThere() {
        assertTrue(availableInNether("nether_wastes"));
        assertTrue(availableInNether("soul_sand_valley"));
        assertTrue(availableInNether("crimson_forest"));
        assertTrue(availableInNether("warped_forest"));
        assertTrue(availableInNether("basalt_deltas"));

        assertAll(
            () -> assertFalse(availableInNether("mountain_edge"), "mountain_edge"),
            () -> assertFalse(availableInNether("deep_warm_ocean"), "deep_warm_ocean"),
            () -> assertFalse(availableInNether("the_void"), "the_void")
        );
    }

    @Test
    void netherSearchKeepsModdedBiomesWhoseDimensionIsUnknown() {
        assertTrue(StructureSearchScreen.isBiomeAvailable(
            DimensionId.NETHER,
            Ids.of("example", "custom_nether")
        ));
    }

    private static boolean availableInNether(final String path) {
        return StructureSearchScreen.isBiomeAvailable(
            DimensionId.NETHER,
            Ids.of("minecraft", path)
        );
    }
}
