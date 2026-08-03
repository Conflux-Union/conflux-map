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
            "confluxmap.map.structure_search",
            "confluxmap.map.structure_search.tooltip",
            "confluxmap.map.structure_search.unavailable",
            "confluxmap.map.structure.candidate_tooltip",
            "confluxmap.map.structure.verified_tooltip",
            "confluxmap.screen.structure_search.title",
            "confluxmap.screen.structure_search.field",
            "confluxmap.screen.structure_search.prompt",
            "confluxmap.screen.structure_search.master",
            "confluxmap.screen.structure_search.locate",
            "confluxmap.screen.structure_search.back",
            "confluxmap.screen.structure_search.empty"
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
