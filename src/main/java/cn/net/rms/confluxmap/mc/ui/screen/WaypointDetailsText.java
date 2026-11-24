package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.core.waypoint.library.WaypointMetadata;
import cn.net.rms.confluxmap.core.waypoint.library.WaypointTag;
import cn.net.rms.confluxmap.mc.ui.WaypointMarkerRenderer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.item.ItemStack;

/** Localized labels are presentation/search only; the store keeps stable tag and item IDs. */
final class WaypointDetailsText {
    private WaypointDetailsText() {}

    static String itemName(final String id) {
        final ItemStack stack = WaypointMarkerRenderer.itemIcon(id);
        return stack.isEmpty() ? id : stack.getName().getString();
    }

    static String compactAmount(int amount) {
        if (amount < 1_000) return Integer.toString(amount);
        boolean millions = amount >= 1_000_000;
        int tenths = amount / (millions ? 100_000 : 100);
        String fraction = tenths % 10 == 0 ? "" : "." + tenths % 10;
        return (tenths / 10) + fraction + (millions ? "m" : "k");
    }

    static String summary(final WaypointMetadata extra) {
        if (!enabled()) return "";
        final List<String> labels = new ArrayList<>();
        for (final String tag : extra.tags()) {
            labels.add(WaypointTag.fromStored(tag).map(preset -> Texts.translatable(preset.translationKey()).getString())
                .orElse(tag));
        }
        for (final String id : extra.resourceItems()) {
            labels.add(itemName(id));
        }
        return String.join(" / ", labels);
    }

    static boolean matches(final WaypointMetadata extra, final String query) {
        if (!enabled()) return false;
        return extra.matches(query) || summary(extra).toLowerCase(Locale.ROOT)
            .contains(query.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean enabled() {
        final var app = cn.net.rms.confluxmap.ConfluxMapClient.get();
        return app == null || app.waypointListMemory() == null || app.waypointListMemory().options().details;
    }
}
