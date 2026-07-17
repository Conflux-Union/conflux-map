package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.core.config.ConfigIo;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;

/**
 * Settings screen exposing every {@link ConfluxConfig} field, grouped into category
 * tabs (Minimap/Layers/Radar/Waypoints/Performance). Built entirely from vanilla
 * widgets, no external config-lib dependency, matching {@link WaypointListScreen}/
 * {@link WaypointEditScreen}'s style: plain {@link ButtonWidget}s that cycle through
 * boolean/enum values on click, and a {@link SliderWidget} subclass for int ranges.
 *
 * <p>Every change mutates the shared {@link ConfluxConfig} instance immediately, so
 * every other system (they all hold the same reference) observes it on its very next
 * read - there is no separate "apply" step. Disk persistence is batched instead: this
 * screen only calls {@link ConfigIo#save} once, in {@link #onClose()}, so dragging a
 * slider doesn't hammer disk I/O once per pixel of movement.
 *
 * <p>Opened only via the {@code key.confluxmap.open_config} keybind (comma by
 * default, see {@code mc.input.Keybinds}) - there is no in-screen entry point, and no
 * ModMenu integration in M1.
 */
public final class ConfigScreen extends Screen {
    private enum Category {
        MINIMAP("confluxmap.screen.config.category.minimap"),
        LAYERS("confluxmap.screen.config.category.layers"),
        RADAR("confluxmap.screen.config.category.radar"),
        WAYPOINTS("confluxmap.screen.config.category.waypoints"),
        PERFORMANCE("confluxmap.screen.config.category.performance");

        private final String labelKey;

        Category(final String labelKey) {
            this.labelKey = labelKey;
        }
    }

    private static final int MARGIN = 8;
    private static final int TAB_Y = 24;
    private static final int TAB_HEIGHT = 20;
    private static final int TAB_GAP = 4;
    private static final int ROW_TOP = 52;
    private static final int ROW_HEIGHT = 22;
    private static final int MAX_ROW_WIDTH = 280;
    private static final int BOTTOM_MARGIN = 30;

    private static final String[] ZOOM_VALUE_KEYS = {
        "confluxmap.value.zoom_0_5",
        "confluxmap.value.zoom_1",
        "confluxmap.value.zoom_2",
        "confluxmap.value.zoom_4"
    };

    private final ConfluxConfig config;
    private final ConfigIo configIo;

    private Category category = Category.MINIMAP;
    private int rowWidth = MAX_ROW_WIDTH;
    /** Rows scroll in ROW_HEIGHT steps; widgets outside the viewport are simply not built. */
    private int scrollOffset;
    private int contentHeight;

    public ConfigScreen() {
        super(new TranslatableText("confluxmap.screen.config.title"));
        final ConfluxMapClient app = ConfluxMapClient.get();
        this.config = app.config();
        this.configIo = app.configIo();
    }

    /** Keep the world (and this session's capture pipeline) running while the screen is open. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        rowWidth = Math.min(MAX_ROW_WIDTH, width - MARGIN * 2);
        scrollOffset = 0;
        rebuild();
    }

    private int viewportHeight() {
        return Math.max(ROW_HEIGHT, height - BOTTOM_MARGIN - ROW_TOP);
    }

    private boolean rowVisible(final int y) {
        return y >= ROW_TOP && y + ROW_HEIGHT - 2 <= height - BOTTOM_MARGIN;
    }

    @Override
    public boolean mouseScrolled(final double mouseX, final double mouseY, final double amount) {
        final int maxScroll = Math.max(0, contentHeight - viewportHeight());
        final int next = Math.max(0, Math.min(maxScroll, scrollOffset - (int) Math.signum(amount) * ROW_HEIGHT));
        if (next != scrollOffset) {
            scrollOffset = next;
            rebuild();
        }
        return true;
    }

    /** Funnel point for every close path (ESC via the default {@code keyPressed}, or the Done button below). */
    @Override
    public void onClose() {
        configIo.save(config);
        super.onClose();
    }

    private void rebuild() {
        clearChildren();
        addTabs();
        addRows();
        addDrawableChild(new ButtonWidget(
            width / 2 - 50, height - BOTTOM_MARGIN + 4, 100, 20,
            new TranslatableText("confluxmap.screen.waypoint.done"), b -> onClose()
        ));
    }

    private void addTabs() {
        final Category[] categories = Category.values();
        final int tabWidth = Math.min(110, (width - MARGIN * 2 - TAB_GAP * (categories.length - 1)) / categories.length);
        final int totalWidth = tabWidth * categories.length + TAB_GAP * (categories.length - 1);
        int x = width / 2 - totalWidth / 2;
        for (final Category c : categories) {
            final ButtonWidget tab = new ButtonWidget(
                x, TAB_Y, tabWidth, TAB_HEIGHT, new TranslatableText(c.labelKey), b -> selectCategory(c)
            );
            // Disabling the current tab both marks it visually (dimmed, per vanilla button
            // convention) and makes re-clicking it a harmless no-op.
            tab.active = c != category;
            addDrawableChild(tab);
            x += tabWidth + TAB_GAP;
        }
    }

    private void selectCategory(final Category c) {
        category = c;
        scrollOffset = 0;
        rebuild();
    }

    private void addRows() {
        int y = ROW_TOP - scrollOffset;
        switch (category) {
            case MINIMAP:
                y = addToggleRow(y, "confluxmap.config.minimap.enabled", () -> config.minimapEnabled, v -> config.minimapEnabled = v);
                y = addEnumRow(
                    y, "confluxmap.config.minimap.corner", ConfluxConfig.Corner.values(),
                    () -> config.minimapCorner, v -> config.minimapCorner = v, ConfigScreen::cornerKey
                );
                y = addEnumRow(
                    y, "confluxmap.config.minimap.shape", ConfluxConfig.Shape.values(),
                    () -> config.minimapShape, v -> config.minimapShape = v, ConfigScreen::shapeKey
                );
                y = addIntSliderRow(
                    y, "confluxmap.config.minimap.size", 64, 256,
                    () -> config.minimapSize, v -> config.minimapSize = v, ConfigScreen::pxText
                );
                y = addToggleRow(y, "confluxmap.config.minimap.rotate", () -> config.minimapRotate, v -> config.minimapRotate = v);
                y = addZoomRow(y);
                y = addToggleRow(y, "confluxmap.config.minimap.show_coordinates", () -> config.showCoordinates, v -> config.showCoordinates = v);
                y = addToggleRow(y, "confluxmap.config.minimap.show_biome", () -> config.showBiome, v -> config.showBiome = v);
                y = addToggleRow(y, "confluxmap.config.fullmap.chunk_grid", () -> config.fullmapChunkGrid, v -> config.fullmapChunkGrid = v);
                break;
            case LAYERS:
                y = addEnumRow(
                    y, "confluxmap.config.layers.override", ConfluxConfig.LayerOverride.values(),
                    () -> config.layerOverride, v -> config.layerOverride = v, ConfigScreen::layerOverrideKey
                );
                y = addToggleRow(y, "confluxmap.config.layers.show_indicator", () -> config.showLayerIndicator, v -> config.showLayerIndicator = v);
                y = addIntSliderRow(
                    y, "confluxmap.config.layers.cave_slice_y", 0, 255,
                    () -> config.caveSliceY, v -> config.caveSliceY = v, ConfigScreen::plainText
                );
                y = addIntSliderRow(
                    y, "confluxmap.config.layers.nether_slice_y", 0, 127,
                    () -> config.netherSliceY, v -> config.netherSliceY = v, ConfigScreen::plainText
                );
                y = addToggleRow(
                    y, "confluxmap.config.map.dynamic_lighting", () -> config.dynamicLighting, v -> config.dynamicLighting = v
                );
                break;
            case RADAR:
                y = addToggleRow(y, "confluxmap.config.radar.enabled", () -> config.radarEnabled, v -> config.radarEnabled = v);
                y = addToggleRow(y, "confluxmap.config.radar.show_players", () -> config.radarShowPlayers, v -> config.radarShowPlayers = v);
                y = addToggleRow(y, "confluxmap.config.radar.show_hostile", () -> config.radarShowHostile, v -> config.radarShowHostile = v);
                y = addToggleRow(y, "confluxmap.config.radar.show_passive", () -> config.radarShowPassive, v -> config.radarShowPassive = v);
                y = addToggleRow(y, "confluxmap.config.radar.show_other", () -> config.radarShowOther, v -> config.radarShowOther = v);
                y = addToggleRow(y, "confluxmap.config.radar.show_player_names", () -> config.radarShowPlayerNames, v -> config.radarShowPlayerNames = v);
                y = addIntSliderRow(
                    y, "confluxmap.config.radar.max_entities", 1, 500,
                    () -> config.radarMaxEntities, v -> config.radarMaxEntities = v, ConfigScreen::plainText
                );
                y = addToggleRow(y, "confluxmap.config.radar.icons_enabled", () -> config.radarIconsEnabled, v -> config.radarIconsEnabled = v);
                break;
            case WAYPOINTS:
                y = addIntSliderRow(
                    y, "confluxmap.config.waypoints.render_distance", 0, 100_000,
                    () -> config.waypointRenderDistance, v -> config.waypointRenderDistance = v, ConfigScreen::renderDistanceText
                );
                y = addToggleRow(
                    y, "confluxmap.config.waypoints.edge_indicators",
                    () -> config.waypointEdgeIndicatorsEnabled, v -> config.waypointEdgeIndicatorsEnabled = v
                );
                y = addIntSliderRow(
                    y, "confluxmap.config.waypoints.death_points_kept", 0, 50,
                    () -> config.deathPointsKept, v -> config.deathPointsKept = v, ConfigScreen::plainText
                );
                y = addToggleRow(
                    y, "confluxmap.config.waypoints.beams_enabled",
                    () -> config.waypointBeamsEnabled, v -> config.waypointBeamsEnabled = v
                );
                y = addToggleRow(
                    y, "confluxmap.config.waypoints.labels_enabled",
                    () -> config.waypointLabelsEnabled, v -> config.waypointLabelsEnabled = v
                );
                break;
            case PERFORMANCE:
                y = addIntSliderRow(
                    y, "confluxmap.config.performance.snapshot_budget", 1, 64,
                    () -> config.snapshotBudgetPerTick, v -> config.snapshotBudgetPerTick = v, ConfigScreen::plainText
                );
                y = addIntSliderRow(
                    y, "confluxmap.config.performance.gpu_tile_cache_limit", 16, 2048,
                    () -> config.gpuTileCacheLimit, v -> config.gpuTileCacheLimit = v, ConfigScreen::plainText
                );
                break;
            default:
                break;
        }
        contentHeight = y + scrollOffset - ROW_TOP;
    }

    private int rowX() {
        return width / 2 - rowWidth / 2;
    }

    private int addToggleRow(final int y, final String labelKey, final BooleanSupplier getter, final Consumer<Boolean> setter) {
        if (rowVisible(y)) {
            addDrawableChild(new ButtonWidget(
                rowX(), y, rowWidth, ROW_HEIGHT - 2, boolLabel(labelKey, getter.getAsBoolean()),
                b -> {
                    final boolean next = !getter.getAsBoolean();
                    setter.accept(next);
                    b.setMessage(boolLabel(labelKey, next));
                }
            ));
        }
        return y + ROW_HEIGHT;
    }

    private <T> int addEnumRow(
        final int y,
        final String labelKey,
        final T[] values,
        final Supplier<T> getter,
        final Consumer<T> setter,
        final Function<T, String> valueKeyFn
    ) {
        if (rowVisible(y)) {
            addDrawableChild(new ButtonWidget(
                rowX(), y, rowWidth, ROW_HEIGHT - 2, enumLabel(labelKey, getter.get(), valueKeyFn),
                b -> {
                    final T next = nextValue(values, getter.get());
                    setter.accept(next);
                    b.setMessage(enumLabel(labelKey, next, valueKeyFn));
                }
            ));
        }
        return y + ROW_HEIGHT;
    }

    private int addZoomRow(final int y) {
        if (rowVisible(y)) {
            addDrawableChild(new ButtonWidget(
                rowX(), y, rowWidth, ROW_HEIGHT - 2, zoomLabel(config.minimapZoomIndex),
                b -> {
                    final int next = (config.minimapZoomIndex + 1) % ZOOM_VALUE_KEYS.length;
                    config.minimapZoomIndex = next;
                    b.setMessage(zoomLabel(next));
                }
            ));
        }
        return y + ROW_HEIGHT;
    }

    private int addIntSliderRow(
        final int y,
        final String labelKey,
        final int min,
        final int max,
        final IntSupplier getter,
        final IntConsumer setter,
        final IntFunction<String> valueText
    ) {
        if (rowVisible(y)) {
            addDrawableChild(new IntSliderWidget(rowX(), y, rowWidth, ROW_HEIGHT - 2, min, max, labelKey, getter, setter, valueText));
        }
        return y + ROW_HEIGHT;
    }

    private static <T> T nextValue(final T[] values, final T current) {
        final int index = Arrays.asList(values).indexOf(current);
        return values[(index + 1) % values.length];
    }

    private static Text boolLabel(final String labelKey, final boolean value) {
        return new TranslatableText(labelKey, resolvedText(value ? "confluxmap.value.on" : "confluxmap.value.off"));
    }

    private static <T> Text enumLabel(final String labelKey, final T value, final Function<T, String> valueKeyFn) {
        return new TranslatableText(labelKey, resolvedText(valueKeyFn.apply(value)));
    }

    private static Text zoomLabel(final int zoomIndex) {
        return new TranslatableText("confluxmap.config.minimap.zoom", resolvedText(ZOOM_VALUE_KEYS[zoomIndex]));
    }

    private static String resolvedText(final String key) {
        return new TranslatableText(key).getString();
    }

    private static String plainText(final int value) {
        return String.valueOf(value);
    }

    private static String pxText(final int value) {
        return new TranslatableText("confluxmap.value.px", value).getString();
    }

    private static String blocksText(final int value) {
        return new TranslatableText("confluxmap.value.blocks", value).getString();
    }

    private static String renderDistanceText(final int value) {
        return value == 0 ? resolvedText("confluxmap.value.unlimited") : blocksText(value);
    }

    private static String cornerKey(final ConfluxConfig.Corner corner) {
        switch (corner) {
            case TOP_LEFT:
                return "confluxmap.config.corner.top_left";
            case BOTTOM_LEFT:
                return "confluxmap.config.corner.bottom_left";
            case BOTTOM_RIGHT:
                return "confluxmap.config.corner.bottom_right";
            default:
                return "confluxmap.config.corner.top_right";
        }
    }

    private static String shapeKey(final ConfluxConfig.Shape shape) {
        return shape == ConfluxConfig.Shape.CIRCLE ? "confluxmap.config.shape.circle" : "confluxmap.config.shape.square";
    }

    private static String layerOverrideKey(final ConfluxConfig.LayerOverride override) {
        switch (override) {
            case FORCE_SURFACE:
                return "confluxmap.config.layer_override.force_surface";
            case FORCE_UNDERGROUND:
                return "confluxmap.config.layer_override.force_underground";
            default:
                return "confluxmap.config.layer_override.auto";
        }
    }

    @Override
    public void render(final MatrixStack matrices, final int mouseX, final int mouseY, final float tickDelta) {
        renderBackground(matrices);
        final String title = getTitle().getString();
        textRenderer.drawWithShadow(matrices, title, width / 2f - textRenderer.getWidth(title) / 2f, 8, 0xFFFFFFFF);
        super.render(matrices, mouseX, mouseY, tickDelta);
    }

    /**
     * Int-ranged setting rendered as a slider whose label is "{@code Label: <value>}",
     * value formatted by the caller-supplied {@code valueText} (plain number, "Nxpx",
     * "N blocks", "Unlimited", etc - see the {@code *Text} helpers above). Applies to
     * the shared config on every drag step, matching a vanilla options slider's feel;
     * only {@link #onClose()} persists to disk.
     */
    private static final class IntSliderWidget extends SliderWidget {
        private final int min;
        private final int max;
        private final String labelKey;
        private final IntConsumer setter;
        private final IntFunction<String> valueText;

        IntSliderWidget(
            final int x,
            final int y,
            final int width,
            final int height,
            final int min,
            final int max,
            final String labelKey,
            final IntSupplier getter,
            final IntConsumer setter,
            final IntFunction<String> valueText
        ) {
            super(x, y, width, height, Text.of(""), normalize(getter.getAsInt(), min, max));
            this.min = min;
            this.max = max;
            this.labelKey = labelKey;
            this.setter = setter;
            this.valueText = valueText;
            updateMessage();
        }

        private static double normalize(final int v, final int min, final int max) {
            return max == min ? 0.0 : (v - min) / (double) (max - min);
        }

        private int currentValue() {
            return min + (int) Math.round(value * (max - min));
        }

        @Override
        protected void updateMessage() {
            setMessage(new TranslatableText(labelKey, valueText.apply(currentValue())));
        }

        @Override
        protected void applyValue() {
            setter.accept(currentValue());
        }
    }
}
