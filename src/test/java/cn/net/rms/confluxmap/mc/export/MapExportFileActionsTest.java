package cn.net.rms.confluxmap.mc.export;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

final class MapExportFileActionsTest {
    @Test
    void windowsDibUsesTopDownBgraPixels() {
        final BufferedImage image = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0x80402010);
        image.setRGB(1, 0, 0xFF112233);

        final byte[] dib = MapExportFileActions.toWindowsDib(image);
        final ByteBuffer header = ByteBuffer.wrap(dib).order(ByteOrder.LITTLE_ENDIAN);

        assertEquals(48, dib.length);
        assertEquals(40, header.getInt(0));
        assertEquals(2, header.getInt(4));
        assertEquals(-1, header.getInt(8));
        assertEquals(1, header.getShort(12));
        assertEquals(32, header.getShort(14));
        assertEquals(8, header.getInt(20));
        assertEquals(0x10, Byte.toUnsignedInt(dib[40]));
        assertEquals(0x20, Byte.toUnsignedInt(dib[41]));
        assertEquals(0x40, Byte.toUnsignedInt(dib[42]));
        assertEquals(0x80, Byte.toUnsignedInt(dib[43]));
        assertEquals(0x33, Byte.toUnsignedInt(dib[44]));
        assertEquals(0x22, Byte.toUnsignedInt(dib[45]));
        assertEquals(0x11, Byte.toUnsignedInt(dib[46]));
        assertEquals(0xFF, Byte.toUnsignedInt(dib[47]));
    }
}
