package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.core.predict.ChestLootPreview;
import cn.net.rms.confluxmap.mc.ui.LootItemResolver;
import java.util.List;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class StructureLootScreenTest {
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

    private static final ChestLootPreview.ChestLoot CHEST =
        new ChestLootPreview.ChestLoot(123, 64, -761, "chests/pillager_outpost");

    @Test
    void chestRowsCarryPositionAndTableAndUnrolledChestsDegrade() {
        final List<StructureLootScreen.Row> rows = StructureLootScreen.buildRows(
            List.of(new ChestLootPreview(CHEST, List.of(), false)),
            new LootItemResolver()
        );
        assertEquals(2, rows.size());
        assertEquals(StructureLootScreen.Row.Kind.CHEST, rows.get(0).kind());
        assertEquals("123, 64, -761", rows.get(0).text());
        assertEquals("chests/pillager_outpost", rows.get(0).secondary());
        assertNull(rows.get(0).icon());
        assertEquals(StructureLootScreen.Row.Kind.DETAIL, rows.get(1).kind());
        assertEquals(
            Texts.translatable("confluxmap.screen.structure_loot.roll_unavailable").getString(),
            rows.get(1).text()
        );
    }

    @Test
    void stackRowsCarryIconsCountsAndDetailLines() {
        final ChestLootPreview preview = new ChestLootPreview(
            CHEST,
            List.of(
                new ChestLootPreview.LootStack(
                    "minecraft:iron_ingot",
                    8,
                    List.of(new ChestLootPreview.LootEnchantment("sharpness", 3)),
                    "regeneration 45"
                ),
                new ChestLootPreview.LootStack("not_an_item", 1, List.of(), "")
            ),
            true
        );
        final List<StructureLootScreen.Row> rows = StructureLootScreen.buildRows(
            List.of(preview), new LootItemResolver()
        );
        assertEquals(5, rows.size());
        final ItemStack icon = rows.get(1).icon();
        assertSame(Items.IRON_INGOT, icon.getItem());
        assertEquals(8, rows.get(1).count());
        assertNull(rows.get(1).text());
        assertEquals(StructureLootScreen.Row.Kind.DETAIL, rows.get(2).kind());
        assertEquals("sharpness 3", rows.get(2).text());
        assertEquals(StructureLootScreen.Row.Kind.DETAIL, rows.get(3).kind());
        assertEquals("regeneration 45", rows.get(3).text());
        assertEquals(StructureLootScreen.Row.Kind.STACK, rows.get(4).kind());
        assertNull(rows.get(4).icon());
        assertEquals("not_an_item", rows.get(4).text());
        assertEquals(1, rows.get(4).count());
    }
}
