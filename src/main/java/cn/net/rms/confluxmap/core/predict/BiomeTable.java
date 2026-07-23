package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.model.SurfaceKind;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Static per-cubiomes-biome-id data used to turn a raw (biomeId, terrainY) baseline sample
 * into a renderable pixel: which {@link SurfaceKind} the biome renders as when the water rule
 * (see {@link BaselineDeriver}) doesn't apply, whether it counts as oceanic/river, how dense its
 * tree canopy is (see {@link CanopyStylizer}), whether its ground accepts a grass tint, and fallback tint/base
 * colors used until {@code mc.predict.PredictionPaletteBuilder} overwrites the tint fields with
 * real client biome-registry samples (or forever, for a biome id the running client doesn't
 * know about at all).
 *
 * <p>Ids and dimension/existence rules were read directly off the vendored {@code
 * native/cubiomes/biomes.c} ({@code biomeExists}/{@code isOverworld}/{@code getDimension}/{@code
 * isOceanic}), not guessed from changelogs. Every id generated in the Overworld by any supported
 * version, plus the End ids, has an entry here (see {@code BiomeTableTest} for the frozen parity
 * list). Aliases introduced by biome renames reuse their older numeric ids.
 *
 * <p>{@code // tuning data, not behavior}: treeCover values and the exact fallback colors below
 * are approximations the plan explicitly leaves to implementer judgment ("tune later"); nothing
 * downstream depends on their precise values being "correct", only on every known id having SOME
 * entry and the water/canopy/kind mechanics reading them consistently.
 */
public final class BiomeTable {
    private static final int NO_SNOW_LINE = Integer.MAX_VALUE;
    /** Pre-tint base ARGB per {@link SurfaceKind}; {@link PredictionPalette} copies these as its own per-kind defaults. */
    public static final int LAND_BASE = 0xFF96966E;
    public static final int SAND_BASE = 0xFFDDCE9B;
    public static final int SNOW_BASE = 0xFFF7FAFF;
    public static final int ICE_BASE = 0xFFA4C6E8;
    /** Deliberately not fully opaque (alpha 0xCC), mirroring vanilla's own semi-transparent water rendering. */
    public static final int WATER_BASE = 0xCCCFE0F2;
    public static final int FOLIAGE_BASE = 0xFF889F63;

    /** Fallback biome tints used before the live palette builds (or for an id it never sees). */
    public static final int DEFAULT_GRASS_TINT = 0xFF7CB556;
    public static final int DEFAULT_FOLIAGE_TINT = 0xFF59AE30;
    public static final int DEFAULT_WATER_TINT = 0xFF3F76E4;
    private static final int NEUTRAL_TINT = 0xFFFFFFFF;

    /** One biome id's kind/canopy/tint data. Tint fields are {@link PredictionPalette}'s starting point, not a hard rule. */
    public record Entry(
        SurfaceKind kind,
        boolean waterBiome,
        double treeCover,
        int groundBase,
        boolean grassTinted,
        int grassTint,
        int foliageTint,
        int waterTint
    ) {
    }

    private static final Map<Integer, Entry> TABLE = new HashMap<>();
    private static final Map<Integer, Integer> SNOW_LINES = new HashMap<>();
    private static final Set<Integer> END_IDS = Set.of(9, 40, 41, 42, 43);

    private BiomeTable() {
    }

    /** The known entry for {@code cubiomesBiomeId}, or a neutral land fallback if unrecognized (e.g. a modded/future id). */
    public static Entry get(final int cubiomesBiomeId) {
        return TABLE.getOrDefault(cubiomesBiomeId, DEFAULT_FALLBACK);
    }

    /** Every cubiomes biome id this table has a dedicated entry for (excludes the {@link #get} fallback). */
    public static Set<Integer> knownIds() {
        return TABLE.keySet();
    }

    /** Whether this cubiomes biome belongs to the End, where Overworld sea-level fill does not apply. */
    public static boolean isEnd(final int cubiomesBiomeId) {
        return END_IDS.contains(cubiomesBiomeId);
    }

    /**
     * Whether vanilla's altitude cooling would turn a predicted land surface into snow. The
     * exact game rule varies the cutoff by roughly four blocks using horizontal temperature
     * noise; prediction uses the deterministic midpoint because {@link BaselineGrid} carries no
     * temperature-noise sample.
     */
    public static boolean hasAltitudeSnow(final int cubiomesBiomeId, final int surfaceY) {
        return surfaceY >= SNOW_LINES.getOrDefault(cubiomesBiomeId, NO_SNOW_LINE);
    }

    private static final Entry DEFAULT_FALLBACK =
        new Entry(
            SurfaceKind.LAND,
            false,
            0.0,
            LAND_BASE,
            true,
            DEFAULT_GRASS_TINT,
            DEFAULT_FOLIAGE_TINT,
            DEFAULT_WATER_TINT
        );

    private static void put(final Entry entry, final int... ids) {
        for (final int id : ids) {
            TABLE.put(id, entry);
        }
    }

    private static void snowLine(final int surfaceY, final int... ids) {
        for (final int id : ids) {
            SNOW_LINES.put(id, surfaceY);
        }
    }

    private static Entry land(final double treeCover, final int grassTint, final int foliageTint) {
        return new Entry(
            SurfaceKind.LAND,
            false,
            treeCover,
            LAND_BASE,
            true,
            grassTint,
            foliageTint,
            DEFAULT_WATER_TINT
        );
    }

    private static Entry water(final boolean waterBiome, final int waterTint) {
        return new Entry(
            SurfaceKind.LAND,
            waterBiome,
            0.0,
            LAND_BASE,
            true,
            DEFAULT_GRASS_TINT,
            DEFAULT_FOLIAGE_TINT,
            waterTint
        );
    }

    private static Entry frozenWater(final int waterTint) {
        return new Entry(
            SurfaceKind.ICE,
            true,
            0.0,
            ICE_BASE,
            false,
            DEFAULT_GRASS_TINT,
            DEFAULT_FOLIAGE_TINT,
            waterTint
        );
    }

    static {
        // cubiomes biome ids, see native/cubiomes/biomes.h.
        final int ocean = 0, plains = 1, desert = 2, mountains = 3, forest = 4, taiga = 5, swamp = 6, river = 7;
        final int the_end = 9, frozen_ocean = 10, frozen_river = 11, snowy_tundra = 12, snowy_mountains = 13;
        final int mushroom_fields = 14, mushroom_field_shore = 15, beach = 16, desert_hills = 17, wooded_hills = 18;
        final int taiga_hills = 19, jungle = 21, jungle_hills = 22, jungle_edge = 23, deep_ocean = 24;
        final int stone_shore = 25, snowy_beach = 26, birch_forest = 27, birch_forest_hills = 28, dark_forest = 29;
        final int snowy_taiga = 30, snowy_taiga_hills = 31, giant_tree_taiga = 32, giant_tree_taiga_hills = 33;
        final int wooded_mountains = 34, savanna = 35, savanna_plateau = 36, badlands = 37;
        final int wooded_badlands_plateau = 38, badlands_plateau = 39;
        final int small_end_islands = 40, end_midlands = 41, end_highlands = 42, end_barrens = 43;
        final int warm_ocean = 44, lukewarm_ocean = 45, cold_ocean = 46;
        final int deep_lukewarm_ocean = 48, deep_cold_ocean = 49, deep_frozen_ocean = 50;
        final int sunflower_plains = 129, desert_lakes = 130, gravelly_mountains = 131, flower_forest = 132;
        final int taiga_mountains = 133, swamp_hills = 134, ice_spikes = 140, modified_jungle = 149;
        final int modified_jungle_edge = 151, tall_birch_forest = 155, tall_birch_hills = 156;
        final int dark_forest_hills = 157, snowy_taiga_mountains = 158, giant_spruce_taiga = 160;
        final int giant_spruce_taiga_hills = 161, modified_gravelly_mountains = 162, shattered_savanna = 163;
        final int shattered_savanna_plateau = 164, eroded_badlands = 165, modified_wooded_badlands_plateau = 166;
        final int modified_badlands_plateau = 167, bamboo_jungle = 168, bamboo_jungle_hills = 169;
        final int dripstone_caves = 174, lush_caves = 175, meadow = 177, grove = 178;
        final int snowy_slopes = 179, jagged_peaks = 180, frozen_peaks = 181, stony_peaks = 182;
        final int deep_dark = 183, mangrove_swamp = 184, cherry_grove = 185, pale_garden = 186;

        final int green = DEFAULT_GRASS_TINT;
        final int foliageGreen = DEFAULT_FOLIAGE_TINT;

        // Oceans (LAND kind is a near-unreachable fallback; the water rule almost always wins).
        put(water(true, DEFAULT_WATER_TINT), ocean, deep_ocean);
        put(water(true, 0xFF43D5EE), warm_ocean);
        put(water(true, 0xFF45ADF2), lukewarm_ocean, deep_lukewarm_ocean);
        put(water(true, 0xFF3D57D6), cold_ocean, deep_cold_ocean);
        put(frozenWater(0xFF3D57D6), frozen_ocean, deep_frozen_ocean);
        put(water(true, DEFAULT_WATER_TINT), river);
        put(frozenWater(DEFAULT_WATER_TINT), frozen_river);

        // Plains-like: sparse trees per the plan's "sparse 0.05 savanna/plains" guidance.
        put(land(0.02, green, foliageGreen), plains, sunflower_plains);

        // Desert (SAND kind, no vegetation).
        put(new Entry(
            SurfaceKind.SAND, false, 0.0, SAND_BASE, false, NEUTRAL_TINT, foliageGreen, DEFAULT_WATER_TINT
        ), desert, desert_hills, desert_lakes);

        // Mountains: bare rock is sparse, "wooded" variants have real forest cover.
        put(land(0.05, 0xFF8FA377, foliageGreen), mountains, gravelly_mountains, modified_gravelly_mountains);
        put(land(0.25, 0xFF7A9660, foliageGreen), wooded_mountains);

        // Forest family.
        put(
            land(0.35, 0xFF6FA84B, 0xFF52A62C),
            forest, wooded_hills, birch_forest, birch_forest_hills, flower_forest, tall_birch_forest, tall_birch_hills
        );
        put(land(0.5, 0xFF3F7A3A, 0xFF2E6E2E), dark_forest, dark_forest_hills);

        // Taiga family (non-snowy: LAND; snowy: SNOW per the plan's biome-kind table).
        put(land(0.2, 0xFF6C8E56, 0xFF3B6E3B), taiga, taiga_hills, taiga_mountains);
        put(land(0.4, 0xFF5E8250, 0xFF335F33), giant_tree_taiga, giant_tree_taiga_hills, giant_spruce_taiga, giant_spruce_taiga_hills);
        put(new Entry(
            SurfaceKind.SNOW, false, 0.2, SNOW_BASE, false, green, 0xFF3B6E3B, DEFAULT_WATER_TINT
        ), snowy_taiga, snowy_taiga_hills, snowy_taiga_mountains);

        // Jungle family.
        put(land(0.5, 0xFF59C93C, 0xFF44C430), jungle, jungle_hills, modified_jungle, bamboo_jungle, bamboo_jungle_hills);
        put(land(0.3, 0xFF6ED04F, 0xFF44C430), jungle_edge, modified_jungle_edge);

        // Savanna family.
        put(land(0.05, 0xFFBFB755, 0xFFAEA53E), savanna, savanna_plateau, shattered_savanna, shattered_savanna_plateau);

        // Badlands/mesa: terracotta look achieved via a warm grassTint over the neutral LAND base
        // (see this class's javadoc - deliberately not a distinct SurfaceKind).
        put(land(0.0, 0xFFD8956B, foliageGreen), badlands, badlands_plateau, eroded_badlands, modified_badlands_plateau);
        put(land(0.15, 0xFFD8956B, foliageGreen), wooded_badlands_plateau, modified_wooded_badlands_plateau);

        // Snowy tundra family (SNOW kind, no trees).
        put(new Entry(
            SurfaceKind.SNOW, false, 0.0, SNOW_BASE, false, green, foliageGreen, DEFAULT_WATER_TINT
        ), snowy_tundra, snowy_mountains, ice_spikes);

        // Mushroom fields: mycelium look via grassTint override, same trick as badlands.
        put(land(0.0, 0xFFAD9EBD, foliageGreen), mushroom_fields, mushroom_field_shore);

        // Beaches.
        put(new Entry(
            SurfaceKind.SAND, false, 0.0, SAND_BASE, false, NEUTRAL_TINT, foliageGreen, DEFAULT_WATER_TINT
        ), beach);
        put(new Entry(
            SurfaceKind.SNOW, false, 0.0, SNOW_BASE, false, NEUTRAL_TINT, foliageGreen, DEFAULT_WATER_TINT
        ), snowy_beach);

        // Stone shore: bare rock, no grass tint.
        put(land(0.0, NEUTRAL_TINT, foliageGreen), stone_shore);

        // Swamp: murky tints (including its own water color), sparse trees. Its ponds are not
        // modeled as water since cubiomes' isOceanic/river doesn't cover swamp - see this class's javadoc.
        put(new Entry(
            SurfaceKind.LAND, false, 0.15, LAND_BASE, true, 0xFF6A7039, 0xFF6A7039, 0xFF617B64
        ), swamp, swamp_hills);

        // Modern Overworld biomes. Cave biomes are retained because the 3D biome source may
        // expose them beside cave openings; surface-oriented sampling normally selects one of
        // the climate biomes below instead.
        put(land(0.0, 0xFF8B8068, 0xFF708050), dripstone_caves, deep_dark);
        put(land(0.12, 0xFF64A84A, 0xFF4FA63A), lush_caves);
        put(land(0.02, 0xFF83B55B, 0xFF63A947), meadow);
        put(new Entry(
            SurfaceKind.SNOW, false, 0.22, SNOW_BASE, false, green, 0xFF3B6E3B, DEFAULT_WATER_TINT
        ), grove);
        put(new Entry(
            SurfaceKind.SNOW, false, 0.0, SNOW_BASE, false, green, foliageGreen, DEFAULT_WATER_TINT
        ), snowy_slopes, jagged_peaks, frozen_peaks);
        put(land(0.0, NEUTRAL_TINT, foliageGreen), stony_peaks);
        put(new Entry(
            SurfaceKind.LAND, false, 0.45, LAND_BASE, true, 0xFF6A7039, 0xFF4C763C, 0xFF617B64
        ), mangrove_swamp);
        put(land(0.3, 0xFF83B55B, 0xFFFF91C8), cherry_grove);
        put(land(0.45, 0xFF77816E, 0xFF879184), pale_garden);

        // The End: pale end-stone look, no vegetation, never water.
        put(new Entry(
            SurfaceKind.LAND,
            false,
            0.0,
            MapColorTable.argb(2),
            false,
            NEUTRAL_TINT,
            foliageGreen,
            DEFAULT_WATER_TINT
        ),
            the_end, small_end_islands, end_midlands, end_highlands, end_barrens);

        // Vanilla 1.17.1 cools rainy biomes above Y=64 by 0.05 per 30 blocks. These
        // midpoint snow lines cover every generated biome cold enough to cross the 0.15 snow
        // threshold within the 1.17 build height; already-snowy biomes keep their SNOW kind.
        snowLine(95, mountains, wooded_mountains, gravelly_mountains, modified_gravelly_mountains, stone_shore);
        snowLine(125, taiga, taiga_hills, taiga_mountains, giant_spruce_taiga, giant_spruce_taiga_hills);
        snowLine(155, giant_tree_taiga, giant_tree_taiga_hills);
    }
}
