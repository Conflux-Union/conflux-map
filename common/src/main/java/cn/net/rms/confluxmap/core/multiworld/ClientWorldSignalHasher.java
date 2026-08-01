package cn.net.rms.confluxmap.core.multiworld;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Comparator;

/** One-way canonicalization for server fingerprints; raw server metadata is never persisted. */
public final class ClientWorldSignalHasher {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private ClientWorldSignalHasher() {
    }

    public static String hash(final String value) {
        return digest(value == null ? "" : value);
    }

    public static String hashSorted(final Collection<String> values) {
        final String canonical = values.stream()
            .filter(value -> value != null && !value.isBlank())
            .sorted(Comparator.naturalOrder())
            .reduce(new StringBuilder(), (builder, value) -> builder
                .append(value.length()).append(':').append(value).append(';'), StringBuilder::append)
            .toString();
        return digest(canonical);
    }

    private static String digest(final String value) {
        final byte[] bytes;
        try {
            bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", e);
        }
        final char[] encoded = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            final int current = bytes[i] & 0xFF;
            encoded[i * 2] = HEX[current >>> 4];
            encoded[i * 2 + 1] = HEX[current & 0x0F];
        }
        return new String(encoded);
    }
}
