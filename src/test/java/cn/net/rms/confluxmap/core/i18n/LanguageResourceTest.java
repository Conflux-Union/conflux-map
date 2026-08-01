package cn.net.rms.confluxmap.core.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class LanguageResourceTest {
    /**
     * Language values are restricted to {@code %s} so placeholder counting stays exact. A stray
     * {@code %d} would still format at runtime but would silently weaken every count below.
     */
    private static final Pattern PLACEHOLDER = Pattern.compile("%(?:\\d+\\$)?[a-zA-Z]");
    private static final Pattern STRING_LITERAL = Pattern.compile("\"([A-Za-z][A-Za-z0-9_.]*)\"");
    /** A fully spelled key: lowercase dotted segments under one of the three shipped namespaces. */
    private static final Pattern TRANSLATION_KEY =
        Pattern.compile("(?:confluxmap|key|gui)\\.[a-z0-9_]+(?:\\.[a-z0-9_]+)+");
    /** A key prefix completed at runtime, such as {@code "confluxmap.layer." + layer.id()}. */
    private static final Pattern TRANSLATION_KEY_PREFIX =
        Pattern.compile("(?:confluxmap|key|gui)\\.(?:[a-z0-9_]+\\.)+");
    private static final Pattern TRANSLATABLE_CALL = Pattern.compile("translatable\\(");
    /** An inactive preprocessor branch, which the normalizer promotes back to ordinary code. */
    private static final Pattern INACTIVE_BRANCH = Pattern.compile("(?m)^([ \\t]*)//\\$\\$ ?");

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
            assertOnlyStringPlaceholders(key, englishValue);
            assertOnlyStringPlaceholders(key, chineseValue);
            assertEquals(
                placeholderCount(englishValue),
                placeholderCount(chineseValue),
                "placeholder count differs for " + key
            );
        }
    }

    /**
     * Comparing the two locales against each other only proves they agree. A symmetric deletion —
     * a merge that drops the same block from both files — keeps that agreement while breaking the
     * UI, so the shipped keys also have to be checked against the code that asks for them.
     */
    @Test
    void everyKeyRequestedBySourceIsTranslated() throws Exception {
        final Set<String> translated = translations("en_us").keySet();
        final SourceKeys source = sourceKeys();

        final Set<String> missing = new TreeSet<>();
        for (final String key : source.keys()) {
            if (!translated.contains(key)) {
                missing.add(key);
            }
        }
        assertTrue(missing.isEmpty(), "keys used in src/main/java but missing from the language files: " + missing);

        for (final String prefix : source.prefixes()) {
            assertTrue(
                translated.stream().anyMatch(key -> key.startsWith(prefix)),
                "no translation left for the runtime-completed prefix " + prefix
            );
        }
    }

    /** The other direction: a renamed key must not leave its predecessor behind forever. */
    @Test
    void everyTranslationIsRequestedBySource() throws Exception {
        final SourceKeys source = sourceKeys();

        final Set<String> unused = new TreeSet<>();
        for (final String key : translations("en_us").keySet()) {
            if (!source.covers(key)) {
                unused.add(key);
            }
        }
        assertTrue(unused.isEmpty(), "translations no longer requested by src/main/java: " + unused);
    }

    /**
     * Placeholder parity between locales says nothing about the call site. A key formatted with too
     * few arguments renders its {@code %s} literally, and a surplus argument is dropped without a
     * trace. Call sites whose key is a variable stay unverifiable and are skipped.
     */
    @Test
    void placeholderUseMatchesCallSites() throws Exception {
        final Map<String, String> english = translations("en_us");
        final List<String> mismatches = new ArrayList<>();
        for (final Path file : sourceFiles()) {
            final String source = normalizedSource(file);
            final Matcher call = TRANSLATABLE_CALL.matcher(source);
            while (call.find()) {
                final List<String> arguments = callArguments(source, call.end() - 1);
                if (arguments == null || arguments.isEmpty()) {
                    continue;
                }
                // A ternary of two literal keys is a single argument, so both branches get checked
                // against the same argument count.
                final Matcher literal = STRING_LITERAL.matcher(arguments.get(0));
                while (literal.find()) {
                    final String value = english.get(literal.group(1));
                    if (value == null) {
                        continue;
                    }
                    final long placeholders = placeholderCount(value);
                    if (placeholders != arguments.size() - 1) {
                        mismatches.add(file.getFileName() + ": " + literal.group(1) + " has "
                            + placeholders + " placeholder(s) but is formatted with "
                            + (arguments.size() - 1) + " argument(s)");
                    }
                }
            }
        }
        assertTrue(mismatches.isEmpty(), String.join("\n", mismatches));
    }

    // The Chinese survey copy was specified verbatim, so this locks the exact wording and the
    // fragment order the chat message is assembled from.
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

    /**
     * The prompt has to name whatever key the player actually bound, so the placeholder is the
     * contract. Asserting the whole sentence would instead fail on harmless rewording.
     */
    @Test
    void ambiguousWorldPromptAcceptsTheCurrentOpenMapKey() {
        for (final String locale : List.of("en_us", "zh_cn")) {
            final String prompt = translations(locale).get("confluxmap.client_world.ambiguous_chat");
            assertNotNull(prompt, "missing ambiguous world prompt in " + locale);
            assertEquals(
                1,
                placeholderCount(prompt),
                "the ambiguous world prompt in " + locale + " must take exactly the open-map key"
            );
        }
    }

    private static void assertOnlyStringPlaceholders(final String key, final String value) {
        final Matcher matcher = PLACEHOLDER.matcher(value);
        while (matcher.find()) {
            assertEquals(
                "%s",
                matcher.group().replaceFirst("\\d+\\$", ""),
                "unsupported placeholder conversion in " + key
            );
        }
    }

    private static long placeholderCount(final String value) {
        final Matcher matcher = PLACEHOLDER.matcher(value);
        long count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static SourceKeys sourceKeys() throws IOException, URISyntaxException {
        final Set<String> keys = new TreeSet<>();
        final Set<String> prefixes = new TreeSet<>();
        for (final Path file : sourceFiles()) {
            final Matcher literal = STRING_LITERAL.matcher(normalizedSource(file));
            while (literal.find()) {
                final String value = literal.group(1);
                if (TRANSLATION_KEY.matcher(value).matches()) {
                    keys.add(value);
                } else if (TRANSLATION_KEY_PREFIX.matcher(value).matches()) {
                    prefixes.add(value);
                }
            }
        }
        return new SourceKeys(keys, prefixes);
    }

    /**
     * Promotes inactive {@code //$$} branches back to code and blanks every comment, so a newer
     * version's call sites are scanned exactly like the core version's and no key, comma, or
     * parenthesis mentioned in prose can reach the scanners. Order matters: the branch markers have
     * to go first, otherwise the whole branch reads as a comment.
     */
    private static String normalizedSource(final Path file) throws IOException {
        final String branchesRestored = INACTIVE_BRANCH.matcher(Files.readString(file)).replaceAll("$1");
        final StringBuilder normalized = new StringBuilder(branchesRestored.length());
        for (int index = 0; index < branchesRestored.length(); index++) {
            final char character = branchesRestored.charAt(index);
            if (character == '"' || character == '\'') {
                index = appendLiteral(branchesRestored, index, normalized);
            } else if (isCommentStart(branchesRestored, index, '/')) {
                while (index < branchesRestored.length() && branchesRestored.charAt(index) != '\n') {
                    index++;
                }
                normalized.append('\n');
            } else if (isCommentStart(branchesRestored, index, '*')) {
                index += 2;
                while (index + 1 < branchesRestored.length()
                    && !(branchesRestored.charAt(index) == '*' && branchesRestored.charAt(index + 1) == '/')) {
                    index++;
                }
                index++;
            } else {
                normalized.append(character);
            }
        }
        return normalized.toString();
    }

    /**
     * Splits one call's argument list on top-level commas. Returns null for an unbalanced list,
     * which a scanner must skip rather than guess at.
     */
    private static List<String> callArguments(final String source, final int openParenthesis) {
        final List<String> arguments = new ArrayList<>();
        final StringBuilder current = new StringBuilder();
        int depth = 0;
        for (int index = openParenthesis; index < source.length(); index++) {
            final char character = source.charAt(index);
            if (character == '"' || character == '\'') {
                index = appendLiteral(source, index, current);
            } else if (character == '(' || character == '[' || character == '{') {
                depth++;
                if (depth > 1) {
                    current.append(character);
                }
            } else if (character == ')' || character == ']' || character == '}') {
                depth--;
                if (depth == 0) {
                    arguments.add(current.toString());
                    return arguments;
                }
                current.append(character);
            } else if (character == ',' && depth == 1) {
                arguments.add(current.toString());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        return null;
    }

    /** Copies a string or char literal verbatim and returns the index of its closing quote. */
    private static int appendLiteral(final String source, final int start, final StringBuilder target) {
        final char quote = source.charAt(start);
        target.append(quote);
        int index = start + 1;
        while (index < source.length()) {
            final char character = source.charAt(index);
            target.append(character);
            if (character == '\\' && index + 1 < source.length()) {
                target.append(source.charAt(index + 1));
                index += 2;
                continue;
            }
            if (character == quote) {
                return index;
            }
            index++;
        }
        return index;
    }

    private static boolean isCommentStart(final String source, final int index, final char second) {
        return source.charAt(index) == '/' && index + 1 < source.length()
            && source.charAt(index + 1) == second;
    }

    /**
     * Reads the shared sources rather than one version's preprocessed output, so keys that only a
     * {@code //#if} branch reaches still count as used.
     */
    private static List<Path> sourceFiles() throws IOException, URISyntaxException {
        try (Stream<Path> paths = Files.walk(sourceRoot())) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".java")).toList();
        }
    }

    private static Path sourceRoot() throws URISyntaxException {
        Path current = Path.of(
            LanguageResourceTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()
        );
        while (current != null) {
            final Path candidate = current.resolve("src/main/java");
            // Every version subproject declares an empty src/main/java, so the marker class is what
            // distinguishes the repository root from a version directory on the way up.
            if (Files.exists(candidate.resolve("cn/net/rms/confluxmap/ConfluxMapClient.java"))) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate src/main/java from the test classpath");
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

    private record SourceKeys(Set<String> keys, Set<String> prefixes) {
        boolean covers(final String key) {
            return keys.contains(key) || prefixes.stream().anyMatch(key::startsWith);
        }
    }
}
