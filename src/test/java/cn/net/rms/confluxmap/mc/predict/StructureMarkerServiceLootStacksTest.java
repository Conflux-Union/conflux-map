package cn.net.rms.confluxmap.mc.predict;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.confluxmap.core.predict.ChestLootPreview;
import cn.net.rms.confluxmap.nativepredict.CubiomesContext;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Direct seam test for {@link StructureMarkerService#lootStacks}' stack-major de-flattening. */
class StructureMarkerServiceLootStacksTest {
    @Test
    void deFlattensEnchantmentsOntoTheirStacks() {
        final int[] counts = {3, 2, 1, 0};
        final int[] itemCounts = {4, 1, 7};
        final String[] items = {"minecraft:a", "minecraft:b", "minecraft:c"};
        final String[] effects = {"minecraft:speed 100", "", "minecraft:glowing 200"};
        final String[] enchantNames = {"sharpness", "fortune", "power", "unused"};
        final int[] enchantLevels = {3, 1, 2, 0};

        final List<ChestLootPreview.LootStack> stacks = StructureMarkerService.lootStacks(
            3, counts, itemCounts, items, effects, enchantNames, enchantLevels
        );

        assertEquals(3, stacks.size());
        assertEquals(new ChestLootPreview.LootStack(
            "minecraft:a", 4,
            List.of(
                new ChestLootPreview.LootEnchantment("sharpness", 3),
                new ChestLootPreview.LootEnchantment("fortune", 1)
            ),
            "minecraft:speed 100"
        ), stacks.get(0));
        assertEquals(new ChestLootPreview.LootStack(
            "minecraft:b", 1,
            List.of(new ChestLootPreview.LootEnchantment("power", 2)),
            ""
        ), stacks.get(1));
        assertEquals(new ChestLootPreview.LootStack(
            "minecraft:c", 7, List.of(), "minecraft:glowing 200"
        ), stacks.get(2));
    }

    @Test
    void reportedCountAboveTheWrittenCapStaysWithinTheArrays() {
        final int cap = CubiomesContext.MAX_LOOT_STACKS;
        final int[] counts = new int[cap + 1];
        final int[] itemCounts = new int[cap];
        final String[] items = new String[cap];
        final String[] effects = new String[cap];
        for (int stack = 0; stack < cap; stack++) {
            items[stack] = "minecraft:item" + stack;
        }

        // A roll reports its full stack count even when it wrote only the capped stacks.
        final List<ChestLootPreview.LootStack> stacks = StructureMarkerService.lootStacks(
            cap + 13, counts, itemCounts, items, effects, new String[0], new int[0]
        );

        assertEquals(cap, stacks.size());
        assertEquals("minecraft:item0", stacks.get(0).itemName());
        assertEquals("minecraft:item" + (cap - 1), stacks.get(cap - 1).itemName());
        assertEquals(List.of(), stacks.get(0).enchantments());
    }
}
