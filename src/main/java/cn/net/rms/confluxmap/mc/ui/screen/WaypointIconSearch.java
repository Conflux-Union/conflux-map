package cn.net.rms.confluxmap.mc.ui.screen;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Pure filtering for the localized vanilla-item icon picker. */
final class WaypointIconSearch {
    record Entry(String itemId, String displayName) {
        Entry {
            Objects.requireNonNull(itemId, "itemId");
            Objects.requireNonNull(displayName, "displayName");
        }
    }

    private WaypointIconSearch() {
    }

    static List<Entry> filter(final List<Entry> entries, final String rawQuery) {
        final String query = rawQuery == null ? "" : rawQuery.trim().toLowerCase(Locale.ROOT);
        return entries.stream()
            .filter(entry -> entry.itemId().startsWith("minecraft:"))
            .filter(entry -> entry.displayName().toLowerCase(Locale.ROOT).contains(query))
            .toList();
    }
}
