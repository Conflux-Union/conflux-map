package cn.net.rms.confluxmap.core.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class LanguageResourceTest {
    private static final Pattern STRING_PLACEHOLDER = Pattern.compile("%(?:\\d+\\$)?s");

    @Test
    void localesHaveMatchingKeysAndPlaceholders() {
        final Map<String, String> english = translations("en_us");
        final Map<String, String> chinese = translations("zh_cn");

        assertEquals(english.keySet(), chinese.keySet(), "locale keys differ");
        for (final Map.Entry<String, String> entry : english.entrySet()) {
            final String key = entry.getKey();
            final String englishValue = entry.getValue();
            final String chineseValue = chinese.get(key);
            assertFalse(englishValue.isBlank(), "blank English translation for " + key);
            assertFalse(chineseValue.isBlank(), "blank Chinese translation for " + key);
            assertEquals(
                placeholderCount(englishValue),
                placeholderCount(chineseValue),
                "placeholder count differs for " + key
            );
        }
    }

    @Test
    void chineseSurveyReminderMatchesTheRequestedChatCopy() {
        final Map<String, String> chinese = translations("zh_cn");

        assertEquals(
            "想要我们做的更好?[点击此处]填写Conflux Map的调查问卷[不再提示]",
            chinese.get("confluxmap.survey.chat.intro")
                + chinese.get("confluxmap.survey.chat.open")
                + chinese.get("confluxmap.survey.chat.body")
                + chinese.get("confluxmap.survey.chat.dismiss")
        );
    }

    private static long placeholderCount(final String value) {
        final Matcher matcher = STRING_PLACEHOLDER.matcher(value);
        long count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static Map<String, String> translations(final String locale) {
        final String resource = "/assets/confluxmap/lang/" + locale + ".json";
        final InputStream stream = LanguageResourceTest.class.getResourceAsStream(resource);
        assertNotNull(stream, "missing language resource " + resource);
        return new Gson().fromJson(
            new InputStreamReader(stream, StandardCharsets.UTF_8),
            new TypeToken<Map<String, String>>() { }.getType()
        );
    }
}
