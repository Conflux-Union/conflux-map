package cn.net.rms.confluxmap.mc.input;

final class MaliLibHotkeyLayout {
    static final int CONFIG_WIDTH = 144;
    private static final int TOP_MARGIN = 28;
    private static final int BOTTOM_MARGIN = 24;

    private MaliLibHotkeyLayout() {
    }

    static int listY(final int baseY) {
        return baseY + TOP_MARGIN;
    }

    static int browserHeight(final int screenHeight) {
        return Math.max(0, screenHeight - TOP_MARGIN - BOTTOM_MARGIN);
    }
}
