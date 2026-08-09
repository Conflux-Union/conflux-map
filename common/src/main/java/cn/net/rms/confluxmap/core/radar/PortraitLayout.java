package cn.net.rms.confluxmap.core.radar;

import java.util.Locale;
import java.util.Set;

/** Minecraft-free projection and sizing policy for model-derived radar portraits. */
public final class PortraitLayout {
    private static final Set<String> SIDE_VIEW_TYPES = Set.of(
        "minecraft:cod", "minecraft:salmon", "minecraft:pufferfish", "minecraft:tropical_fish"
    );
    private static final Set<String> THREE_QUARTER_VIEW_TYPES = Set.of(
        "minecraft:horse",
        "minecraft:donkey",
        "minecraft:mule",
        "minecraft:skeleton_horse",
        "minecraft:zombie_horse",
        "minecraft:llama",
        "minecraft:trader_llama",
        "minecraft:camel"
    );

    public record Fit(float scale, float width, float height, float left, float top) {
    }

    private PortraitLayout() {
    }

    public static float viewYawDegrees(final String entityType) {
        final String type = entityType == null ? "" : entityType.toLowerCase(Locale.ROOT);
        if (SIDE_VIEW_TYPES.contains(type)) {
            return 90f;
        }
        return THREE_QUARTER_VIEW_TYPES.contains(type) ? 35f : 0f;
    }

    /**
     * Scales a portrait so its longest axis always spans the same number of pixels, whatever the
     * model's aspect ratio is. Normalizing visual area instead would make an elongated muzzle
     * overflow the span a cube-shaped head gets, which is exactly how portraits end up looking
     * inconsistently sized on the radar.
     */
    public static Fit fit(
        final float rawWidth,
        final float rawHeight,
        final float cellSize,
        final float padding
    ) {
        if (!(rawWidth > 0f) || !(rawHeight > 0f) || !(cellSize > 0f) || padding < 0f) {
            throw new IllegalArgumentException("portrait dimensions must be positive");
        }
        final float content = cellSize - 2f * padding;
        if (!(content > 0f)) {
            throw new IllegalArgumentException("portrait padding leaves no content area");
        }
        final float scale = content / Math.max(rawWidth, rawHeight);
        final float width = rawWidth * scale;
        final float height = rawHeight * scale;
        return new Fit(scale, width, height, (cellSize - width) / 2f, (cellSize - height) / 2f);
    }
}
