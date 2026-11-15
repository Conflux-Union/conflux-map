package cn.net.rms.confluxmap.core.i18n;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The loot panel's container header probes {@code confluxmap.loot_table.chests.<table>} and
 * falls back to the raw table id when the key is missing, so a table the native fork learns to
 * roll must ship its container names here or players see the untranslated id.
 */
class ChestLootTableLangTest {

    /** Every chest table under the fork's {@code loot_tables/chests/}. */
    private static final List<String> CHEST_TABLES = List.of(
        "abandoned_camp_common_chest",
        "abandoned_camp_secret_chest",
        "bastion_bridge",
        "bastion_other",
        "buried_treasure",
        "desert_pyramid",
        "end_city_treasure",
        "igloo_chest",
        "jungle_temple",
        "jungle_temple_dispenser",
        "nether_bridge",
        "pillager_outpost",
        "ruined_portal",
        "shipwreck_map",
        "shipwreck_supply",
        "shipwreck_treasure",
        "stronghold_corridor",
        "stronghold_crossing",
        "stronghold_library"
    );

    @Test
    void everyChestLootTableHasContainerNamesInBothLocales() throws IOException {
        for (final String locale : List.of("en_us", "zh_cn")) {
            final Map<String, String> translations = translations(locale);
            for (final String table : CHEST_TABLES) {
                final String key = "confluxmap.loot_table.chests." + table;
                final String value = translations.get(key);
                assertTrue(value != null && !value.isBlank(), locale + " missing " + key);
            }
        }
    }

    private static Map<String, String> translations(final String locale) throws IOException {
        try (InputStream input = ChestLootTableLangTest.class.getResourceAsStream(
            "/assets/confluxmap/lang/" + locale + ".json"
        )) {
            if (input == null) {
                throw new IOException("missing language resource for " + locale);
            }
            final Map<String, String> translations = new Gson().fromJson(
                new InputStreamReader(input, StandardCharsets.UTF_8),
                new com.google.gson.reflect.TypeToken<Map<String, String>>() {}.getType()
            );
            assertFalse(translations == null || translations.isEmpty(), "empty " + locale);
            return translations;
        }
    }
}
