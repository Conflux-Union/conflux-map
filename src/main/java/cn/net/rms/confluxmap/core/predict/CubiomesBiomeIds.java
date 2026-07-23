package cn.net.rms.confluxmap.core.predict;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Maps a vanilla biome registry identifier's path (e.g. {@code "plains"} out of {@code
 * minecraft:plains}) to the cubiomes biome id it corresponds to, per the plan's R4
 * note: raw registry ids must never be assumed to equal cubiomes ids, so this table is built by
 * hand from {@code native/cubiomes/biomes.h}'s enum. Renamed modern biomes are aliases of their
 * older numeric ids, while biomes added since 1.17 have their own entries.
 *
 * <p>Deliberately a separate table from {@link BiomeTable} (whose keys are already the cubiomes
 * ids) rather than a derived reverse-index, since this one only exists for {@code
 * mc.predict.PredictionPaletteBuilder} to resolve the client's live biome registry - {@link
 * BiomeTable} itself never needs a name, only the id cubiomes hands back from a query.
 */
public final class CubiomesBiomeIds {
    private static final Map<String, Integer> BY_NAME = new HashMap<>();
    private static final Map<Integer, String> BY_ID = new HashMap<>();

    private CubiomesBiomeIds() {
    }

    public static OptionalInt idForName(final String name) {
        final Integer id = BY_NAME.get(name);
        return id == null ? OptionalInt.empty() : OptionalInt.of(id);
    }

    public static Optional<String> nameForId(final int id) {
        return Optional.ofNullable(BY_ID.get(id));
    }

    private static void put(final int id, final String name) {
        BY_NAME.put(name, id);
        BY_ID.put(id, name);
    }

    private static void alias(final int id, final String name) {
        BY_NAME.put(name, id);
    }

    static {
        put(0, "ocean");
        put(1, "plains");
        put(2, "desert");
        put(3, "mountains");
        put(4, "forest");
        put(5, "taiga");
        put(6, "swamp");
        put(7, "river");
        put(9, "the_end");
        put(10, "frozen_ocean");
        put(11, "frozen_river");
        put(12, "snowy_tundra");
        put(13, "snowy_mountains");
        put(14, "mushroom_fields");
        put(15, "mushroom_field_shore");
        put(16, "beach");
        put(17, "desert_hills");
        put(18, "wooded_hills");
        put(19, "taiga_hills");
        put(21, "jungle");
        put(22, "jungle_hills");
        put(23, "jungle_edge");
        put(24, "deep_ocean");
        put(25, "stone_shore");
        put(26, "snowy_beach");
        put(27, "birch_forest");
        put(28, "birch_forest_hills");
        put(29, "dark_forest");
        put(30, "snowy_taiga");
        put(31, "snowy_taiga_hills");
        put(32, "giant_tree_taiga");
        put(33, "giant_tree_taiga_hills");
        put(34, "wooded_mountains");
        put(35, "savanna");
        put(36, "savanna_plateau");
        put(37, "badlands");
        put(38, "wooded_badlands_plateau");
        put(39, "badlands_plateau");
        put(40, "small_end_islands");
        put(41, "end_midlands");
        put(42, "end_highlands");
        put(43, "end_barrens");
        put(44, "warm_ocean");
        put(45, "lukewarm_ocean");
        put(46, "cold_ocean");
        put(48, "deep_lukewarm_ocean");
        put(49, "deep_cold_ocean");
        put(50, "deep_frozen_ocean");
        put(129, "sunflower_plains");
        put(130, "desert_lakes");
        put(131, "gravelly_mountains");
        put(132, "flower_forest");
        put(133, "taiga_mountains");
        put(134, "swamp_hills");
        put(140, "ice_spikes");
        put(149, "modified_jungle");
        put(151, "modified_jungle_edge");
        put(155, "tall_birch_forest");
        put(156, "tall_birch_hills");
        put(157, "dark_forest_hills");
        put(158, "snowy_taiga_mountains");
        put(160, "giant_spruce_taiga");
        put(161, "giant_spruce_taiga_hills");
        put(162, "modified_gravelly_mountains");
        put(163, "shattered_savanna");
        put(164, "shattered_savanna_plateau");
        put(165, "eroded_badlands");
        put(166, "modified_wooded_badlands_plateau");
        put(167, "modified_badlands_plateau");
        put(168, "bamboo_jungle");
        put(169, "bamboo_jungle_hills");
        put(174, "dripstone_caves");
        put(175, "lush_caves");
        put(177, "meadow");
        put(178, "grove");
        put(179, "snowy_slopes");
        put(180, "jagged_peaks");
        put(181, "frozen_peaks");
        put(182, "stony_peaks");
        put(183, "deep_dark");
        put(184, "mangrove_swamp");
        put(185, "cherry_grove");
        put(186, "pale_garden");

        alias(12, "snowy_plains");
        alias(155, "old_growth_birch_forest");
        alias(32, "old_growth_pine_taiga");
        alias(160, "old_growth_spruce_taiga");
        alias(23, "sparse_jungle");
        alias(25, "stony_shore");
        alias(3, "windswept_hills");
        alias(34, "windswept_forest");
        alias(131, "windswept_gravelly_hills");
        alias(163, "windswept_savanna");
        alias(38, "wooded_badlands");
    }
}
