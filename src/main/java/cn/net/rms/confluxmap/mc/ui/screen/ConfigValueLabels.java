package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.core.color.MapColorStyle;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.predict.PredictionViewMode;
import java.util.function.Function;
import net.minecraft.text.Text;

/**
 * Label and value-text formatting for the config rows: every enum-to-translation-key
 * mapping and every slider value formatter the settings screen needs, in one place.
 */
final class ConfigValueLabels {
    private static final String[] ZOOM_VALUE_KEYS = {
        "confluxmap.value.zoom_0_5",
        "confluxmap.value.zoom_1",
        "confluxmap.value.zoom_2",
        "confluxmap.value.zoom_4"
    };

    private ConfigValueLabels() {
    }

    static Text boolLabel(final String labelKey, final boolean value) {
        return Texts.translatable(labelKey, resolvedText(value ? "confluxmap.value.on" : "confluxmap.value.off"));
    }

    static <T> Text enumLabel(final String labelKey, final T value, final Function<T, String> valueKeyFn) {
        return Texts.translatable(labelKey, resolvedText(valueKeyFn.apply(value)));
    }

    static Text zoomLabel(final int zoomIndex) {
        return Texts.translatable("confluxmap.config.minimap.zoom", resolvedText(ZOOM_VALUE_KEYS[zoomIndex]));
    }

    static String resolvedText(final String key) {
        return Texts.translatable(key).getString();
    }

    static String plainText(final int value) {
        return String.valueOf(value);
    }

    static String pxText(final int value) {
        return Texts.translatable("confluxmap.value.px", value).getString();
    }

    static String percentText(final int value) {
        return Texts.translatable("confluxmap.value.percent", value).getString();
    }

    static String blocksText(final int value) {
        return Texts.translatable("confluxmap.value.blocks", value).getString();
    }

    static String secondsText(final int value) {
        return Texts.translatable("confluxmap.value.seconds", value).getString();
    }

    static String renderDistanceText(final int value) {
        return value == 0 ? resolvedText("confluxmap.value.unlimited") : blocksText(value);
    }

    static String structureIconDetailLimitText(final double scale) {
        return Texts.translatable(
            "confluxmap.value.zoom_multiplier", DecimalSliderInput.format(scale)
        ).getString();
    }

    static String portalHighlightColorKey(final ConfluxConfig.PortalHighlightColor color) {
        return "confluxmap.value.portal.highlight_color." + color.name().toLowerCase(java.util.Locale.ROOT);
    }

    static String uiTransitionSpeedKey(final ConfluxConfig.UiTransitionSpeed speed) {
        return "confluxmap.value.ui_transition_speed." + speed.name().toLowerCase(java.util.Locale.ROOT);
    }

    static String voidColumnColorKey(final ConfluxConfig.VoidColumnColor color) {
        return "confluxmap.value.map.void_color." + color.name().toLowerCase(java.util.Locale.ROOT);
    }

    static String shapeKey(final ConfluxConfig.Shape shape) {
        return shape == ConfluxConfig.Shape.CIRCLE ? "confluxmap.config.shape.circle" : "confluxmap.config.shape.square";
    }

    static String playerMarkerStyleKey(final ConfluxConfig.PlayerMarkerStyle style) {
        return style == ConfluxConfig.PlayerMarkerStyle.TRADITIONAL
            ? "confluxmap.value.player_marker.traditional"
            : "confluxmap.value.player_marker.modern";
    }

    static String radarDisplayModeKey(final ConfluxConfig.RadarDisplayMode mode) {
        return mode == ConfluxConfig.RadarDisplayMode.DOTS
            ? "confluxmap.value.radar_display_mode.dots"
            : "confluxmap.value.radar_display_mode.portraits";
    }

    static String workerThreadModeKey(final ConfluxConfig.WorkerThreadMode mode) {
        switch (mode) {
            case ALL_THREADS:
                return "confluxmap.value.worker_threads.all";
            case CUSTOM:
                return "confluxmap.value.worker_threads.custom";
            default:
                return "confluxmap.value.worker_threads.auto";
        }
    }

    static String waypointListTabKey(final ConfluxConfig.WaypointListTab tab) {
        return tab == ConfluxConfig.WaypointListTab.LOCAL
            ? "confluxmap.value.waypoint_list_tab.local"
            : "confluxmap.value.waypoint_list_tab.public";
    }

    static String waypointListDefaultDimensionKey(
        final ConfluxConfig.WaypointListDefaultDimension dimension
    ) {
        return dimension == ConfluxConfig.WaypointListDefaultDimension.CURRENT
            ? "confluxmap.value.waypoint_list_dimension.current"
            : "confluxmap.value.waypoint_list_dimension.all";
    }

    static String layerOverrideKey(final ConfluxConfig.LayerOverride override) {
        switch (override) {
            case FORCE_SURFACE:
                return "confluxmap.config.layer_override.force_surface";
            case FORCE_UNDERGROUND:
                return "confluxmap.config.layer_override.force_underground";
            case FORCE_SLICE:
                return "confluxmap.config.layer_override.force_slice";
            default:
                return "confluxmap.config.layer_override.auto";
        }
    }

    static String mapColorStyleKey(final MapColorStyle style) {
        return style == MapColorStyle.XAERO
            ? "confluxmap.value.map_color_style.xaero"
            : "confluxmap.value.map_color_style.conflux";
    }

    static String mapQualityKey(final ConfluxConfig.MapQuality quality) {
        switch (quality) {
            case ULTRA_PERFORMANCE:
                return "confluxmap.value.map_quality.ultra_performance";
            case PERFORMANCE:
                return "confluxmap.value.map_quality.performance";
            case QUALITY:
                return "confluxmap.value.map_quality.quality";
            case ULTRA_QUALITY:
                return "confluxmap.value.map_quality.ultra_quality";
            case BALANCED:
            default:
                return "confluxmap.value.map_quality.balanced";
        }
    }

    static String mapQualitySliderText(final int index) {
        final ConfluxConfig.MapQuality[] qualities = ConfluxConfig.MapQuality.values();
        final int clamped = Math.max(0, Math.min(qualities.length - 1, index));
        return Texts.translatable(mapQualityKey(qualities[clamped])).getString();
    }

    static String predictionViewModeKey(final PredictionViewMode mode) {
        switch (mode) {
            case GENERATED_ONLY:
                return "confluxmap.config.prediction.mode.generated_only";
            case VISITED_ONLY:
                return "confluxmap.config.prediction.mode.visited_only";
            default:
                return "confluxmap.config.prediction.mode.everywhere";
        }
    }
}
