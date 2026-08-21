package cn.net.rms.confluxmap.mc.ui.hud;

/** The map-content rectangle inside the minimap's outer frame bounds. */
record MinimapContentViewport(int x, int y, int size) {
    static MinimapContentViewport resolve(
        final int outerX,
        final int outerY,
        final int outerSize,
        final int requestedInset
    ) {
        final int inset = Math.max(0, Math.min(requestedInset, (outerSize - 1) / 2));
        return new MinimapContentViewport(
            outerX + inset,
            outerY + inset,
            outerSize - inset * 2
        );
    }

    float centerX() {
        return x + size / 2f;
    }

    float centerY() {
        return y + size / 2f;
    }
}
