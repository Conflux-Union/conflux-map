package cn.net.rms.confluxmap.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class HexCodecTest {
    @Test
    void emptyArrayEncodesToEmptyString() {
        assertEquals("", HexCodec.format(new byte[0]));
    }

    @Test
    void everyBytePatternEncodesLowercaseHex() {
        assertEquals(
            "00010f7f80fecdff",
            HexCodec.format(new byte[] {0x00, 0x01, 0x0f, 0x7f, (byte) 0x80, (byte) 0xfe, (byte) 0xcd, (byte) 0xff})
        );
    }

    @Test
    void outputLengthDoublesTheInput() {
        assertEquals(64, HexCodec.format(new byte[32]).length());
    }
}
