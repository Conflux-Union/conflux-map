package cn.net.rms.confluxmap.mc.ui;

import cn.net.rms.confluxmap.compat.Ids;
import cn.net.rms.confluxmap.compat.Regs;
import net.minecraft.item.ItemStack;

/**
 * Resolves chest-loot item names ("minecraft:x" or bare "x") into display stacks from the
 * version's item registry. Returns null for unknown or malformed names - the loot screen
 * falls back to a text row for those.
 */
public final class LootItemResolver {

    public ItemStack resolve(final String lootItemName) {
        try {
            return Regs.item(Ids.of(lootItemName)).map(ItemStack::new).orElse(null);
        } catch (final RuntimeException malformed) {
            // A name the identifier parser rejects (unknown, null, malformed) degrades to null.
            return null;
        }
    }
}
