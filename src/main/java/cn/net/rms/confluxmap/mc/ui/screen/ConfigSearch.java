package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.compat.Texts;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Searches live translations and the shipped Chinese and English labels. */
final class ConfigSearch {
    private static final Pattern ARGUMENTS = Pattern.compile("%(?:[0-9]+\\$)?[sd]");
    private static final Pattern SEPARATOR = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Map<String, String> CHINESE = translations("zh_cn");
    private static final Map<String, String> ENGLISH = translations("en_us");

    private final List<String> terms;
    private final Map<String, String> indexedLabels = new HashMap<>();

    ConfigSearch(String query) {
        String normalized = normalize(query);
        terms = normalized.isEmpty() ? List.of() : List.of(normalized.split(" "));
    }

    boolean active() { return !terms.isEmpty(); }

    boolean matches(String labelKey, String tooltipKey, String categoryKey) {
        if (!active()) return true;
        String text = index(labelKey) + " " + index(tooltipKey) + " " + index(categoryKey);
        return terms.stream().allMatch(text::contains);
    }

    private String index(String key) {
        if (key == null) return "";
        return indexedLabels.computeIfAbsent(key, k -> normalize(k + " " + Texts.translatable(k).getString()
            + " " + CHINESE.getOrDefault(k, "") + " " + ENGLISH.getOrDefault(k, "")));
    }

    private static String normalize(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return SEPARATOR.matcher(ARGUMENTS.matcher(normalized).replaceAll(" ")).replaceAll(" ").trim();
    }

    private static Map<String, String> translations(String language) {
        try (var stream = ConfigSearch.class.getResourceAsStream("/assets/confluxmap/lang/" + language + ".json")) {
            if (stream == null) return Map.of();
            var document = new Gson().fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8), JsonObject.class);
            if (document == null) return Map.of();
            Map<String, String> result = new HashMap<>();
            document.entrySet().forEach(entry -> result.put(entry.getKey(), entry.getValue().getAsString()));
            return Map.copyOf(result);
        } catch (IOException | RuntimeException error) {
            cn.net.rms.confluxmap.ConfluxMapMod.LOGGER.warn("Cannot read {} settings search labels", language, error);
            return Map.of();
        }
    }
}
