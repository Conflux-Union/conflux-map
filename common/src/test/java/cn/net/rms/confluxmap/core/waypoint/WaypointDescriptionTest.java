package cn.net.rms.confluxmap.core.waypoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class WaypointDescriptionTest {
    @Test
    void nullBlankAndEmptyValuesNormalizeToEmptyString() {
        assertEquals("", WaypointDescription.normalize(null));
        assertEquals("", WaypointDescription.normalize(""));
        assertEquals("", WaypointDescription.normalize("   "));
        assertEquals("", WaypointDescription.normalize("\n \n"));
    }

    @Test
    void carriageReturnsAndTrailingSpacesAreStrippedPerLine() {
        // A bare CR inside a line is stripped in place; only \n separates lines.
        assertEquals("a\nbc\nd", WaypointDescription.normalize("a\r\nb\rc  \nd"));
        assertEquals("a\nb", WaypointDescription.normalize("a  \nb"));
    }

    @Test
    void runsOfBlankLinesCollapseToOneBlankLine() {
        assertEquals("a\n\nb", WaypointDescription.normalize("a\n\n\n\n\nb"));
    }

    @Test
    void interiorNewlinesAndLeadingSpacesArePreserved() {
        assertEquals("产物:\n  钻石 ×3\n获取方式:挖矿", WaypointDescription.normalize(
            "产物:\n  钻石 ×3\n获取方式:挖矿"));
    }

    @Test
    void outerWhitespaceIsTrimmedAfterPerLineProcessing() {
        assertEquals("a\nb", WaypointDescription.normalize("  a\nb\n\n "));
    }

    @Test
    void controlCharactersOtherThanNewlineAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> WaypointDescription.normalize("a\tb"));
        assertThrows(IllegalArgumentException.class, () -> WaypointDescription.normalize("a\u0000b"));
        assertThrows(IllegalArgumentException.class, () -> WaypointDescription.normalize("a\u001Bb"));
    }

    @Test
    void sectionSignsFormatAndSurrogateCodePointsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> WaypointDescription.normalize("a§kb"));
        assertThrows(IllegalArgumentException.class, () -> WaypointDescription.normalize("a­b"));
        assertThrows(IllegalArgumentException.class, () -> WaypointDescription.normalize("a\uD83Db"));
    }

    @Test
    void descriptionsAreCappedAt512CodePoints() {
        final String boundary = "a".repeat(512);
        assertEquals(boundary, WaypointDescription.normalize(boundary));
        assertThrows(IllegalArgumentException.class, () -> WaypointDescription.normalize("a".repeat(513)));
        assertThrows(
            IllegalArgumentException.class,
            () -> WaypointDescription.normalize("中".repeat(400) + "a".repeat(113))
        );
    }

    @Test
    void descriptionsAreCappedAt2048Utf8BytesWithFourByteCodePointsAtTheBoundary() {
        final String boundary = "😀".repeat(512);
        assertEquals(boundary, WaypointDescription.normalize(boundary));
    }

    @Test
    void descriptionsAreCappedAt16Lines() {
        final StringBuilder sixteen = new StringBuilder("l1");
        for (int line = 2; line <= 16; line++) {
            sixteen.append('\n').append("l").append(line);
        }
        assertEquals(sixteen.toString(), WaypointDescription.normalize(sixteen.toString()));

        final StringBuilder seventeen = new StringBuilder(sixteen).append("\nl17");
        assertThrows(IllegalArgumentException.class, () -> WaypointDescription.normalize(seventeen.toString()));
    }

    @Test
    void blankLineRunsCountTowardTheLineCapAfterCollapsing() {
        assertEquals("a\n\nb\nc\nd\ne\nf\ng\nh\ni\nj\nk\nl\nm\nn\no", WaypointDescription.normalize(
            "a\n\nb\nc\nd\ne\nf\ng\nh\ni\nj\nk\nl\nm\nn\no"));
        assertThrows(IllegalArgumentException.class, () -> WaypointDescription.normalize(
            "a\n\nb\nc\nd\ne\nf\ng\nh\ni\nj\nk\nl\nm\nn\no\np"));
    }

    @Test
    void imageIdsNormalizeToCanonicalLowercaseUuidsOrEmpty() {
        assertEquals("", WaypointDescription.normalizeImageId(null));
        assertEquals("", WaypointDescription.normalizeImageId(""));
        assertEquals("", WaypointDescription.normalizeImageId("   "));
        assertEquals(
            "0123abcd-4567-789a-bcde-f0123456789a",
            WaypointDescription.normalizeImageId("0123ABCD-4567-789A-BCDE-F0123456789A")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> WaypointDescription.normalizeImageId("not-a-uuid")
        );
    }
}
