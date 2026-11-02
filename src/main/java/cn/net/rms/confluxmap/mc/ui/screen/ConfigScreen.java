package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.bridge.GameBridge;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.core.config.ConfigIo;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointAvailability;
import cn.net.rms.confluxmap.core.predict.PredictionState;
import cn.net.rms.confluxmap.core.util.Easing;
import cn.net.rms.confluxmap.core.util.Stagger;
import cn.net.rms.confluxmap.mc.net.CompanionSession;
import cn.net.rms.confluxmap.mc.net.shared.SharedWaypointClient;
import cn.net.rms.confluxmap.mc.predict.ManualSeedService;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import cn.net.rms.confluxmap.mc.ui.UiResourceTheme;
import cn.net.rms.confluxmap.mc.ui.anim.PopupReveal;
import cn.net.rms.confluxmap.mc.ui.anim.TabSwitchFlow;
import cn.net.rms.confluxmap.mc.ui.anim.UiMotion;
import java.util.ArrayList;
import java.util.List;
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
    /**
     * Share of the row fade's timeline that each row waits behind the previous one; the
     * slices compress per-row fades by the same factor, so all rows still finish together.
     */
    private static final float ROW_STAGGER_FRACTION = 0.25f;

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
    /** True while the open reveal should still drive the rows' staggered fade-in. */
    private boolean rowFadeArmed;
    /** Pure two-act tab-switch state: the screen executes its decisions. */
    private final TabSwitchFlow<Category> tabSwitch = new TabSwitchFlow<>();
    /** Reveal of the act currently running; recreated at every act start. */
    private PopupReveal actReveal;
    /** The previous tab's rows and labels, kept fading until the retire act lands. */
    private ConfigRows.RetireSnapshot retiring;
    /** The tab bar's buttons in {@link Category} order, for the instant highlight move. */
    private final List<ButtonWidget> tabButtons = new ArrayList<>();

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
        // Arm for a real open only: a resize re-init lands on a settled reveal, and a
        // parent screen reopened from a child replays its reveal after init, so neither
        // may re-stagger the rows — those fade with the screen like every other widget.
        rowFadeArmed = config.uiTransitionsEnabled && revealOpening();
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
        if (isClosing() || tabSwitch.phase() == TabSwitchFlow.Phase.RETIRING) {
            // While act 1 retires the old rows, a scroll rebuild would lay fresh
            // old-category rows under the fading snapshot.
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
        if (tabSwitch.phase() == TabSwitchFlow.Phase.RETIRING) {
            // Act 1 owns the rows: the old ones are fading from the snapshot, so no new
            // rows may surface under them and the scrollbar stays with the retiring tab.
            reAddRetiringWidgets();
            addBottomButtons();
            return;
        }
        contentHeight = rows.addRows() + scrollOffset - rowsTop();
        addBottomButtons();
    }

    private void addBottomButtons() {
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

    /**
     * Puts the retiring rows back into the widget list after a mid-act rebuild dropped
     * them, so they keep fading where they stood; they stay inactive until the act lands.
     */
    private void reAddRetiringWidgets() {
        if (retiring == null) {
            return;
        }
        for (final ConfigRows.RowSlot slot : retiring.rows()) {
            for (final ClickableWidget widget : slot.widgets()) {
                addDrawableChild(widget);
            }
        }
    }

    private void addTabs() {
        final Category[] categories = Category.values();
        final ConfigTabLayout layout = tabLayout();
        final int startX = width / 2 - layout.totalWidth() / 2;
        // The highlight follows the machine's view, not the category field: during act 1
        // the field stays on the old tab, and a mid-act rebuild must not undo the instant
        // highlight move the click already made.
        final Category highlighted = tabSwitch.highlighted(category);
        tabButtons.clear();
        for (int index = 0; index < categories.length; index++) {
            final Category c = categories[index];
            final ButtonWidget tab = Widgets.button(
                startX + index % layout.columns() * (layout.tabWidth() + TAB_GAP),
                TAB_Y + index / layout.columns() * (TAB_HEIGHT + TAB_GAP),
                layout.tabWidth(),
                TAB_HEIGHT,
                tabLabel(c, c == highlighted),
                b -> selectCategory(c)
            );
            addDrawableChild(tab);
            tabButtons.add(tab);
        }
    }

    private ConfigTabLayout tabLayout() {
        return ConfigTabLayout.fit(
            width, Category.values().length, MARGIN, TAB_GAP, TAB_Y, TAB_HEIGHT
        );
    }

    private void selectCategory(final Category c) {
        switch (tabSwitch.click(c, category, config.uiTransitionsEnabled)) {
            case NONE -> {
            }
            case REBUILD_NOW -> {
                // No animation: drop any half-run act state with the instant rebuild.
                retiring = null;
                actReveal = null;
                switchTo(c);
            }
            case BEGIN_RETIRE -> {
                highlightTab(c);
                beginRetireAct();
            }
            case FAST_FORWARD -> {
                highlightTab(c);
                // Cut the running act short; the next render frame lands it into the
                // queued tab's rebuild.
                actReveal.finishNow();
            }
        }
    }

    /** Swaps the rows in place: the transitions-off path and the pre-transition behavior. */
    private void switchTo(final Category c) {
        category = c;
        scrollOffset = 0;
        rebuild();
    }

    private static Text tabLabel(final Category c, final boolean selected) {
        return selected
            ? Texts.literal("[" + Texts.translatable(c.labelKey).getString() + "]")
            : Texts.translatable(c.labelKey);
    }

    /**
     * Moves the tab bar's {@code [label]} highlight to the clicked tab at the click
     * instant, so the feedback never waits out the two acts; the landing rebuild recreates
     * the same labels from the new category.
     */
    private void highlightTab(final Category c) {
        final Category[] categories = Category.values();
        for (int index = 0; index < tabButtons.size(); index++) {
            tabButtons.get(index).setMessage(tabLabel(categories[index], categories[index] == c));
        }
    }

    /**
     * Opens act 1: the current rows stop being clickable and get snapshotted so a rebuild
     * cannot drop them mid-fade, their reveal starts falling from full alpha, and the old
     * category stays current until the landing rebuild — so tick-driven policy rebuilds
     * keep serving the retiring tab instead of racing the act.
     */
    private void beginRetireAct() {
        // A switch starting inside the open reveal takes the rows over; settling the open
        // stagger first keeps the two drivers from fighting over the same rows.
        settleOpenRowStagger();
        retiring = rows.takeRetireSnapshot();
        boolean focusedIsRetiring = false;
        for (final ConfigRows.RowSlot slot : retiring.rows()) {
            for (final ClickableWidget widget : slot.widgets()) {
                widget.active = false;
                focusedIsRetiring |= getFocused() == widget;
            }
        }
        if (focusedIsRetiring) {
            setFocused(null);
        }
        // The rows stood at full alpha, so the retire act falls from there.
        actReveal = PopupReveal.fallingFrom(
            1.0f, UiMotion.durationSeconds(config.uiTransitionSpeed)
        );
    }

    /**
     * Drives the two-act tab switch: ticks the running act's reveal, applies its per-row
     * alphas before the widgets draw, and lands a finished act — the retire act rebuilds
     * for the queued tab and starts the reveal act, a settled reveal hands the rows back
     * to the inherited path. An idle screen pays a single phase check per frame, and a
     * close fade takes the rows over like the open stagger does.
     */
    private void applyTabSwitch() {
        if (tabSwitch.phase() == TabSwitchFlow.Phase.IDLE) {
            return;
        }
        if (isClosing()) {
            abandonTabSwitch();
            return;
        }
        actReveal.tick(config.uiTransitionsEnabled, UiMotion.durationSeconds(config.uiTransitionSpeed));
        if (actReveal.animating()) {
            applyActAlphas();
            return;
        }
        landTabSwitch();
        if (actReveal != null && actReveal.animating()) {
            // The landing rebuilt fresh rows; their first drawn frame starts the new act
            // at zero instead of flashing in at full alpha.
            applyActAlphas();
        }
    }

    /**
     * One frame of the running act. Act 1 slices its progress across the retiring rows in
     * reverse build order — bottom row first, collecting upward — and act 2 replays the
     * open stagger's build-order slices; rows without a button ride the shared progress
     * through their widget alpha.
     */
    private void applyActAlphas() {
        final float progress = actReveal.progress();
        if (tabSwitch.phase() == TabSwitchFlow.Phase.RETIRING) {
            final List<ConfigRows.RowSlot> retiringRows = retiring.rows();
            final int count = retiringRows.size();
            for (int index = 0; index < count; index++) {
                final float alpha = Easing.easeOutCubic(
                    Stagger.slice(progress, count - 1 - index, count, ROW_STAGGER_FRACTION)
                );
                for (final ClickableWidget widget : retiringRows.get(index).widgets()) {
                    if (widget instanceof RowButton button) {
                        button.setRowAlpha(alpha);
                    } else {
                        widget.setAlpha(alpha);
                    }
                }
            }
            return;
        }
        final List<RowButton> buttons = rows.rowButtons();
        final int count = buttons.size();
        for (int index = 0; index < count; index++) {
            buttons.get(index).setRowAlpha(
                Easing.easeOutCubic(Stagger.slice(progress, index, count, ROW_STAGGER_FRACTION))
            );
        }
        final float shared = actReveal.eased();
        for (final ConfigRows.RowSlot slot : rows.rowSlots()) {
            for (final ClickableWidget widget : slot.widgets()) {
                if (!(widget instanceof RowButton)) {
                    widget.setAlpha(shared);
                }
            }
        }
    }

    private void landTabSwitch() {
        final TabSwitchFlow.Landing<Category> landing = tabSwitch.land();
        switch (landing.action()) {
            case REBUILD_AND_REVEAL -> {
                retiring = null;
                actReveal = new PopupReveal();
                actReveal.setOpen(true);
                switchTo(landing.target());
            }
            case SETTLE -> {
                settleRevealActRows();
                retiring = null;
                actReveal = null;
            }
            case NONE -> {
            }
        }
    }

    /** Hands the reveal act's rows back to the inherited path, like the open stagger's settle. */
    private void settleRevealActRows() {
        for (final RowButton button : rows.rowButtons()) {
            button.settleRowAlpha();
        }
        for (final ConfigRows.RowSlot slot : rows.rowSlots()) {
            for (final ClickableWidget widget : slot.widgets()) {
                if (!(widget instanceof RowButton)) {
                    widget.setAlpha(1.0f);
                }
            }
        }
    }

    /**
     * Hands every act-driven row back to the inherited path so the screen's own close fade
     * moves them: a scoped row would keep its private alpha while the screen dims around it.
     */
    private void abandonTabSwitch() {
        settleRevealActRows();
        if (retiring != null) {
            for (final ConfigRows.RowSlot slot : retiring.rows()) {
                for (final ClickableWidget widget : slot.widgets()) {
                    if (widget instanceof RowButton button) {
                        button.settleRowAlpha();
                    } else {
                        widget.setAlpha(1.0f);
                    }
                }
            }
            retiring = null;
        }
        tabSwitch.reset();
        actReveal = null;
    }

    /**
     * Label alpha for the live label lists: during act 2 a label follows its row — the
     * staggered button alpha, or the shared fade for rows without a button — and any other
     * frame leaves the exact color (the screen reveal scales labels on its own).
     */
    private float actLabelAlpha(final ConfigRows.RowSlot slot) {
        if (slot.button() != null) {
            return slot.button().rowAlpha();
        }
        return actReveal.eased();
    }

    /** Label alpha for the retiring snapshot: the followed row's reverse-order fade. */
    private float retiringLabelAlpha(final ConfigRows.RowSlot slot) {
        final List<ConfigRows.RowSlot> retiringRows = retiring.rows();
        for (int index = 0; index < retiringRows.size(); index++) {
            if (retiringRows.get(index) == slot) {
                return Easing.easeOutCubic(Stagger.slice(
                    actReveal.progress(), retiringRows.size() - 1 - index, retiringRows.size(),
                    ROW_STAGGER_FRACTION
                ));
            }
        }
        return 1.0f;
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

    /**
     * Drives the rows' staggered fade-in through the open reveal's shared progress, sliced
     * per row so the buttons appear top to bottom. Runs after the reveal frame has set the
     * uniform widget alpha, so the per-row values win for this frame. A rebuild mid-fade
     * hands its new buttons the current progress, and the first non-opening frame restores
     * the exact pre-flight rows — a settled screen then pays a single flag check per frame.
     */
    private void applyRowStagger() {
        if (!rowFadeArmed) {
            return;
        }
        if (!revealOpening()) {
            // The reveal landed or a close fade took over. Un-scope the rows so the
            // screen's own fade handles both render channels from here — on a close-fade
            // frame a scoped row would stay opaque while the screen dims.
            settleOpenRowStagger();
            return;
        }
        final float progress = revealProgress();
        final List<RowButton> buttons = rows.rowButtons();
        final int count = buttons.size();
        for (int index = 0; index < count; index++) {
            buttons.get(index).setRowAlpha(
                Easing.easeOutCubic(Stagger.slice(progress, index, count, ROW_STAGGER_FRACTION))
            );
        }
    }

    /** Disarms the open stagger and hands its rows back to the inherited path. */
    private void settleOpenRowStagger() {
        if (!rowFadeArmed) {
            return;
        }
        rowFadeArmed = false;
        for (final RowButton button : rows.rowButtons()) {
            button.settleRowAlpha();
        }
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
        applyTabSwitch();
        applyRowStagger();
        draw.renderBackground(this, mouseX, mouseY, tickDelta);
        final String title = getTitle().getString();
        draw.drawTextWithShadow(this.textRenderer, title, width / 2f - this.textRenderer.getWidth(title) / 2f, 8, 0xFFFFFFFF);
        if (retiring == null) {
            // During act 1 the snapshot replaces the live lists: before a rebuild they hold
            // exactly the labels that are retiring, after one they hold the rebuilt tab's.
            final ConfigRows.RowAlpha labelAlpha =
                tabSwitch.phase() == TabSwitchFlow.Phase.REVEALING
                    ? this::actLabelAlpha
                    : ConfigRows.RowAlpha.OPAQUE;
            rows.drawIconTextureLabels(draw, labelAlpha);
            rows.drawSectionLabels(draw, labelAlpha);
        } else {
            rows.drawRetiringLabels(draw, retiring, this::retiringLabelAlpha);
        }
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
