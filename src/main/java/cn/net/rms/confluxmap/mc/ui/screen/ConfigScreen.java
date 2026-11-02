package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.bridge.GameBridge;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.core.config.ConfigIo;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointAvailability;
import cn.net.rms.confluxmap.core.predict.PredictionState;
import cn.net.rms.confluxmap.mc.net.CompanionSession;
import cn.net.rms.confluxmap.mc.net.shared.SharedWaypointClient;
import cn.net.rms.confluxmap.mc.predict.ManualSeedService;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import cn.net.rms.confluxmap.mc.ui.UiResourceTheme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.StringVisitable;
import net.minecraft.text.Text;

/**
 * Settings screen exposing every {@link ConfluxConfig} field, grouped into category
 * tabs (Minimap/Map/Markers/Radar/Waypoints/Prediction/General). Built entirely from
 * vanilla widgets, no external config-lib dependency, matching {@link WaypointListScreen}/
 * {@link WaypointEditScreen}'s style: plain {@link ButtonWidget}s that cycle through
 * boolean/enum values on click, and paired sliders/numeric fields for int ranges.
 *
 * <p>This class owns the screen lifecycle: tabs, scrolling, policy polling in
 * {@code tick()}, and rendering. Row construction lives in {@link ConfigRows}, label
 * and value-text formatting in {@link ConfigValueLabels}, and the server-policy
 * snapshots in {@link RadarSettingsAccess}/{@link PredictionSettingsAccess}/
 * {@link PlayerMarkerSettingsAccess}.
 *
 * <p>Every change mutates and saves the shared {@link ConfluxConfig} immediately, so
 * every other system observes the same value without a separate apply step.
 *
 * <p>Opened via the {@code key.confluxmap.open_config} keybind (comma by default,
 * see {@code mc.input.Keybinds}) or the fullscreen map's settings button. The latter
 * supplies a parent screen so closing settings returns to the map.
 */
public final class ConfigScreen extends ConfluxScreen {
    enum Category {
        MINIMAP("confluxmap.screen.config.category.minimap"),
        MAP("confluxmap.screen.config.category.map"),
        MARKERS("confluxmap.screen.config.category.markers"),
        RADAR("confluxmap.screen.config.category.radar"),
        WAYPOINTS("confluxmap.screen.config.category.waypoints"),
        PREDICTION("confluxmap.screen.config.category.prediction"),
        GENERAL("confluxmap.screen.config.category.general");

        private final String labelKey;

        Category(final String labelKey) {
            this.labelKey = labelKey;
        }
    }

    private static final int MARGIN = 8;
    private static final int TAB_Y = 24;
    private static final int TAB_HEIGHT = 20;
    private static final int TAB_GAP = 4;
    static final int ROW_HEIGHT = 22;
    private static final int MAX_ROW_WIDTH = 280;
    private static final int BOTTOM_MARGIN = 30;
    private static final int RADAR_NOTICE_PADDING = 4;
    private static final int NOTICE_TEXT_COLOR = 0xFFFFAA00;

    private final Screen parent;
    private final ConfluxConfig config;
    private final ConfigIo configIo;
    private final CompanionSession companionSession;
    private final SharedWaypointClient sharedWaypoints;
    private final GameBridge gameBridge;
    private final PredictionState predictionState;
    private final ManualSeedService manualSeedService;
    private final UiResourceTheme uiTheme;
    private final ConfigRows rows;

    Category category = Category.MINIMAP;
    int rowWidth = MAX_ROW_WIDTH;
    /** Rows scroll in ROW_HEIGHT steps; widgets outside the viewport are simply not built. */
    int scrollOffset;
    private int contentHeight;
    SharedWaypointAvailability sharedAvailability;
    RadarSettingsAccess radarAccess = RadarSettingsAccess.ALLOWED;
    PredictionSettingsAccess predictionAccess;
    PlayerMarkerSettingsAccess playerMarkerAccess =
        PlayerMarkerSettingsAccess.from(false);
    boolean manualSeedAvailable;

    public ConfigScreen() {
        this(null);
    }

    public ConfigScreen(final Screen parent) {
        super(Texts.translatable("confluxmap.screen.config.title"));
        this.parent = parent;
        final ConfluxMapClient app = ConfluxMapClient.get();
        this.config = app.config();
        this.configIo = app.configIo();
        this.companionSession = app.companionSession();
        this.sharedWaypoints = app.sharedWaypoints();
        this.gameBridge = app.gameBridge();
        this.predictionState = app.predictionState();
        this.manualSeedService = app.manualSeedService();
        this.uiTheme = app.uiResourceTheme();
        this.rows = new ConfigRows(this, config, configIo);
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
        manualSeedAvailable = manualSeedService.available();
        rebuild();
    }

    @Override
    public void tick() {
        super.tick();
        rows.tickInputs();
        final SharedWaypointAvailability availability = sharedWaypoints.availability();
        if (!availability.equals(sharedAvailability)) {
            sharedAvailability = availability;
            if (category == Category.WAYPOINTS) {
                rebuild();
            }
        }
        final RadarSettingsAccess currentRadarAccess = RadarSettingsAccess.from(
            companionSession.entityRadarAllowed()
        );
        if (currentRadarAccess != radarAccess) {
            radarAccess = currentRadarAccess;
            if (category == Category.RADAR) {
                rebuild();
            }
        }
        final PredictionSettingsAccess currentPredictionAccess = predictionSettingsAccess();
        final boolean currentManualSeedAvailable = manualSeedService.available();
        if (!currentPredictionAccess.equals(predictionAccess)
            || currentManualSeedAvailable != manualSeedAvailable) {
            predictionAccess = currentPredictionAccess;
            manualSeedAvailable = currentManualSeedAvailable;
            if (category == Category.PREDICTION) {
                rebuild();
            }
        }
        final PlayerMarkerSettingsAccess currentPlayerMarkerAccess = playerMarkerSettingsAccess();
        if (!currentPlayerMarkerAccess.equals(playerMarkerAccess)) {
            playerMarkerAccess = currentPlayerMarkerAccess;
            if (category == Category.MINIMAP) {
                rebuild();
            }
        }
    }

    private int viewportHeight() {
        return Math.max(ROW_HEIGHT, height - BOTTOM_MARGIN - rowsTop());
    }

    boolean rowVisible(final int y) {
        return y >= rowsTop() && y + ROW_HEIGHT - 2 <= height - BOTTOM_MARGIN;
    }

    int rowsTop() {
        final int baseRowsTop = tabLayout().contentTop();
        return category == Category.RADAR && radarAccess.noticeKey() != null
            ? baseRowsTop + radarNoticeHeight()
            : baseRowsTop;
    }

    private int radarNoticeHeight() {
        final String notice = Texts.translatable(radarAccess.noticeKey()).getString();
        final int lines = this.textRenderer.wrapLines(
            StringVisitable.plain(notice), Math.max(40, rowWidth)
        ).size();
        return lines * (this.textRenderer.fontHeight + 1) + RADAR_NOTICE_PADDING;
    }

    @Override
        //#if MC>=12002
        //$$ public boolean mouseScrolled(
        //$$     final double mouseX,
        //$$     final double mouseY,
        //$$     final double horizontalAmount,
        //$$     final double amount
        //$$ ) {
        //#else
        public boolean mouseScrolled(final double mouseX, final double mouseY, final double amount) {
        //#endif
        if (isClosing()) {
            return true;
        }
        // Scrolling rebuilds the rows and would destroy a text field mid-edit.
        if (getFocused() instanceof TextFieldWidget) {
            return false;
        }
        final boolean overRows = mouseX >= rowX() && mouseX <= rowX() + rowWidth + 8
            && mouseY >= rowsTop() && mouseY <= height - BOTTOM_MARGIN;
        if (amount != 0 && overRows) {
            final int maxScroll = Math.max(0, contentHeight - viewportHeight());
            final int next = Math.max(
                0,
                Math.min(maxScroll, scrollOffset - (int) Math.signum(amount) * ROW_HEIGHT)
            );
            if (next != scrollOffset) {
                scrollOffset = next;
                rebuild();
            }
            return true;
        }
        //#if MC>=12002
        //$$ return super.mouseScrolled(mouseX, mouseY, horizontalAmount, amount);
        //#else
        return super.mouseScrolled(mouseX, mouseY, amount);
        //#endif
    }

    /** Funnel point for every close path (ESC via the default {@code keyPressed}, or the Done button below). */
    @Override
    public void onClose() {
        configIo.save(config);
        requestClose(parent);
    }

    /** Rebuilds every row; also callable from a row's own click handler (e.g. to reveal a follow-up row). */
    void rebuild() {
        sharedAvailability = sharedWaypoints.availability();
        radarAccess = RadarSettingsAccess.from(companionSession.entityRadarAllowed());
        predictionAccess = predictionSettingsAccess();
        playerMarkerAccess = playerMarkerSettingsAccess();
        manualSeedAvailable = manualSeedService.available();
        rows.clearInputs();
        clearChildren();
        addTabs();
        contentHeight = rows.addRows() + scrollOffset - rowsTop();
        final int bottomButtonWidth = Math.min(140, Math.max(80, (width - MARGIN * 2 - TAB_GAP) / 2));
        final int bottomButtonsWidth = bottomButtonWidth * 2 + TAB_GAP;
        final int bottomButtonsX = width / 2 - bottomButtonsWidth / 2;
        addDrawableChild(Widgets.button(
            bottomButtonsX, height - BOTTOM_MARGIN + 4, bottomButtonWidth, 20,
            Texts.translatable("confluxmap.screen.config.hotkeys"),
            b -> ConfluxMapClient.get().keybinds().openHotkeySettings(this)
        ));
        addDrawableChild(Widgets.button(
            bottomButtonsX + bottomButtonWidth + TAB_GAP,
            height - BOTTOM_MARGIN + 4,
            bottomButtonWidth,
            20,
            Texts.translatable("confluxmap.screen.waypoint.done"), b -> onClose()
        ));
    }

    private void addTabs() {
        final Category[] categories = Category.values();
        final ConfigTabLayout layout = tabLayout();
        final int startX = width / 2 - layout.totalWidth() / 2;
        for (int index = 0; index < categories.length; index++) {
            final Category c = categories[index];
            final Text label = c == category
                ? Texts.literal("[" + Texts.translatable(c.labelKey).getString() + "]")
                : Texts.translatable(c.labelKey);
            final ButtonWidget tab = Widgets.button(
                startX + index % layout.columns() * (layout.tabWidth() + TAB_GAP),
                TAB_Y + index / layout.columns() * (TAB_HEIGHT + TAB_GAP),
                layout.tabWidth(),
                TAB_HEIGHT,
                label,
                b -> selectCategory(c)
            );
            addDrawableChild(tab);
        }
    }

    private ConfigTabLayout tabLayout() {
        return ConfigTabLayout.fit(
            width, Category.values().length, MARGIN, TAB_GAP, TAB_Y, TAB_HEIGHT
        );
    }

    private void selectCategory(final Category c) {
        category = c;
        scrollOffset = 0;
        rebuild();
    }

    int rowX() {
        return width / 2 - rowWidth / 2;
    }

    TextRenderer textRenderer() {
        return this.textRenderer;
    }

    /** Screen-protected registration, exposed for {@link ConfigRows}. */
    <T extends ClickableWidget> T addRowWidget(final T widget) {
        return addDrawableChild(widget);
    }

    private PredictionSettingsAccess predictionSettingsAccess() {
        final boolean singleplayer = MinecraftClient.getInstance().isInSingleplayer();
        final boolean seedIndependentUnderlay = predictionState.flatBaseline(
            gameBridge.session().dimension()
        ) != null;
        return PredictionSettingsAccess.from(
            singleplayer,
            seedIndependentUnderlay,
            companionSession.seedSharingDisabledByServer(),
            manualSeedService.available(),
            companionSession.mapCorrectionDisabledReasonKey(),
            companionSession.structureSearchAllowed()
        );
    }

    private PlayerMarkerSettingsAccess playerMarkerSettingsAccess() {
        return PlayerMarkerSettingsAccess.from(uiTheme.playerMarker().isPresent());
    }

    @Override
    protected void renderContents(final GuiDraw draw, final int mouseX, final int mouseY, final float tickDelta) {
        draw.renderBackground(this, mouseX, mouseY, tickDelta);
        final String title = getTitle().getString();
        draw.drawTextWithShadow(this.textRenderer, title, width / 2f - this.textRenderer.getWidth(title) / 2f, 8, 0xFFFFFFFF);
        rows.drawIconTextureLabels(draw);
        rows.drawSectionLabels(draw);
        if (category == Category.RADAR && radarAccess.noticeKey() != null) {
            drawRadarPolicyNotice(draw);
        }
        drawScrollbar(draw);
    }

    private void drawScrollbar(final GuiDraw draw) {
        final int viewport = viewportHeight();
        final int maxScroll = Math.max(0, contentHeight - viewport);
        if (maxScroll <= 0) {
            return;
        }
        final int top = rowsTop();
        final int trackHeight = viewport;
        final int thumbHeight = Math.max(12, trackHeight * viewport / contentHeight);
        final int thumbTravel = Math.max(1, trackHeight - thumbHeight);
        final int thumbY = top + (int) Math.round(thumbTravel * (scrollOffset / (double) maxScroll));
        final int x = rowX() + rowWidth + 4;
        draw.fill(x, top, x + 2, top + trackHeight, 0xFF3A3A3A);
        draw.fill(x, thumbY, x + 2, thumbY + thumbHeight, 0xFFFFFFFF);
    }

    private void drawRadarPolicyNotice(final GuiDraw draw) {
        final String notice = Texts.translatable(radarAccess.noticeKey()).getString();
        int y = tabLayout().contentTop() + 2;
        for (final OrderedText line : this.textRenderer.wrapLines(
            StringVisitable.plain(notice), Math.max(40, rowWidth)
        )) {
            draw.drawTextWithShadow(
                this.textRenderer, line, width / 2f - this.textRenderer.getWidth(line) / 2f,
                y, NOTICE_TEXT_COLOR
            );
            y += this.textRenderer.fontHeight + 1;
        }
    }

}
