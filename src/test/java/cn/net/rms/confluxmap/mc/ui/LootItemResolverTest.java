package cn.net.rms.confluxmap.mc.ui;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class LootItemResolverTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        //#if MC>=11800
        //$$ Assumptions.abort(
        //$$     "Yarn's named 1.18+ test jars cannot bootstrap vanilla static registries"
        //$$ );
        //#else
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
        //#endif
    }

    @Test
    void resolvesNamespacedItemNames() {
        final ItemStack stack = new LootItemResolver().resolve("minecraft:iron_ingot");
        assertSame(Items.IRON_INGOT, stack.getItem());
    }

    @Test
    void resolvesBareItemNamesAsMinecraft() {
        final ItemStack stack = new LootItemResolver().resolve("iron_ingot");
        assertSame(Items.IRON_INGOT, stack.getItem());
    }

    @Test
    void unknownItemNamesResolveToNull() {
        final LootItemResolver resolver = new LootItemResolver();
        assertNull(resolver.resolve("not_an_item"));
        assertNull(resolver.resolve("minecraft:not_an_item"));
    }
}
