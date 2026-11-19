package cn.net.rms.confluxmap.core.predict;

import java.util.List;

/**
 * The predicted loot of one chest: block position, loot table, the deterministic vanilla roll of
 * its contents, and whether that roll succeeded ({@code rolled=false} leaves {@code stacks}
 * empty - table or version not rollable).
 */
public record ChestLootPreview(ChestLoot chest, List<LootStack> stacks, boolean rolled) {

    /**
     * One chest: block position and loot table ("chests/pillager_outpost" style). Y is the
     * fork's piece Y - real 3D where modeled, 0 where the fork leaves it unset (bastion, camp).
     */
    public record ChestLoot(int blockX, int blockY, int blockZ, String lootTable) {
    }

    /**
     * One rolled stack: namespaced item name ("minecraft:iron_ingot"), the item count, its
     * enchantments, and the effect descriptor ({@code "<effect_name> <duration>"}, empty when
     * the stack has none).
     */
    public record LootStack(
        String itemName,
        int count,
        List<LootEnchantment> enchantments,
        String effect
    ) {
    }

    public record LootEnchantment(String name, int level) {
    }
}
