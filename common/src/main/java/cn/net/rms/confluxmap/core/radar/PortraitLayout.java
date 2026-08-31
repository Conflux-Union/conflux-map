package cn.net.rms.confluxmap.core.radar;

import java.util.Locale;
import java.util.Map;

/** Minecraft-free projection and sizing policy for model-derived radar portraits. */
public final class PortraitLayout {
    public enum Framing {
        DOMINANT,
        COMPLETE,
        UPPER_SILHOUETTE
    }

    private static final Profile DEFAULT_PROFILE = new Profile(
        0f, 0f, false, Framing.DOMINANT
    );
    private static final Profile RIGHT_SIDE_PROFILE = new Profile(
        90f, 0f, true, Framing.DOMINANT
    );
    private static final Profile LEFT_SIDE_PROFILE = new Profile(
        -90f, 0f, true, Framing.DOMINANT
    );
    private static final Profile RIGHT_SIDE_COMPLETE_PROFILE = new Profile(
        90f, 0f, true, Framing.COMPLETE
    );
    private static final Profile LEFT_SIDE_COMPLETE_PROFILE = new Profile(
        -90f, 0f, true, Framing.COMPLETE
    );
    private static final Profile LONG_HEAD_PROFILE = new Profile(
        -90f, 35f, true, Framing.COMPLETE
    );
    private static final Profile LLAMA_PROFILE = new Profile(
        -90f, 35f, true, Framing.UPPER_SILHOUETTE
    );
    private static final Profile CAMEL_PROFILE = new Profile(
        -90f, 0f, true, Framing.COMPLETE
    );
    private static final Profile GOAT_PROFILE = new Profile(
        0f, 30f, true, Framing.DOMINANT
    );
    private static final Profile HOGLIN_PROFILE = new Profile(
        0f, 60f, true, Framing.DOMINANT
    );
    private static final Profile COMPLETE_PROFILE = new Profile(
        0f, 0f, false, Framing.COMPLETE
    );
    private static final Profile UPPER_PROFILE = new Profile(
        0f, 0f, true, Framing.UPPER_SILHOUETTE
    );
    private static final Map<String, Profile> PORTRAIT_PROFILES = Map.ofEntries(
        Map.entry("minecraft:cod", RIGHT_SIDE_COMPLETE_PROFILE),
        Map.entry("minecraft:salmon", RIGHT_SIDE_COMPLETE_PROFILE),
        Map.entry("minecraft:pufferfish", RIGHT_SIDE_COMPLETE_PROFILE),
        Map.entry("minecraft:tropical_fish", RIGHT_SIDE_COMPLETE_PROFILE),
        Map.entry("minecraft:turtle", RIGHT_SIDE_COMPLETE_PROFILE),
        Map.entry("minecraft:parrot", LEFT_SIDE_COMPLETE_PROFILE),
        Map.entry("minecraft:horse", LONG_HEAD_PROFILE),
        Map.entry("minecraft:donkey", LONG_HEAD_PROFILE),
        Map.entry("minecraft:mule", LONG_HEAD_PROFILE),
        Map.entry("minecraft:skeleton_horse", LONG_HEAD_PROFILE),
        Map.entry("minecraft:zombie_horse", LONG_HEAD_PROFILE),
        Map.entry("minecraft:llama", LLAMA_PROFILE),
        Map.entry("minecraft:trader_llama", LLAMA_PROFILE),
        Map.entry("minecraft:camel", CAMEL_PROFILE),
        Map.entry("minecraft:camel_husk", CAMEL_PROFILE),
        Map.entry("minecraft:goat", GOAT_PROFILE),
        Map.entry("minecraft:hoglin", HOGLIN_PROFILE),
        Map.entry("minecraft:zoglin", HOGLIN_PROFILE),
        Map.entry("minecraft:nautilus", LEFT_SIDE_PROFILE),
        Map.entry("minecraft:zombie_nautilus", LEFT_SIDE_PROFILE),
        Map.entry("minecraft:rabbit", COMPLETE_PROFILE),
        Map.entry("minecraft:happy_ghast", UPPER_PROFILE)
    );

    public record Fit(float scale, float width, float height, float left, float top) {
    }

    public record Profile(
        float yawDegrees,
        float pitchDegrees,
        boolean resetPartRotation,
        Framing framing
    ) {
    }

    private PortraitLayout() {
    }

    public static float viewYawDegrees(final String entityType) {
        return profile(entityType).yawDegrees();
    }

    public static Profile profile(final String entityType) {
        final String type = normalizedType(entityType);
        return PORTRAIT_PROFILES.getOrDefault(type, DEFAULT_PROFILE);
    }

    /**
     * Fits the already-selected subject into the portrait while preserving its aspect ratio.
     * Callers normally pass the dominant subject dimensions; species whose smaller parts define
     * their identity may instead pass the complete selected-head silhouette.
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
        final float scale = Math.min(content / rawWidth, content / rawHeight);
        final float width = rawWidth * scale;
        final float height = rawHeight * scale;
        return new Fit(scale, width, height, (cellSize - width) / 2f, (cellSize - height) / 2f);
    }

    private static String normalizedType(final String entityType) {
        return entityType == null ? "" : entityType.toLowerCase(Locale.ROOT);
    }
}
