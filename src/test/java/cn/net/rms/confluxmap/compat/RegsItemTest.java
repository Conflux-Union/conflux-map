package cn.net.rms.confluxmap.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.item.Items;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class RegsItemTest {
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
    void exposesStaticItemRegistryWithoutDefaultingUnknownIds() {
        assertTrue(Regs.items().iterator().hasNext());
        assertEquals(Items.DIAMOND, Regs.item(Ids.of("diamond")).orElseThrow());
        assertTrue(Regs.item(Ids.of("does_not_exist")).isEmpty());
        assertEquals("minecraft:diamond", Regs.itemId(Items.DIAMOND).toString());
    }
}
