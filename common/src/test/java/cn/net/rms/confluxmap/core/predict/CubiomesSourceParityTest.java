package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Parity guard between the Java-side prediction tables and the vendored cubiomes sources
 * themselves. {@code BiomeTableTest} freezes today's known-id set by hand, which is exactly how
 * the 26.3 port shipped without {@code dappled_forest}: the submodule pin gained the biome, the
 * frozen list did not, and both parity assertions stayed green inside the stale set. This test
 * instead derives, at test time, which biome ids the pinned cubiomes commit can generate and
 * fails when either table has not caught up - so the next submodule bump that adds a biome breaks
 * the build here instead of shipping a biome the underlay renders as anonymous grass.
 *
 * <p>Derivation parses {@code enum BiomeID} from {@code native/cubiomes/biomes.h} and evaluates
 * {@code biomeExists} plus {@code isOverworld} from {@code native/cubiomes/biomes.c} at the
 * newest supported version. Evaluating at the newest version keeps the C interpretation trivial:
 * every {@code mc >= MC_X} gate is true and every {@code mc <= MC_X} gate is false, the pre-1.18
 * branch of biomeExists is unreachable, and no version numbering is ever needed. {@code
 * biomeExists} alone is not the right set: it also admits registry-only ids vanilla's climate no
 * longer generates (deep_warm_ocean), which isOverworld zeroes out, so the Overworld side comes
 * from isOverworld and the Nether/End sides from biomeExists restricted to those dimensions'
 * ranges. Anything the evaluator does not recognize fails loudly instead of being skipped - if
 * cubiomes restructures these functions, this parser must be taught the new shape on purpose.
 * Skipped entirely when the submodule is not checked out, matching the native integration
 * tests' assumption policy.
 */
class CubiomesSourceParityTest {
    private static Map<String, Long> enumIds;
    private static Set<Long> generatable;
    private static String biomeExistsBody;

    @BeforeAll
    static void deriveFromVendoredCubiomes() {
        final Path header = repoFile("native/cubiomes/biomes.h");
        final Path source = repoFile("native/cubiomes/biomes.c");
        Assumptions.assumeTrue(
            header != null && source != null,
            "native/cubiomes is not checked out; skipping cubiomes-source parity"
        );
        enumIds = parseBiomeEnum(block(read(header), "enum BiomeID"));
        // Anchors proving the enum parser survived a cubiomes reshuffle: implicit numbering,
        // same-line aliases, and the +128 mutated-variant expressions must all resolve.
        assertEquals(0L, enumIds.get("ocean"));
        assertEquals(21L, enumIds.get("jungle"));
        assertEquals(127L, enumIds.get("the_void"));
        assertEquals(188L, enumIds.get("dappled_forest"));

        final String biomeExists = block(read(source), "int biomeExists");
        biomeExistsBody = biomeExists;
        final String isOverworld = block(read(source), "int isOverworld");
        generatable = new TreeSet<>();
        for (final long id : new TreeSet<>(enumIds.values())) {
            if (id < 0) {
                continue;
            }
            final Boolean overworld = evalSequence(isOverworld, 0, isOverworld.length(), id);
            assertTrue(overworld != null, "isOverworld fell through without returning for id " + id);
            if (overworld) {
                generatable.add(id);
                continue;
            }
            if (!isNetherOrEnd(id)) {
                continue;
            }
            final Boolean exists = evalSequence(biomeExists, 0, biomeExists.length(), id);
            assertTrue(exists != null, "biomeExists fell through without returning for id " + id);
            if (exists) {
                generatable.add(id);
            }
        }
        assertTrue(generatable.size() > 50, "the parser lost most of the biome set");
        // The ids this guard exists for must be derivable end to end.
        assertTrue(generatable.contains(186L), "pale_garden (186) must generate");
        assertTrue(generatable.contains(187L), "sulfur_caves (187) must generate");
        assertTrue(generatable.contains(188L), "dappled_forest (188) must generate");
    }

    @Test
    void everyGeneratableBiomeHasABiomeTableEntry() {
        final Set<Integer> missing = new TreeSet<>();
        for (final long id : generatable) {
            if (!BiomeTable.knownIds().contains((int) id)) {
                missing.add((int) id);
            }
        }
        assertTrue(missing.isEmpty(), () -> "BiomeTable has no entry for cubiomes biome id(s) "
            + missing + " (" + firstDeclaredNames(missing) + ") that the pinned cubiomes commit "
            + "generates. Add entries for the new biomes (see the pale_garden/dappled_forest "
            + "precedents) so the underlay does not render them as the neutral land fallback.");
    }

    @Test
    void everyGeneratableBiomeIsResolvableByName() {
        final Set<Integer> missing = new TreeSet<>();
        for (final long id : generatable) {
            if (CubiomesBiomeIds.idForName(firstDeclaredName((int) id)).isEmpty()) {
                missing.add((int) id);
            }
        }
        assertTrue(missing.isEmpty(), () -> "CubiomesBiomeIds cannot resolve " + missing
            + " (" + firstDeclaredNames(missing) + ") by their enum name, so "
            + "PredictionPaletteBuilder would skip their live registry tint samples and the "
            + "underlay would keep the fallback colors forever.");
    }

    private static String firstDeclaredName(final int id) {
        for (final Map.Entry<String, Long> entry : enumIds.entrySet()) {
            if (entry.getValue() == id) {
                return entry.getKey();
            }
        }
        throw new IllegalStateException("enum BiomeID has no member with id " + id);
    }

    private static String firstDeclaredNames(final Set<Integer> ids) {
        final List<String> names = new ArrayList<>();
        for (final int id : ids) {
            names.add(firstDeclaredName(id));
        }
        return names.toString();
    }

    private static String read(final Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path repoFile(final String relative) {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null) {
            final Path candidate = dir.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        return null;
    }

    /** The text between {@code marker}'s opening brace and the next column-zero closing brace. */
    private static String block(final String text, final String marker) {
        final int start = text.indexOf(marker);
        assertTrue(start >= 0, marker + " not found");
        final int open = text.indexOf('{', start);
        final int close = text.indexOf("\n}", open);
        assertTrue(open >= 0 && close > open, "column-zero terminator for " + marker + " not found");
        return text.substring(open + 1, close).replaceAll("//[^\n]*", "");
    }

    /**
     * Resolves {@code enum BiomeID} declarations: bare names count up implicitly, {@code name = 7}
     * sets an explicit value, {@code name = other} aliases an earlier member, and the mutated
     * variants use {@code name = other+128}.
     */
    private static Map<String, Long> parseBiomeEnum(final String body) {
        final Map<String, Long> ids = new LinkedHashMap<>();
        long implicit = 0;
        for (final String token : body.split(",")) {
            final String declaration = token.trim();
            if (declaration.isEmpty()) {
                continue;
            }
            final int eq = declaration.indexOf('=');
            if (eq < 0) {
                ids.putIfAbsent(declaration, implicit++);
                continue;
            }
            final String name = declaration.substring(0, eq).trim();
            final String value = declaration.substring(eq + 1).trim();
            final long resolved;
            if (value.matches("-?\\d+")) {
                resolved = Long.parseLong(value);
            } else if (value.matches("\\w+\\s*\\+\\s*\\d+")) {
                final String[] parts = value.split("\\+");
                final Long base = ids.get(parts[0].trim());
                assertTrue(base != null, "enum alias " + value + " references an unseen member");
                resolved = base + Long.parseLong(parts[1].trim());
            } else {
                final Long base = ids.get(value);
                assertTrue(base != null, "enum alias " + value + " references an unseen member");
                resolved = base;
            }
            ids.put(name, resolved);
            implicit = resolved + 1;
        }
        return ids;
    }

    /**
     * Evaluates one statement sequence for a biome id at the newest supported version, returning
     * the first {@code return} reached, or null when the sequence falls through (a {@code break}
     * out of a switch, or the end of a skipped block).
     */
    private static Boolean evalSequence(final String src, final int from, final int to, final long id) {
        int i = from;
        while (i < to) {
            while (i < to && Character.isWhitespace(src.charAt(i))) {
                i++;
            }
            if (i >= to || src.charAt(i) == '}') {
                break;
            }
            if (src.startsWith("if (", i)) {
                final int condEnd = matching(src, i + 3, '(', ')');
                final String condition = src.substring(i + 4, condEnd).trim();
                final int[] extent = consequenceExtent(src, condEnd + 1);
                if (evalCondition(condition, id)) {
                    final Boolean hit = evalSequence(src, extent[0], extent[1], id);
                    if (hit != null) {
                        return hit;
                    }
                }
                i = extent[1];
            } else if (src.startsWith("switch (id)", i)) {
                final int open = src.indexOf('{', i);
                final int close = matching(src, open, '{', '}');
                final Boolean hit = evalSwitch(src, open + 1, close, id);
                if (hit != null) {
                    return hit;
                }
                i = close + 1;
            } else if (src.startsWith("return", i)) {
                final int semi = src.indexOf(';', i);
                return evalReturnExpr(src.substring(i + 6, semi));
            } else {
                throw new AssertionError("cannot parse cubiomes statement near: "
                    + src.substring(i, Math.min(i + 60, to)).replace('\n', ' '));
            }
        }
        return null;
    }

    /** Extent {@code [from, to)} of the braced block or single statement after a condition. */
    private static int[] consequenceExtent(final String src, final int from) {
        int i = from;
        while (i < src.length() && Character.isWhitespace(src.charAt(i))) {
            i++;
        }
        if (src.charAt(i) == '{') {
            final int close = matching(src, i, '{', '}');
            return new int[] {i + 1, close};
        }
        final int semi = src.indexOf(';', i);
        return new int[] {i, semi + 1};
    }

    private static Boolean evalSwitch(final String src, final int from, final int to, final long id) {
        final List<String> labels = new ArrayList<>();
        int i = from;
        while (i < to) {
            if (src.startsWith("case ", i)) {
                final int colon = src.indexOf(':', i);
                labels.add(src.substring(i + 5, colon).trim());
                i = colon + 1;
            } else if (src.startsWith("default:", i)) {
                labels.add("default");
                i += 8;
            } else if (src.startsWith("return", i)) {
                final int semi = src.indexOf(';', i);
                final String expr = src.substring(i + 6, semi);
                boolean matches = labels.contains("default");
                for (final String label : labels) {
                    if (label.equals("default")) {
                        continue;
                    }
                    final Long labelId = enumIds.get(label);
                    assertTrue(labelId != null, "switch label " + label + " is missing from enum BiomeID");
                    matches |= labelId == id;
                }
                if (matches) {
                    return evalReturnExpr(expr);
                }
                labels.clear();
                i = semi + 1;
            } else if (src.startsWith("break", i)) {
                return null;
            } else {
                i++;
            }
        }
        return null;
    }

    private static boolean isNetherOrEnd(final long id) {
        final long soulSandValley = enumIds.get("soul_sand_valley");
        final long basaltDeltas = enumIds.get("basalt_deltas");
        final long smallEndIslands = enumIds.get("small_end_islands");
        final long endBarrens = enumIds.get("end_barrens");
        return id == enumIds.get("nether_wastes") || id == enumIds.get("the_end")
            || (id >= soulSandValley && id <= basaltDeltas)
            || (id >= smallEndIslands && id <= endBarrens);
    }

    private static boolean evalCondition(final String condition, final long id) {
        final String cond = condition.trim();
        if (cond.startsWith("mc >=")) {
            return true;
        }
        if (cond.startsWith("mc <=")) {
            return false;
        }
        if (cond.startsWith("!biomeExists")) {
            final Boolean exists =
                evalSequence(biomeExistsBody, 0, biomeExistsBody.length(), id);
            assertTrue(exists != null, "biomeExists fell through without returning for id " + id);
            return !exists;
        }
        if (cond.startsWith("id >=") && cond.contains("&&")) {
            final String[] parts = cond.split("&&");
            final long lo = enumValue(parts[0].trim(), "id >=");
            final long hi = enumValue(parts[1].trim(), "id <=");
            return id >= lo && id <= hi;
        }
        throw new AssertionError("cannot evaluate cubiomes condition: " + cond);
    }

    private static boolean evalReturnExpr(final String expr) {
        for (final String term : expr.split("\\|\\|")) {
            final String t = term.trim();
            if (t.equals("1")) {
                return true;
            }
            if (t.equals("0")) {
                return false;
            }
            if (t.startsWith("mc >")) {
                return true;
            }
            if (t.startsWith("mc <")) {
                return false;
            }
            throw new AssertionError("cannot evaluate cubiomes return expression: " + expr);
        }
        return false;
    }

    private static long enumValue(final String comparison, final String prefix) {
        final String name = comparison.substring(prefix.length()).trim();
        final Long value = enumIds.get(name);
        assertTrue(value != null, "comparison " + comparison + " references unknown enum member " + name);
        return value;
    }

    private static int matching(final String src, final int open, final char openCh, final char closeCh) {
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            final char c = src.charAt(i);
            if (c == openCh) {
                depth++;
            } else if (c == closeCh && --depth == 0) {
                return i;
            }
        }
        throw new AssertionError("unbalanced " + openCh + " in cubiomes source");
    }
}
