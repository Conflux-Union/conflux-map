package cn.net.rms.confluxmap.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** FNV-1a vectors recorded from the pre-consolidation {@code ChunkPatchCodec} helpers. */
class Fnv1aTest {

    private static final long BASIS = 0xcbf29ce484222325L;

    @Test
    void matchesTheRecordedVectors() {
        assertEquals(0x4d25767f9dce13f5L, Fnv1a.fnv1aInt(BASIS, 0));
        assertEquals(0x994f76653e2a3951L, Fnv1a.fnv1aInt(BASIS, -1));
        assertEquals(0x37eb3f3347761c55L, Fnv1a.fnv1aLong(BASIS, 0x0123456789ABCDEFL));
        assertEquals(0x078ea932798a6c44L, Fnv1a.fnv1aString(BASIS, "conflux"));
        assertEquals(0xaf63bd4c8601b7dfL, Fnv1a.fnv1aString(BASIS, ""));
    }

    @Test
    void normalizeRevisionMovesTheReservedMinimum() {
        assertEquals(Long.MAX_VALUE, Fnv1a.normalizeRevision(Long.MIN_VALUE));
        assertEquals(Long.MAX_VALUE - 1, Fnv1a.normalizeRevision(Long.MAX_VALUE - 1));
        assertEquals(-1L, Fnv1a.normalizeRevision(-1L));
        assertEquals(0L, Fnv1a.normalizeRevision(0L));
    }
}
