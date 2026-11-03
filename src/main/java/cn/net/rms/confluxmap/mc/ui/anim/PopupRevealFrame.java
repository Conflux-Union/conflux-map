package cn.net.rms.confluxmap.mc.ui.anim;

import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import java.util.List;
import net.minecraft.client.gui.widget.ClickableWidget;

/**
 * One popup draw frame: installs the reveal alpha plus an anchor-relative slide around the
 * popup's draw calls, and fades the popup's own widgets alongside. Auto-closeable so the
 * usual try-with-resources keeps the state paired; {@code null} when nothing should apply,
 * so the settled path keeps its exact pre-animation draw sequence.
 */
public final class PopupRevealFrame implements AutoCloseable {
    /** Anchor-relative slide distance shared by every popup reveal. */
    public static final float ANCHOR_SLIDE_PIXELS = 3.0f;

    private final GuiDraw draw;
    private final boolean installsAlpha;
    private final List<? extends ClickableWidget> widgets;

    private PopupRevealFrame(
        final GuiDraw draw,
        final boolean installsAlpha,
        final List<? extends ClickableWidget> widgets
    ) {
        this.draw = draw;
        this.installsAlpha = installsAlpha;
        this.widgets = widgets;
    }

    /**
     * Fades and slides one popup: the reveal alpha, the slide, and the popup's widgets all
     * follow {@code eased}.
     */
    public static PopupRevealFrame fadeAndSlide(
        final GuiDraw draw,
        final boolean active,
        final float eased,
        final float slideX,
        final float slideY,
        final List<? extends ClickableWidget> widgets
    ) {
        return open(draw, active, eased, slideX, slideY, true, widgets);
    }

    /**
     * Slides without installing a reveal alpha, for surfaces whose fade already comes from
     * an enclosing screen reveal (installing a second alpha would clobber it).
     */
    public static PopupRevealFrame slideOnly(
        final GuiDraw draw,
        final boolean active,
        final float eased,
        final float slideX,
        final float slideY
    ) {
        return open(draw, active, eased, slideX, slideY, false, List.of());
    }

    private static PopupRevealFrame open(
        final GuiDraw draw,
        final boolean active,
        final float eased,
        final float slideX,
        final float slideY,
        final boolean installsAlpha,
        final List<? extends ClickableWidget> widgets
    ) {
        if (!active) {
            return null;
        }
        if (installsAlpha) {
            GuiDraw.beginReveal(eased);
        }
        draw.pushTransform();
        draw.translate((eased - 1.0f) * slideX, (eased - 1.0f) * slideY);
        for (final ClickableWidget widget : widgets) {
            widget.setAlpha(eased);
        }
        return new PopupRevealFrame(draw, installsAlpha, widgets);
    }

    @Override
    public void close() {
        for (final ClickableWidget widget : widgets) {
            widget.setAlpha(1.0f);
        }
        draw.popTransform();
        if (installsAlpha) {
            GuiDraw.endReveal();
        }
    }
}
