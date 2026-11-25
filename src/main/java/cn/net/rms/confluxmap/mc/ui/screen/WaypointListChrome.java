package cn.net.rms.confluxmap.mc.ui.screen;

/** Shared native list geometry. Layout options only change the rows inside this chrome. */
record WaypointListChrome(int left, int right, int headerWidth, int createWidth, int settingsX, int settingsY, int settingsWidth) {
    static WaypointListChrome create(int width, int height, boolean shared) {
        int left = Math.max(16, (width - 880) / 2), right = width - left;
        int create = shared ? 116 : Math.min(116, Math.max(60, (right - left - 88) / 2));
        int settingsX = left + (shared ? create + 4 : 2 * (create + 4));
        int settingsWidth = Math.min(80, right - 84 - settingsX);
        int settingsY = height - 24;
        if (settingsWidth < 36) { settingsWidth = 64; settingsX = right - 64; settingsY = 4; }
        return new WaypointListChrome(left, right, Math.min(280, Math.max(120, width - 32)), create, settingsX, settingsY, settingsWidth);
    }
}
