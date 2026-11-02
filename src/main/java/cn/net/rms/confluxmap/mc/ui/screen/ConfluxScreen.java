package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import cn.net.rms.confluxmap.mc.ui.anim.ScreenCloseFlow;
import cn.net.rms.confluxmap.mc.ui.anim.ScreenReveal;
import cn.net.rms.confluxmap.mc.ui.anim.TransitionClock;
import cn.net.rms.confluxmap.mc.ui.anim.UiMotion;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
//#if MC>=12000
//$$ import net.minecraft.client.gui.DrawContext;
//#else
import net.minecraft.client.gui.Element;
import net.minecraft.client.util.math.MatrixStack;
//#endif
//#if MC>=12109
//$$ import net.minecraft.client.gui.Click;
//$$ import net.minecraft.client.input.KeyInput;
//#endif
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/** Screen base that keeps the MatrixStack-to-DrawContext rewrite at one lifecycle seam. */
public abstract class ConfluxScreen extends Screen {
    private final Map<ClickableWidget, String> disabledTooltipKeys = new IdentityHashMap<>();
    private final Map<ClickableWidget, String> locationMenuTooltipKeys = new LinkedHashMap<>();
    private final List<ButtonWidget> embeddedLocationMenuButtons = new ArrayList<>();
    private final HotkeyFocusDelay initialFocusDelay = new HotkeyFocusDelay();
    private Runnable enterAction;
    private BooleanSupplier enterActionEnabled = () -> false;
    private ClickableWidget deferredInitialFocus;
    private final ScreenReveal reveal = new ScreenReveal();
    private final TransitionClock revealClock = new TransitionClock();
    // Close orchestration state: requestClose arms all three together, and whatever
    // finally performs the switch (fade completion, interrupt or removed()) clears them
    // together, so the deferred cleanup runs exactly once.
    private boolean closePending;
    private Screen closeTarget;
    private Runnable closeCleanup;

    protected ConfluxScreen(final Text title) {
        super(title);
        reveal.beginOpen();
    }

    /**
     * Scale the screen starts its reveal from; heavier screens can dip lower for a more
     * visible settle.
     */
    protected float revealStartScale() {
        return 1.0f - 0.02f;
    }

    /**
     * Opens one reveal frame around the whole render pass: ticks the open animation, then
     * installs the center scale and reveal alpha. Returns false without touching anything
     * unless the animation is actually in flight, so a settled screen keeps the exact
     * pre-transition render path.
     */
    private boolean beginRevealFrame(final GuiDraw draw) {
        final ConfluxConfig config = ConfluxMapClient.get().config();
        if (!config.uiTransitionsEnabled) {
            reveal.finishNow();
            return false;
        }
        if (reveal.animating()) {
            reveal.tick(revealClock.tick(), UiMotion.durationSeconds(config.uiTransitionSpeed));
        }
        // A fade that just landed holds its fully-closed pose (alpha 0) until tick()
        // performs the deferred switch, so the screen never flashes open again in the
        // frames between the last fade step and the switch; the settled OPEN fast path
        // still skips straight through.
        if (!reveal.animating() && !holdingClosedPose()) {
            return false;
        }
        final float eased = reveal.eased();
        final float startScale = revealStartScale();
        // The pose fold pushes the context level the screen transform's translates re-fold
        // onto, so it must run first on 1.21.8+; endRevealFrame restores in mirror order.
        draw.applyToContextPose();
        draw.beginScreenTransform(startScale + (1.0f - startScale) * eased, width, height);
        GuiDraw.beginReveal(eased);
        applyWidgetReveal(eased);
        return true;
    }

    /** Undoes {@link #beginRevealFrame}; call only when it returned true. */
    private void endRevealFrame(final GuiDraw draw) {
        applyWidgetReveal(1.0f);
        GuiDraw.endReveal();
        draw.restoreContextPose();
        draw.endScreenTransform();
    }

    /**
     * Fades the screen's vanilla-rendered widgets through their own alpha during a reveal
     * frame — the color outlets never see them. Widgets that fade themselves through the
     * shape layer opt out via {@link SelfFadingWidget}, so the two channels do not compound.
     */
    private void applyWidgetReveal(final float alpha) {
        for (final var child : children()) {
            if (child instanceof ClickableWidget widget && !(child instanceof SelfFadingWidget)) {
                widget.setAlpha(alpha);
            }
        }
    }

    /** True while the screen is already invisible and only waiting for tick() to switch. */
    private boolean holdingClosedPose() {
        return closePending && reveal.phase() == ScreenReveal.Phase.CLOSED;
    }

    /**
     * Closes this screen through the transition orchestration. Closing to the world fades
     * out in place and defers the real switch until the fade lands; closing to a parent
     * screen switches instantly and has the parent replay its own open reveal.
     */
    protected final void requestClose(final Screen target) {
        requestClose(target, null);
    }

    /**
     * {@code cleanup} runs just before the target screen takes over. Pass the work that
     * must still observe this screen's live state (viewport clears, flushes); it runs
     * exactly once — when the fade lands, or in {@code removed()} when the screen was
     * force-replaced before the fade could finish. A second request while a fade is
     * already running only cuts it short; the first request's cleanup wins.
     */
    protected final void requestClose(final Screen target, final Runnable cleanup) {
        final ConfluxConfig config = ConfluxMapClient.get().config();
        switch (ScreenCloseFlow.decideClose(config.uiTransitionsEnabled, target != null, closePending)) {
            case DEFER_FADE -> {
                reveal.beginClose();
                closeTarget = target;
                closeCleanup = cleanup;
                closePending = true;
            }
            case FINISH_PENDING_FADE -> finishPendingCloseNow();
            case SWITCH_NOW_REOPEN_TARGET -> {
                disarmPendingClose();
                if (cleanup != null) {
                    cleanup.run();
                }
                MinecraftAccess.setScreen(MinecraftClient.getInstance(), target);
                if (target instanceof ConfluxScreen parent) {
                    parent.reopen();
                }
            }
            case SWITCH_NOW -> {
                disarmPendingClose();
                if (cleanup != null) {
                    cleanup.run();
                }
                closeNow(target);
            }
        }
    }

    /** True while this screen is fading out and has not handed control to its target yet. */
    public boolean isClosing() {
        return closePending;
    }

    /**
     * Replays this screen's open reveal. Called on the parent screen a child just closed
     * back to, so round-trip navigation reads as one motion instead of stacking a second
     * fade onto the already-settled screen.
     */
    void reopen() {
        reveal.beginOpen();
        revealClock.reset();
    }

    /**
     * Raw progress of this screen's reveal in [0, 1]; 1 fully open, 0 fully closed — for
     * callers slicing their own sub-progress off the open transition. The clock stays with
     * the reveal frame, so readers must run inside the render pass.
     */
    float revealProgress() {
        return reveal.progress();
    }

    /** True only while the open reveal is climbing; a close fade or a settled screen is false. */
    boolean revealOpening() {
        return reveal.phase() == ScreenReveal.Phase.OPENING;
    }

    private void finishPendingCloseNow() {
        reveal.finishNow();
        completePendingClose();
    }

    /** Clears armed close state without running anything; the caller takes over the close. */
    private void disarmPendingClose() {
        closeTarget = null;
        closeCleanup = null;
        closePending = false;
    }

    /** Runs the deferred cleanup and hands control to the target recorded at request time. */
    private void completePendingClose() {
        final Screen target = closeTarget;
        final Runnable cleanup = closeCleanup;
        disarmPendingClose();
        if (cleanup != null) {
            cleanup.run();
        }
        closeNow(target);
    }

    private void closeNow(final Screen target) {
        if (target == null) {
            // Vanilla's own close-to-world switch, so per-version onClose behavior stays intact.
            super.onClose();
        } else {
            MinecraftAccess.setScreen(MinecraftClient.getInstance(), target);
        }
    }

    @Override
    public void removed() {
        // Death, a world change or another mod can force-replace this screen before the
        // fade lands, and removed() then runs with the switch already made. Flush the
        // deferred cleanup here so it still happens exactly once, without switching
        // screens from inside this callback.
        final Runnable cleanup = closeCleanup;
        disarmPendingClose();
        if (cleanup != null) {
            cleanup.run();
        }
        super.removed();
    }
    //#if MC>=12000
    //$$ /**
    //$$  * Screen.render owns the widget loop, but its implicit background must not cover
    //$$  * renderContents. Only 1.21.5 and older reach that path: 1.21.6 moved the background
    //$$  * call up into Screen.renderWithTooltip, which already runs before renderContents.
    //$$  */
    //$$ private boolean renderingVanillaWidgets;
    //#endif

    /** Leaves a new text field unfocused until the opening key's character event has drained. */
    protected final void deferInitialFocusUntilNextTick(final ClickableWidget widget) {
        deferredInitialFocus = widget;
        initialFocusDelay.defer();
        setFocused(null);
    }

    //#if MC>=12109
    //$$ @Override
    //$$ protected void setInitialFocus() {
    //$$     if (initialFocusDelay.shouldFocus()) {
    //$$         super.setInitialFocus();
    //$$     }
    //$$ }
    //#endif

    @Override
    public void tick() {
        super.tick();
        // The deferred switch lands in a tick, not inside render: a stable phase for
        // setScreen, and until it happens the screen stays current, so the HUD-yield
        // checks that read the active screen keep yielding through the whole fade.
        if (holdingClosedPose()) {
            completePendingClose();
        }
        if (deferredInitialFocus == null) {
            return;
        }
        initialFocusDelay.advanceTick();
        if (initialFocusDelay.shouldFocus()) {
            final ClickableWidget widget = deferredInitialFocus;
            deferredInitialFocus = null;
            setInitialFocus(widget);
        }
    }

    //#if MC>=260100
    //$$ @Override
    //$$ public final void extractRenderState(
    //$$     final GuiGraphicsExtractor context,
    //$$     final int mouseX,
    //$$     final int mouseY,
    //$$     final float tickDelta
    //$$ ) {
    //$$     final GuiDraw draw = GuiDraw.of(context);
    //$$     final boolean revealFrame = beginRevealFrame(draw);
    //$$     try {
    //$$         renderContents(draw, mouseX, mouseY, tickDelta);
    //$$         // 26.1 renamed the retained-mode entry points but kept the ordering: the background is
    //$$         // extracted before the widget list is walked.
    //$$         renderingVanillaWidgets = true;
    //$$         try {
    //$$             super.extractRenderState(context, mouseX, mouseY, tickDelta);
    //$$         } finally {
    //$$             renderingVanillaWidgets = false;
    //$$         }
    //$$         renderAfterWidgets(draw, mouseX, mouseY, tickDelta);
    //$$         renderDisabledTooltip(draw, mouseX, mouseY);
    //$$         renderLocationMenuTooltip(draw, mouseX, mouseY);
    //$$     } finally {
    //$$         if (revealFrame) {
    //$$             endRevealFrame(draw);
    //$$         }
    //$$     }
    //$$ }
    //$$
    //$$ @Override
    //$$ public final void extractBackground(
    //$$     final GuiGraphicsExtractor context,
    //$$     final int mouseX,
    //$$     final int mouseY,
    //$$     final float tickDelta
    //$$ ) {
    //$$     if (!renderingVanillaWidgets) {
    //$$         renderVanillaBackground(context, mouseX, mouseY, tickDelta);
    //$$     }
    //$$ }
    //$$
    //$$ protected void renderVanillaBackground(
    //$$     final GuiGraphicsExtractor context,
    //$$     final int mouseX,
    //$$     final int mouseY,
    //$$     final float tickDelta
    //$$ ) {
    //$$     super.extractBackground(context, mouseX, mouseY, tickDelta);
    //$$ }
    //#elseif MC>=12000
    //$$ @Override
    //$$ public final void render(
    //$$     final DrawContext context,
    //$$     final int mouseX,
    //$$     final int mouseY,
    //$$     final float tickDelta
    //$$ ) {
    //$$     final GuiDraw draw = GuiDraw.of(context);
    //$$     final boolean revealFrame = beginRevealFrame(draw);
    //$$     try {
    //$$         renderContents(draw, mouseX, mouseY, tickDelta);
    //$$         // Modern Screen.render invokes renderBackground before iterating its private widget list.
    //$$         renderingVanillaWidgets = true;
    //$$         try {
    //$$             super.render(context, mouseX, mouseY, tickDelta);
    //$$         } finally {
    //$$             renderingVanillaWidgets = false;
    //$$         }
    //$$         renderAfterWidgets(draw, mouseX, mouseY, tickDelta);
    //$$         renderDisabledTooltip(draw, mouseX, mouseY);
    //$$         renderLocationMenuTooltip(draw, mouseX, mouseY);
    //$$     } finally {
    //$$         if (revealFrame) {
    //$$             endRevealFrame(draw);
    //$$         }
    //$$     }
    //$$ }
    //$$
    //#if MC>=12002
    //$$ @Override
    //$$ public final void renderBackground(
    //$$     final DrawContext context,
    //$$     final int mouseX,
    //$$     final int mouseY,
    //$$     final float tickDelta
    //$$ ) {
    //$$     if (!renderingVanillaWidgets) {
    //$$         renderVanillaBackground(context, mouseX, mouseY, tickDelta);
    //$$     }
    //$$ }
    //$$
    //$$ protected void renderVanillaBackground(
    //$$     final DrawContext context,
    //$$     final int mouseX,
    //$$     final int mouseY,
    //$$     final float tickDelta
    //$$ ) {
    //$$     super.renderBackground(context, mouseX, mouseY, tickDelta);
    //$$ }
    //#else
    //$$ @Override
    //$$ public final void renderBackground(final DrawContext context) {
    //$$     if (!renderingVanillaWidgets) {
    //$$         renderVanillaBackground(context);
    //$$     }
    //$$ }
    //$$
    //$$ protected void renderVanillaBackground(final DrawContext context) {
    //$$     super.renderBackground(context);
    //$$ }
    //#endif
    //#else
    @Override
    public final void render(
        final MatrixStack matrices,
        final int mouseX,
        final int mouseY,
        final float tickDelta
    ) {
        final GuiDraw draw = GuiDraw.of(matrices);
        final boolean revealFrame = beginRevealFrame(draw);
        try {
            renderContents(draw, mouseX, mouseY, tickDelta);
            super.render(matrices, mouseX, mouseY, tickDelta);
            renderAfterWidgets(draw, mouseX, mouseY, tickDelta);
            renderDisabledTooltip(draw, mouseX, mouseY);
            renderLocationMenuTooltip(draw, mouseX, mouseY);
        } finally {
            if (revealFrame) {
                endRevealFrame(draw);
            }
        }
    }
    //#endif

    protected abstract void renderContents(GuiDraw draw, int mouseX, int mouseY, float tickDelta);

    /** Makes Enter activate the screen's visible primary action when it is available. */
    protected final void setEnterAction(
        final BooleanSupplier enabled,
        final Runnable action
    ) {
        enterActionEnabled = enabled;
        enterAction = action;
    }

    /** Removes an Enter binding when a rebuilt screen no longer exposes that action. */
    protected final void clearEnterAction() {
        enterActionEnabled = () -> false;
        enterAction = null;
    }

    @Override
    //#if MC>=12109
    //$$ public boolean keyPressed(final KeyInput input) {
    //$$     final int keyCode = input.key();
    //$$     if (swallowWhileClosing(input)) {
    //$$         return true;
    //$$     }
    //#else
    public boolean keyPressed(final int keyCode, final int scanCode, final int modifiers) {
        if (swallowWhileClosing(keyCode, scanCode)) {
            return true;
        }
    //#endif
        if (!(getFocused() instanceof TextFieldWidget) && embeddedMenuHotkeyPressed(keyCode)) {
            return true;
        }
        if ((keyCode == cn.net.rms.confluxmap.compat.Keys.ENTER || keyCode == cn.net.rms.confluxmap.compat.Keys.KP_ENTER)
            && enterAction != null && enterActionEnabled.getAsBoolean()) {
            enterAction.run();
            return true;
        }
        //#if MC>=12109
        //$$ return super.keyPressed(input);
        //#else
        return super.keyPressed(keyCode, scanCode, modifiers);
        //#endif
    }

    /**
     * While a close fade is pending, keyboard input is swallowed so a dying screen cannot
     * trigger actions — except the interrupt keys, which cut the fade short: a rapid
     * double-press of ESC or of the keybind that opened the screen must never wait out
     * the animation. Subclasses that override this should route their first key handling
     * through it before their own branches.
     */
    //#if MC>=12109
    //$$ protected final boolean swallowWhileClosing(final KeyInput input) {
    //$$     if (!closePending) {
    //$$         return false;
    //$$     }
    //$$     if (isCloseInterruptKey(input)) {
    //$$         finishPendingCloseNow();
    //$$     }
    //$$     return true;
    //$$ }
    //$$
    //$$ /** ESC ends the fade immediately; subclasses widen this with their own open key. */
    //$$ protected boolean isCloseInterruptKey(final KeyInput input) {
    //$$     return input.key() == cn.net.rms.confluxmap.compat.Keys.ESCAPE;
    //$$ }
    //#else
    protected final boolean swallowWhileClosing(final int keyCode, final int scanCode) {
        if (!closePending) {
            return false;
        }
        if (isCloseInterruptKey(keyCode, scanCode)) {
            finishPendingCloseNow();
        }
        return true;
    }

    /** ESC ends the fade immediately; subclasses widen this with their own open key. */
    protected boolean isCloseInterruptKey(final int keyCode, final int scanCode) {
        return keyCode == cn.net.rms.confluxmap.compat.Keys.ESCAPE;
    }
    //#endif

    @Override
    //#if MC>=12109
    //$$ public boolean mouseClicked(final Click click, final boolean doubledClick) {
    //$$     if (isClosing()) {
    //$$         return true;
    //$$     }
    //$$     return super.mouseClicked(click, doubledClick);
    //$$ }
    //$$
    //$$ @Override
    //$$ public boolean mouseReleased(final Click click) {
    //$$     if (isClosing()) {
    //$$         return true;
    //$$     }
    //$$     return super.mouseReleased(click);
    //$$ }
    //#else
    public boolean mouseClicked(final double mouseX, final double mouseY, final int button) {
        if (isClosing()) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    public boolean mouseReleased(final double mouseX, final double mouseY, final int button) {
        if (isClosing()) {
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }
    //#endif

    @Override
    //#if MC>=12002
    //$$ public boolean mouseScrolled(
    //$$     final double mouseX,
    //$$     final double mouseY,
    //$$     final double horizontalAmount,
    //$$     final double amount
    //$$ ) {
    //$$     if (isClosing()) {
    //$$         return true;
    //$$     }
    //$$     return super.mouseScrolled(mouseX, mouseY, horizontalAmount, amount);
    //$$ }
    //#else
    public boolean mouseScrolled(final double mouseX, final double mouseY, final double amount) {
        if (isClosing()) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }
    //#endif

    //#if MC<11904
    @Override
    public void setFocused(final Element element) {
        final Element previous = getFocused();
        super.setFocused(element);
        if (previous != element && previous instanceof TextFieldWidget field) {
            Widgets.unfocus(field);
        }
    }
    //#endif

    /**
     * Offers a digit shortcut for the open embedded location menu. Overridden by the
     * fullscreen map for its own menu and by split-map hosts for the pane's menu; the
     * caller guarantees no text field holds focus, so typing always wins.
     */
    protected boolean embeddedMenuHotkeyPressed(final int keyCode) {
        return false;
    }

    /**
     * Drops keyboard focus so menu digit shortcuts are not captured by a text input.
     * Rebuilds re-run the host's {@code init()}, which refocuses its search field, so the
     * pane calls this after every rebuild that leaves the menu open.
     */
    void clearFocusForOpenMenu() {
        setFocused(null);
    }

    protected void renderAfterWidgets(
        final GuiDraw draw,
        final int mouseX,
        final int mouseY,
        final float tickDelta
    ) {
    }

    /** Draws a stable monochrome scrollbar for a row-based viewport. */
    protected final void drawListScrollbar(
        final GuiDraw draw,
        final int x,
        final int top,
        final int height,
        final int totalRows,
        final int visibleRows,
        final int offset
    ) {
        drawScrollbar(draw, x, top, height, totalRows, visibleRows, offset, false);
    }

    /**
     * The same scrollbar for a popup that floats over earlier GUI text (a dropdown): the
     * fills go through {@link GuiDraw#fillPopup} so glyph depth left by that text cannot
     * punch holes in the track or thumb.
     */
    protected final void drawPopupScrollbar(
        final GuiDraw draw,
        final int x,
        final int top,
        final int height,
        final int totalRows,
        final int visibleRows,
        final int offset
    ) {
        drawScrollbar(draw, x, top, height, totalRows, visibleRows, offset, true);
    }

    private static void drawScrollbar(
        final GuiDraw draw,
        final int x,
        final int top,
        final int height,
        final int totalRows,
        final int visibleRows,
        final int offset,
        final boolean popup
    ) {
        if (totalRows <= visibleRows || visibleRows <= 0 || height <= 0) {
            return;
        }
        final int thumbHeight = Math.max(10, height * visibleRows / totalRows);
        final int maxOffset = totalRows - visibleRows;
        final int travel = Math.max(1, height - thumbHeight);
        final int thumbY = top + Math.round(travel * (offset / (float) maxOffset));
        if (popup) {
            draw.fillPopup(x, top, x + 2, top + height, 0xFF3A3A3A);
            draw.fillPopup(x, thumbY, x + 2, thumbY + thumbHeight, 0xFFFFFFFF);
        } else {
            draw.fill(x, top, x + 2, top + height, 0xFF3A3A3A);
            draw.fill(x, thumbY, x + 2, thumbY + thumbHeight, 0xFFFFFFFF);
        }
    }

    /** Associates one inactive control with the translated reason it cannot be used. */
    protected final void setDisabledTooltip(final ClickableWidget widget, final String translationKey) {
        if (widget == null) {
            return;
        }
        if (translationKey == null) {
            disabledTooltipKeys.remove(widget);
            return;
        }
        disabledTooltipKeys.put(widget, translationKey);
    }

    private static boolean pointerOver(final ClickableWidget widget, final int mouseX, final int mouseY) {
        return mouseX >= Widgets.x(widget) && mouseY >= Widgets.y(widget)
                && mouseX < Widgets.x(widget) + widget.getWidth()
                && mouseY < Widgets.y(widget) + widget.getHeight();
    }

    private void renderDisabledTooltip(final GuiDraw draw, final int mouseX, final int mouseY) {
        disabledTooltipKeys.entrySet().removeIf(entry -> !children().contains(entry.getKey()));
        for (final Map.Entry<ClickableWidget, String> entry : disabledTooltipKeys.entrySet()) {
            final ClickableWidget widget = entry.getKey();
            if (widget.visible && !widget.active && pointerOver(widget, mouseX, mouseY)) {
                draw.drawTooltip(
                    this,
                    this.textRenderer,
                    Texts.translatable(entry.getValue()),
                    mouseX,
                    mouseY
                );
                return;
            }
        }
    }

    /** Adds one embedded location-menu button to this screen's widget layer. */
    ButtonWidget hostLocationMenuButton(final ButtonWidget button) {
        embeddedLocationMenuButtons.add(button);
        return addDrawableChild(button);
    }

    /**
     * The embedded pane's current menu buttons, so its reveal frame can fade them alongside
     * the panel; rebuilt from empty on every embedded-menu rebuild.
     */
    List<ButtonWidget> embeddedLocationMenuButtons() {
        return embeddedLocationMenuButtons;
    }

    /**
     * Tooltip for an embedded location-menu button, shown on hover whether or not the
     * action is currently available.
     */
    void setLocationMenuTooltip(final ButtonWidget button, final String translationKey) {
        setHoverTooltip(button, translationKey);
    }

    /** Tooltip shown on hover, whether or not the control is currently usable. */
    protected final void setHoverTooltip(final ClickableWidget widget, final String translationKey) {
        if (translationKey == null) {
            locationMenuTooltipKeys.remove(widget);
        } else {
            locationMenuTooltipKeys.put(widget, translationKey);
        }
    }

    /**
     * Recreates this screen's widgets after the embedded location menu opened, closed, or
     * re-armed its delete confirmation.
     */
    void rebuildForEmbeddedLocationMenu() {
        embeddedLocationMenuButtons.clear();
        clearChildren();
        init();
    }

    private void renderLocationMenuTooltip(final GuiDraw draw, final int mouseX, final int mouseY) {
        locationMenuTooltipKeys.entrySet().removeIf(entry -> !children().contains(entry.getKey()));
        for (final Map.Entry<ClickableWidget, String> entry : locationMenuTooltipKeys.entrySet()) {
            final ClickableWidget widget = entry.getKey();
            if (widget.visible && pointerOver(widget, mouseX, mouseY)) {
                draw.drawTooltip(
                    this,
                    this.textRenderer,
                    Texts.translatable(entry.getValue()),
                    mouseX,
                    mouseY
                );
                return;
            }
        }
    }
}
