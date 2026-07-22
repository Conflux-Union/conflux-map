package cn.net.rms.confluxmap.core.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Parsing and SemVer precedence of {@link ModVersion}. */
class ModVersionTest {

    private static ModVersion v(final String raw) {
        return ModVersion.parse(raw).orElseThrow(() -> new AssertionError("expected parseable: " + raw));
    }

    @Test
    void parsesCorePrereleaseAndRoundTrips() {
        assertEquals("1.2.3", v("1.2.3").toString());
        assertEquals("0.1.0-alpha.4", v("0.1.0-alpha.4").toString());
    }

    @Test
    void stripsLeadingVAndBuildMetadata() {
        assertEquals("0.1.0-alpha.4", v("v0.1.0-alpha.4").toString());
        assertEquals("0.1.0-alpha.4", v("V0.1.0-alpha.4").toString());
        assertEquals("1.2.3", v("1.2.3+build.77").toString());
        assertEquals(0, v("v1.2.3+a").compareTo(v("1.2.3+b")));
    }

    @Test
    void rejectsMalformedVersions() {
        for (final String raw : List.of(
            "", "v", "1", "1.2", "1.2.3.4", "a.b.c", "1.2.x", "1.2.3-", "1.2.3-a..b", "1.2.3-al_pha", "1..3"
        )) {
            assertTrue(ModVersion.parse(raw).isEmpty(), "expected rejection: " + raw);
        }
        assertTrue(ModVersion.parse(null).isEmpty());
    }

    @Test
    void ordersBySemverPrecedence() {
        final List<String> ascending = List.of(
            "1.0.0-alpha",
            "1.0.0-alpha.1",
            "1.0.0-alpha.beta",
            "1.0.0-beta",
            "1.0.0-beta.2",
            "1.0.0-beta.11",
            "1.0.0-rc.1",
            "1.0.0",
            "1.0.1",
            "1.1.0",
            "2.0.0"
        );
        for (int i = 1; i < ascending.size(); i++) {
            final ModVersion lower = v(ascending.get(i - 1));
            final ModVersion higher = v(ascending.get(i));
            assertTrue(lower.compareTo(higher) < 0, lower + " should precede " + higher);
            assertTrue(higher.compareTo(lower) > 0, higher + " should follow " + lower);
        }
    }

    @Test
    void numericPrereleaseIdentifiersCompareNumerically() {
        assertTrue(v("0.1.0-alpha.4").compareTo(v("0.1.0-alpha.10")) < 0);
        assertEquals(0, v("0.1.0-alpha.007").compareTo(v("0.1.0-alpha.7")));
    }

    @Test
    void equalityMatchesComparison() {
        assertEquals(v("v1.2.3-rc.1"), v("1.2.3-rc.1+meta"));
        assertEquals(v("1.2.3").hashCode(), v("v1.2.3").hashCode());
    }
}
