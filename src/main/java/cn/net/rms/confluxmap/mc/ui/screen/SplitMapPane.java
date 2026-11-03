package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.compat.MouseButtons;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.core.waypoint.WaypointRenderEntry;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import cn.net.rms.confluxmap.mc.ui.anim.PopupReveal;
import cn.net.rms.confluxmap.mc.ui.anim.PopupRevealFrame;
import cn.net.rms.confluxmap.mc.ui.anim.UiMotion;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.widget.ButtonWidget;

/**
 * Interactive left-hand map pane shared by the structure picker and candidate browser.
 * Also hosts the fullscreen map's right-click location menu: the pane owns the menu
 * state and button wiring, while target capture and action execution stay on the
 * embedded {@link FullscreenMapScreen}.
 */
final class SplitMapPane {
    private static final int PANEL_BACKGROUND = 0xB0101018;
    private static final int BLURRED_PANEL_BACKGROUND = 0x70101018;
    private static final int DIVIDER_COLOR = 0xB0454554;

    private final FullscreenMapScreen map;
    private boolean dragging;

    /**
     * Reveal of the search panel itself. It exists as soon as its screen does, so the fade
     * comes from the screen's own open reveal - both start on the first frame with the same
     * config tier, and installing this site's own alpha would clobber ConfluxScreen's single
     * static value for everything drawn after the pane. Only the slide lives here.
     */
    private final PopupReveal paneReveal = new PopupReveal();

    private FullscreenMapLocationMenu.Bounds menuBounds;
    private FullscreenMapLocationMenu.Target menuTarget;
    private WaypointRenderEntry menuWaypoint;
    private UUID menuPlayerId;
    private FullscreenMapLocationMenu.Action pendingMenuAction;
    private boolean menuDeletePending;
    private List<FullscreenMapLocationMenu.ButtonSpec> menuSpecs = List.of();
    /** Reveal of the embedded location menu panel; host state survives widget rebuilds. */
    private final PopupReveal menuReveal = new PopupReveal();
    /** Panel geometry kept across a close so the fade-out has something to draw. */
    private FullscreenMapLocationMenu.Bounds fadingMenuBounds;
    /**
     * Screen the open menu belongs to, kept for the close fade so the frame can still fade
     * its buttons; the pane lives and dies with one host screen.
     */
    private ConfluxScreen menuHost;

    SplitMapPane(final FullscreenMapScreen map) {
        this.map = map;
        paneReveal.setOpen(true);
    }

    void render(
        final GuiDraw draw,
        final int mouseX,
        final int mouseY,
        final float tickDelta,
        final SplitMapLayout layout
    ) {
        map.renderEmbedded(draw, mouseX, mouseY, tickDelta, layout);
        paneReveal.tick(
            map.config.uiTransitionsEnabled,
            UiMotion.durationSeconds(map.config.uiTransitionSpeed)
        );
        // Only the slide lives here (see paneReveal); the screen's reveal alpha covers it.
        try (PopupRevealFrame frame = PopupRevealFrame.slideOnly(
            draw, paneReveal.fadeVisible(map.config.uiTransitionsEnabled), paneReveal.eased(),
            -PopupRevealFrame.ANCHOR_SLIDE_PIXELS, 0f
        )) {
            final boolean blurred = map.renderEmbeddedPanelBlur(draw, layout);
            draw.fill(
                layout.panelLeft(), 0,
                layout.screenWidth(), layout.screenHeight(),
                blurred ? BLURRED_PANEL_BACKGROUND : PANEL_BACKGROUND
            );
        }
        draw.fill(
            layout.panelLeft(), 0,
            layout.panelLeft() + 1, layout.screenHeight(),
            DIVIDER_COLOR
        );
        drawMenuPanel(draw);
    }

    private void drawMenuPanel(final GuiDraw draw) {
        menuReveal.tick(
            map.config.uiTransitionsEnabled,
            UiMotion.durationSeconds(map.config.uiTransitionSpeed)
        );
        final FullscreenMapLocationMenu.Bounds bounds = menuBounds != null
            ? menuBounds
            : menuFadeVisible() ? fadingMenuBounds : null;
        if (bounds == null) {
            return;
        }
        // menuHost is set by the same openMenu that produced these bounds or their fading twin.
        try (PopupRevealFrame frame = PopupRevealFrame.fadeAndSlide(
            draw, menuFadeVisible(), menuReveal.eased(),
            0f, PopupRevealFrame.ANCHOR_SLIDE_PIXELS, menuHost.embeddedLocationMenuButtons()
        )) {
            FullscreenMapLocationMenu.drawPanel(draw, bounds);
        }
    }

    /** True while the menu close fade may still draw: transitions on and reveal in flight. */
    private boolean menuFadeVisible() {
        return map.config.uiTransitionsEnabled && menuReveal.animating();
    }

    boolean mouseClicked(
        final ConfluxScreen host,
        final double mouseX,
        final double mouseY,
        final int button,
        final SplitMapLayout layout
    ) {
        if (button == MouseButtons.RIGHT && layout.containsMap(mouseX, mouseY)) {
            openMenu(host, mouseX, mouseY, layout);
            return true;
        }
        if (button != MouseButtons.LEFT || !layout.containsMap(mouseX, mouseY)) {
            return false;
        }
        dragging = true;
        return true;
    }

    boolean mouseDragged(
        final int button,
        final double deltaX,
        final double deltaY
    ) {
        if (button != MouseButtons.LEFT || !dragging) {
            return false;
        }
        map.panEmbedded(deltaX, deltaY);
        return true;
    }

    boolean mouseReleased(final int button) {
        if (button != MouseButtons.LEFT || !dragging) {
            return false;
        }
        dragging = false;
        return true;
    }

    boolean mouseScrolled(
        final ConfluxScreen host,
        final double mouseX,
        final double mouseY,
        final double amount,
        final SplitMapLayout layout
    ) {
        if (amount == 0.0 || !layout.containsMap(mouseX, mouseY)) {
            return false;
        }
        if (menuOpen()) {
            closeMenu(host);
            return true;
        }
        return map.zoomEmbedded(mouseX, mouseY, amount, layout);
    }

    boolean menuOpen() {
        return menuBounds != null && menuTarget != null;
    }

    boolean menuContains(final double mouseX, final double mouseY) {
        return menuOpen() && menuBounds.contains(mouseX, mouseY);
    }

    /**
     * Handles a click outside the open menu: dismiss it, or reposition it when the click is
     * another right-click on the map. Mirrors the fullscreen screen's menu dismissal.
     */
    void menuClickedOutside(
        final ConfluxScreen host,
        final double mouseX,
        final double mouseY,
        final int button,
        final SplitMapLayout layout
    ) {
        if (button == MouseButtons.RIGHT && layout.containsMap(mouseX, mouseY)) {
            openMenu(host, mouseX, mouseY, layout);
            return;
        }
        closeMenu(host);
    }

    /**
     * Performs the action armed by the last menu-button press. Runs after the host's widget
     * dispatch returns, because the delete confirmation and action screen changes rebuild
     * the widget list that dispatch iterates.
     */
    void consumePendingMenuAction(final ConfluxScreen host) {
        final FullscreenMapLocationMenu.Action action = pendingMenuAction;
        pendingMenuAction = null;
        if (action == null) {
            return;
        }
        if (action == FullscreenMapLocationMenu.Action.DELETE_WAYPOINT && !menuDeletePending) {
            menuDeletePending = true;
            host.rebuildForEmbeddedLocationMenu();
            host.clearFocusForOpenMenu();
            return;
        }
        final FullscreenMapLocationMenu.Target target = menuTarget;
        final WaypointRenderEntry waypoint = menuWaypoint;
        final UUID playerId = menuPlayerId;
        closeMenu(host);
        map.runLocationAction(action, target, waypoint, playerId, host);
    }

    /** Re-adds the menu buttons onto a freshly rebuilt host screen. */
    void addMenuButtons(final ConfluxScreen host) {
        if (!menuOpen()) {
            return;
        }
        final List<FullscreenMapLocationMenu.ButtonSpec> specs = map.locationMenuButtonSpecs(
            menuTarget, menuWaypoint, menuPlayerId, menuDeletePending
        );
        menuSpecs = specs;
        for (int index = 0; index < specs.size(); index++) {
            final FullscreenMapLocationMenu.ButtonSpec spec = specs.get(index);
            final ButtonWidget button = host.hostLocationMenuButton(Widgets.button(
                menuBounds.buttonX(),
                menuBounds.buttonY(index),
                menuBounds.buttonWidth(),
                FullscreenMapLocationMenu.BUTTON_HEIGHT,
                Texts.translatable(spec.labelKey()),
                ignored -> pendingMenuAction = spec.action()
            ));
            button.active = spec.active();
            host.setLocationMenuTooltip(button, spec.tooltipKey());
        }
    }

    /**
     * Offers the open menu's digit shortcuts. Runs the same pending-action path as a click,
     * so the delete confirmation two-step stays identical; the shared funnel in
     * {@link ConfluxScreen} guarantees no text field holds focus.
     */
    boolean menuHotkeyPressed(final ConfluxScreen host, final int keyCode) {
        if (!menuOpen()) {
            return false;
        }
        final FullscreenMapLocationMenu.Action action =
            FullscreenMapLocationMenu.actionForHotkey(menuSpecs, keyCode);
        if (action == null) {
            return false;
        }
        pendingMenuAction = action;
        consumePendingMenuAction(host);
        return true;
    }

    /** Draws the digit hints for the open menu on top of its rendered buttons. */
    void drawMenuHotkeys(final GuiDraw draw, final TextRenderer font) {
        if (menuBounds != null) {
            FullscreenMapLocationMenu.drawHotkeyHints(draw, font, menuBounds, menuSpecs);
        }
    }

    private void openMenu(
        final ConfluxScreen host,
        final double mouseX,
        final double mouseY,
        final SplitMapLayout layout
    ) {
        final FullscreenMapLocationMenu.Capture capture =
            map.openEmbeddedLocationMenu(mouseX, mouseY, layout);
        menuBounds = capture.bounds();
        menuTarget = capture.target();
        menuWaypoint = capture.waypoint();
        menuPlayerId = capture.playerId();
        pendingMenuAction = null;
        menuDeletePending = false;
        menuReveal.setOpen(true);
        menuHost = host;
        host.rebuildForEmbeddedLocationMenu();
        host.clearFocusForOpenMenu();
    }

    private void closeMenu(final ConfluxScreen host) {
        fadingMenuBounds = menuBounds;
        menuBounds = null;
        menuTarget = null;
        menuWaypoint = null;
        menuPlayerId = null;
        pendingMenuAction = null;
        menuDeletePending = false;
        menuSpecs = List.of();
        menuReveal.setOpen(false);
        host.rebuildForEmbeddedLocationMenu();
    }
}
