package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.predict.ChestLootPreview;
import cn.net.rms.confluxmap.mc.ui.LootItemResolver;
import java.util.List;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
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
    void panelCarriesTablePositionAndResolvedSlots() {
        final ChestLootPreview preview = new ChestLootPreview(
            CHEST,
            List.of(
                new ChestLootPreview.LootStack(
                    "minecraft:iron_ingot",
                    8,
                    List.of(new ChestLootPreview.LootEnchantment("sharpness", 3)),
                    "regeneration 45"
                ),
                new ChestLootPreview.LootStack("minecraft:arrow", 7, List.of(), "")
            ),
            true
        );
        final List<StructureLootScreen.Panel> panels = StructureLootScreen.buildPanels(
            List.of(preview), new LootItemResolver()
        );
        assertEquals(1, panels.size());
        final StructureLootScreen.Panel panel = panels.get(0);
        assertEquals("chests/pillager_outpost", panel.table());
        assertEquals(123, panel.blockX());
        assertEquals(64, panel.blockY());
        assertEquals(-761, panel.blockZ());
        assertTrue(panel.rolled());
        assertEquals(2, panel.slots().size());
        final StructureLootScreen.Slot first = panel.slots().get(0);
        assertSame(Items.IRON_INGOT, first.icon().getItem());
        assertNull(first.rawName());
        assertEquals(8, first.count());
        assertEquals(
            List.of(new ChestLootPreview.LootEnchantment("sharpness", 3)),
            first.enchantments()
        );
        assertEquals("regeneration 45", first.effect());
        final StructureLootScreen.Slot second = panel.slots().get(1);
        assertSame(Items.ARROW, second.icon().getItem());
        assertNull(second.rawName());
        assertEquals(7, second.count());
        assertTrue(second.enchantments().isEmpty());
        assertEquals("", second.effect());
    }

    @Test
    void unknownItemDegradesToRawNameSlot() {
        final ChestLootPreview preview = new ChestLootPreview(
            CHEST,
            List.of(new ChestLootPreview.LootStack("not_an_item", 1, List.of(), "")),
            true
        );
        final List<StructureLootScreen.Panel> panels = StructureLootScreen.buildPanels(
            List.of(preview), new LootItemResolver()
        );
        final StructureLootScreen.Slot slot = panels.get(0).slots().get(0);
        assertNull(slot.icon());
        assertEquals("not_an_item", slot.rawName());
        assertEquals(1, slot.count());
    }

    @Test
    void unrolledChestDegradesToNotePanel() {
        final List<StructureLootScreen.Panel> panels = StructureLootScreen.buildPanels(
            List.of(new ChestLootPreview(CHEST, List.of(), false)), new LootItemResolver()
        );
        assertEquals(1, panels.size());
        final StructureLootScreen.Panel panel = panels.get(0);
        assertEquals("chests/pillager_outpost", panel.table());
        assertTrue(panel.slots().isEmpty());
        assertTrue(!panel.rolled());
    }

    @Test
    void eachChestBuildsItsOwnPanel() {
        final List<StructureLootScreen.Panel> panels = StructureLootScreen.buildPanels(
            List.of(
                new ChestLootPreview(CHEST, List.of(), false),
                new ChestLootPreview(
                    new ChestLootPreview.ChestLoot(1, 2, 3, "chests/jungle_temple"),
                    List.of(), true
                )
            ),
            new LootItemResolver()
        );
        assertEquals(2, panels.size());
        assertEquals("chests/pillager_outpost", panels.get(0).table());
        assertEquals("chests/jungle_temple", panels.get(1).table());
    }

    @Test
    void romanNumeralsCoverLootLevels() {
        assertEquals("I", StructureLootScreen.romanNumeral(1));
        assertEquals("III", StructureLootScreen.romanNumeral(3));
        assertEquals("IX", StructureLootScreen.romanNumeral(9));
        assertEquals("XIV", StructureLootScreen.romanNumeral(14));
        // Levels outside 1..3999 cannot happen; the fallback keeps the function total.
        assertEquals("0", StructureLootScreen.romanNumeral(0));
        assertEquals("4000", StructureLootScreen.romanNumeral(4000));
    }

    @Test
    void effectClockFormatsVanillaDurations() {
        assertEquals("", StructureLootScreen.effectClock(0));
        assertEquals("", StructureLootScreen.effectClock(-1));
        assertEquals("0:00", StructureLootScreen.effectClock(1));
        assertEquals("0:01", StructureLootScreen.effectClock(20));
        assertEquals("0:02", StructureLootScreen.effectClock(45));
        assertEquals("2:00", StructureLootScreen.effectClock(2400));
    }

    @Test
    void translationProbeHitsVanillaKeysAndFallsBackOnUnknownOnes() {
        // The test classpath carries vanilla's own en_us.json (Language.create loads it), so
        // vanilla keys resolve exactly like they do on a live client.
        assertEquals("Sharpness", StructureLootScreen.translatedOr(
            "enchantment.minecraft.sharpness", "sharpness"
        ));
        assertEquals("", StructureLootScreen.translatedOr(
            "confluxmap.loot_table.chests.missing", ""
        ));
    }

    @Test
    void tableKeyTargetsChestNamespace() {
        assertEquals(
            "confluxmap.loot_table.chests.jungle_temple_dispenser",
            StructureLootScreen.tableKey("chests/jungle_temple_dispenser")
        );
        assertEquals("", StructureLootScreen.tableKey(""));
        assertEquals("", StructureLootScreen.tableKey("weird"));
    }

    @Test
    void enchantmentLineTranslatesAndAppendsRomanLevel() {
        assertEquals("Sharpness III", StructureLootScreen.enchantmentLine("sharpness", 3));
        assertEquals("ench I", StructureLootScreen.enchantmentLine("ench", 1));
        assertEquals("ench 4000", StructureLootScreen.enchantmentLine("ench", 4000));
    }

    @Test
    void effectLineTranslatesAndAppendsTheClock() {
        assertEquals("Slowness (2:00)", StructureLootScreen.effectLine("minecraft:slowness 2400"));
        assertEquals("Instant Damage", StructureLootScreen.effectLine("minecraft:instant_damage"));
        // "harming" is a potion id, not an effect id - vanilla has no such key, so the
        // stripped id must come through as the fallback.
        assertEquals("harming", StructureLootScreen.effectLine("minecraft:harming"));
        assertEquals("odd effect", StructureLootScreen.effectLine("odd effect"));
        assertEquals("weird (0:01)", StructureLootScreen.effectLine("weird 20"));
    }
}
