package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

final class WaypointIconSearchTest {
    private static final List<WaypointIconSearch.Entry> ITEMS = List.of(
        new WaypointIconSearch.Entry("minecraft:diamond", "Diamond"),
        new WaypointIconSearch.Entry("minecraft:oak_boat", "Oak Boat"),
        new WaypointIconSearch.Entry("minecraft:compass", "\u6307\u5357\u9488"),
        new WaypointIconSearch.Entry("example:wand", "Diamond Wand")
    );

    @Test
    void filtersVanillaItemsByLocalizedNameCaseInsensitively() {
        assertEquals(
            List.of(new WaypointIconSearch.Entry("minecraft:diamond", "Diamond")),
            WaypointIconSearch.filter(ITEMS, "  DIAm  ")
        );
        assertEquals(
            List.of(new WaypointIconSearch.Entry("minecraft:compass", "\u6307\u5357\u9488")),
            WaypointIconSearch.filter(ITEMS, "\u5357\u9488")
        );
    }

    @Test
    void emptyQueryKeepsOnlyVanillaItems() {
        assertEquals(3, WaypointIconSearch.filter(ITEMS, "").size());
    }
}
