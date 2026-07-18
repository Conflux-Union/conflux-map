package cn.net.rms.confluxmap.core.predict;

/** Small client-owned map-colour table used when a server correction carries a vanilla colour id. */
public final class MapColorTable {
    private static final int[] COLORS = {
        0x00000000, 0xFF7FB238, 0xFFF7E9A3, 0xFFC7C7C7, 0xFFFF0000, 0xFFA000A0, 0xFF707070, 0xFF007C00,
        0xFF4040FF, 0xFFA0A0A0, 0xFF2E2E2E, 0xFF707070, 0xFF4040FF, 0xFF8F7748, 0xFF000000, 0xFFD87F33,
        0xFFB24CD8, 0xFF6699D8, 0xFFE5E533, 0xFF7FCC19, 0xFFFFAFAF, 0xFF4C4C4C, 0xFF999999, 0xFF4C4C4C,
        0xFF999999, 0xFF4C4C4C, 0xFF999999, 0xFF4C4C4C, 0xFF999999, 0xFF4C4C4C, 0xFF999999, 0xFF4C4C4C,
        0xFF999999, 0xFF4C4C4C, 0xFF999999, 0xFF4C4C4C, 0xFF999999, 0xFF4C4C4C, 0xFF999999, 0xFF4C4C4C,
        0xFF999999, 0xFF4C4C4C, 0xFF999999, 0xFF4C4C4C, 0xFF999999, 0xFF4C4C4C, 0xFF999999, 0xFF4C4C4C,
        0xFF999999, 0xFF4C4C4C, 0xFF999999, 0xFF4C4C4C, 0xFF999999, 0xFF4C4C4C, 0xFF999999, 0xFF4C4C4C,
        0xFF999999, 0xFF4C4C4C, 0xFF999999, 0xFF4C4C4C, 0xFF999999, 0xFF4C4C4C
    };

    private MapColorTable() {
    }

    public static int argb(final int id) {
        return id >= 0 && id < COLORS.length ? COLORS[id] : 0xFF969696;
    }

    public static int size() {
        return COLORS.length;
    }
}
