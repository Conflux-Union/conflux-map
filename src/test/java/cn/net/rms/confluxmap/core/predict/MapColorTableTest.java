package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class MapColorTableTest {
    @Test
    void matchesVanilla1171MapColorsAcrossTheRegistry() {
        assertEquals(62, MapColorTable.size());
        assertEquals(0x00000000, MapColorTable.argb(0));
        assertEquals(0xFFFFFFFF, MapColorTable.argb(8));
        assertEquals(0xFF976D4D, MapColorTable.argb(10));
        assertEquals(0xFF5CDBD5, MapColorTable.argb(31));
        assertEquals(0xFFD1B1A1, MapColorTable.argb(36));
        assertEquals(0xFF7FA796, MapColorTable.argb(61));
    }
}
