package cn.net.rms.confluxmap.core.util;

/** Lowercase hex encoding - {@code java.util.HexFormat} is JDK 17+, this mod compiles at Java 16. */
public final class HexCodec {
    private static final char[] DIGITS = "0123456789abcdef".toCharArray();

    public static String format(final byte[] bytes) {
        final char[] hex = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            final int value = bytes[i] & 0xFF;
            hex[i * 2] = DIGITS[value >>> 4];
            hex[i * 2 + 1] = DIGITS[value & 0x0F];
        }
        return new String(hex);
    }

    private HexCodec() {
    }
}
