package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StructureTranslationTest {
    @Test
    void everyStructureHasEnglishAndChineseNames() {
        final Map<String, String> english = translations("en_us");
        final Map<String, String> chinese = translations("zh_cn");

        for (final StructureIndex.StructureType type : StructureIndex.StructureType.values()) {
            final String key = type.translationKey();
            assertTrue(english.containsKey(key), "missing English translation for " + key);
            assertTrue(chinese.containsKey(key), "missing Chinese translation for " + key);
            assertTrue(!english.get(key).isBlank(), "blank English translation for " + key);
            assertTrue(!chinese.get(key).isBlank(), "blank Chinese translation for " + key);
        }
        assertEquals(english.keySet().stream().filter(key -> key.startsWith("confluxmap.structure.")).count(),
            chinese.keySet().stream().filter(key -> key.startsWith("confluxmap.structure.")).count());
        for (final String key : new String[] {
            "confluxmap.map.search",
            "confluxmap.map.search.tooltip",
            "confluxmap.map.search.unavailable",
            "confluxmap.map.structure_search.disabled_by_server",
            "confluxmap.map.structure.candidate_tooltip",
            "confluxmap.map.structure.verified_tooltip",
            "confluxmap.screen.map_search.title",
            "confluxmap.screen.map_search.mode.structure",
            "confluxmap.screen.map_search.mode.biome",
            "confluxmap.screen.structure_search.field",
            "confluxmap.screen.structure_search.prompt",
            "confluxmap.screen.structure_search.locate",
            "confluxmap.screen.structure_search.back",
            "confluxmap.screen.structure_search.empty",
            "confluxmap.screen.biome_search.field",
            "confluxmap.screen.biome_search.prompt",
            "confluxmap.screen.biome_search.empty",
            "confluxmap.screen.biome_candidates.title",
            "confluxmap.screen.biome_candidates.searching",
            "confluxmap.screen.biome_candidates.searching_button",
            "confluxmap.screen.biome_candidates.not_found",
            "confluxmap.screen.biome_candidates.not_found_no_prediction",
            "confluxmap.screen.biome_candidates.failed"
        }) {
            assertTrue(english.containsKey(key), "missing English translation for " + key);
            assertTrue(chinese.containsKey(key), "missing Chinese translation for " + key);
        }

        for (final String key : new String[] {
            StructureIndex.StructureType.VILLAGE.variantTranslationKey(0),
            StructureIndex.StructureType.VILLAGE.variantTranslationKey(1),
            StructureIndex.StructureType.VILLAGE.variantTranslationKey(2),
            StructureIndex.StructureType.VILLAGE.variantTranslationKey(3),
            StructureIndex.StructureType.VILLAGE.variantTranslationKey(4),
            StructureIndex.StructureType.VILLAGE.variantTranslationKey(8),
            StructureIndex.StructureType.VILLAGE.variantTranslationKey(9),
            StructureIndex.StructureType.VILLAGE.variantTranslationKey(10),
            StructureIndex.StructureType.VILLAGE.variantTranslationKey(11),
            StructureIndex.StructureType.VILLAGE.variantTranslationKey(12),
            StructureIndex.StructureType.IGLOO.variantTranslationKey(0),
            StructureIndex.StructureType.IGLOO.variantTranslationKey(1),
            StructureIndex.StructureType.SHIPWRECK.variantTranslationKey(0),
            StructureIndex.StructureType.SHIPWRECK.variantTranslationKey(1),
            StructureIndex.StructureType.BASTION_REMNANT.variantTranslationKey(0),
            StructureIndex.StructureType.BASTION_REMNANT.variantTranslationKey(1),
            StructureIndex.StructureType.BASTION_REMNANT.variantTranslationKey(2),
            StructureIndex.StructureType.BASTION_REMNANT.variantTranslationKey(3),
            StructureIndex.StructureType.RUINED_PORTAL.variantTranslationKey(0),
            StructureIndex.StructureType.RUINED_PORTAL.variantTranslationKey(1),
            StructureIndex.StructureType.END_CITY.variantTranslationKey(0),
            StructureIndex.StructureType.END_CITY.variantTranslationKey(1)
        }) {
            assertTrue(english.containsKey(key), "missing English translation for " + key);
            assertTrue(chinese.containsKey(key), "missing Chinese translation for " + key);
        }
    }

    private static Map<String, String> translations(final String locale) {
        final String resource = "/assets/confluxmap/lang/" + locale + ".json";
        final InputStream stream = StructureTranslationTest.class.getResourceAsStream(resource);
        assertNotNull(stream, "missing language resource " + resource);
        return new Gson().fromJson(
            new InputStreamReader(stream, StandardCharsets.UTF_8),
            new TypeToken<Map<String, String>>() { }.getType()
        );
    }
}
