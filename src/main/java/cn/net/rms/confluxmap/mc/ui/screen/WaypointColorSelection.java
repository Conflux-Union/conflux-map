package cn.net.rms.confluxmap.mc.ui.screen;

/** Keeps the active waypoint color separate from the last custom color. */
final class WaypointColorSelection {
    private int selected;
    private int custom;

    WaypointColorSelection(final int initialColor) {
        selected = initialColor;
        custom = initialColor;
    }

    int selected() {
        return selected;
    }

    int custom() {
        return custom;
    }

    void selectPreset(final int color) {
        selected = color;
    }

    void selectCustom() {
        selected = custom;
    }

    void updateCustom(final int color) {
        custom = color;
        selected = color;
    }
}
