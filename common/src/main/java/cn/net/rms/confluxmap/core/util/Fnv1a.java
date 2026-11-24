package cn.net.rms.confluxmap.core.util;

import java.nio.charset.StandardCharsets;

/**
 * FNV-1a 64-bit fingerprint helpers shared by the correction codecs and the
 * patch builder. Hash values cross the protocol wire, so both producers go
 * through this one implementation to stay in lockstep.
 */
public final class Fnv1a {
    private Fnv1a() {
    }

    public static long fnv1a(final long hash, final int value) {
        return (hash ^ (value & 0xFFL)) * 0x100000001b3L;
    }

    public static long fnv1aInt(long hash, final int value) {
        for (int shift = 0; shift < Integer.SIZE; shift += Byte.SIZE) {
            hash = fnv1a(hash, value >>> shift);
        }
        return hash;
    }

    public static long fnv1aLong(long hash, final long value) {
        for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
            hash = fnv1a(hash, (int) (value >>> shift));
        }
        return hash;
    }

    public static long fnv1aString(long hash, final String value) {
        for (final byte b : value.getBytes(StandardCharsets.UTF_8)) {
            hash = fnv1a(hash, b);
        }
        return fnv1a(hash, 0);
    }

    /** Reserves {@link Long#MIN_VALUE} as the "revision unknown" sentinel. */
    public static long normalizeRevision(final long hash) {
        return hash == Long.MIN_VALUE ? Long.MAX_VALUE : hash;
    }
}
