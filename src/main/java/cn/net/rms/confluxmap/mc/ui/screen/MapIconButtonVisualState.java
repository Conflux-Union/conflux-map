package cn.net.rms.confluxmap.mc.ui.screen;

record MapIconButtonVisualState(int border, int background, int iconTint) {
    private static final int ENABLED_ICON_TINT = 0xFFFFFFFF;
    private static final int DISABLED_ICON_TINT = 0xFF777777;

    static MapIconButtonVisualState of(
        final boolean active,
        final boolean selected,
        final boolean hovered
    ) {
        final boolean selectedAppearance = active && selected;
        final int border = active
            ? hovered || selectedAppearance ? 0xFFFFFFFF : 0xFF8A8A8A
            : 0xFF4A4A4A;
        final int background = selectedAppearance
            ? 0xFFFFFFFF
            : active ? 0xE0181818 : 0xD0121212;
        final int iconTint = selectedAppearance ? 0xFF101010 : active
            ? ENABLED_ICON_TINT
            : DISABLED_ICON_TINT;
        return new MapIconButtonVisualState(border, background, iconTint);
    }
}
