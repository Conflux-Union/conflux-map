package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.mc.render.GuiShapes;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import cn.net.rms.confluxmap.mc.ui.widget.ConfluxTextButton;

/**
 * Settings-row button carrying the row's own fade alpha, so the screen's open stagger can
 * drive rows independently of the frame-wide reveal. The vanilla-style body consumes the
 * widget alpha ({@code setAlpha} is public on every supported version and its render always
 * reads the field), while the self-drawn body routes through the shape layer's frame-wide
 * reveal alpha — so while the stagger drives the row, that reveal alpha is scoped to the
 * row's slice around its own draws. Scoping stays on for saturated slices too: an absolute
 * {@code beginReveal(1)} holds the row at full brightness, where an unscoped draw would dip
 * to the still-eased screen value mid-stagger. {@link #settleRowAlpha()} is the explicit
 * hand-back to the inherited path once the stagger settles.
 */
final class RowButton extends ConfluxTextButton {
    /** This row's fade alpha while the stagger drives it. */
    private float rowAlpha = 1.0f;
    /** True from the first {@link #setRowAlpha} until {@link #settleRowAlpha()} hands back. */
    private boolean scoped;

    RowButton(
        final int x,
        final int y,
        final int width,
        final int height,
        final net.minecraft.text.Text message,
        final PressAction onPress
    ) {
        super(x, y, width, height, message, onPress);
    }

    /**
     * Drives the row's fade on both render channels. Stays scoped even at exactly 1.0: a
     * saturated slice must hold full brightness, not fall back to the frame-wide eased
     * screen value that is still below 1 at that point in the reveal.
     */
    void setRowAlpha(final float alpha) {
        rowAlpha = alpha;
        scoped = true;
        setAlpha(alpha);
    }

    /** The row's current fade alpha; 1.0 once handed back, so drawn labels can follow it. */
    float rowAlpha() {
        return rowAlpha;
    }

    /**
     * Hands the row back to the screen's own fades — the uniform widget alpha and the
     * frame-wide reveal — once the stagger no longer drives it, so close fades and every
     * later uniform fade reach the row like any other widget.
     */
    void settleRowAlpha() {
        rowAlpha = 1.0f;
        scoped = false;
        setAlpha(1.0f);
    }

    @Override
    protected void drawContents(final GuiDraw draw) {
        if (!scoped) {
            super.drawContents(draw);
            return;
        }
        // The self-drawn body would otherwise fade with the screen-wide reveal; scope it
        // to this row's slice so the stagger does not wait on the whole screen.
        final float screenReveal = GuiShapes.revealAlpha();
        GuiDraw.beginReveal(rowAlpha);
        try {
            super.drawContents(draw);
        } finally {
            GuiDraw.beginReveal(screenReveal);
        }
    }

    @Override
    protected void drawForeground(final GuiDraw draw) {
        if (!scoped) {
            super.drawForeground(draw);
            return;
        }
        // The vanilla-style path draws its label through the shape layer beside the
        // alpha-driven sprite body; folding the row alpha over the reveal keeps label and
        // body on one schedule. Nested with the drawContents scope this rewrites the same
        // value and restores it exactly.
        final float screenReveal = GuiShapes.revealAlpha();
        GuiDraw.beginReveal(rowAlpha);
        try {
            super.drawForeground(draw);
        } finally {
            GuiDraw.beginReveal(screenReveal);
        }
    }
}
