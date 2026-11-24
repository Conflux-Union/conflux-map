package cn.net.rms.confluxmap.core.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit coverage for the shared patch wire primitives both codecs delegate to. */
class PatchWireTest {
    @Test
    void zigzagVarintRoundTripsSmallLargeAndNegativeDeltas() throws Exception {
        final int[] values = {0, 1, -1, 63, -64, 64, -65, 170, -10,
            Short.MAX_VALUE, Short.MIN_VALUE, 1_048_575, -1_048_576};
        for (final int value : values) {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            PatchWire.writeZigzagVarint(new DataOutputStream(bytes), value);
            assertEquals(value, PatchWire.readZigzagVarint(input(bytes.toByteArray())), "value " + value);
        }
    }

    @Test
    void zigzagVarLongRoundTripsExtremeValues() throws Exception {
        final long[] values = {0L, 1L, -1L, 9_876_543_210L, -9_876_543_210L,
            Long.MAX_VALUE, Long.MIN_VALUE};
        for (final long value : values) {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            PatchWire.writeZigzagVarLong(new DataOutputStream(bytes), value);
            assertEquals(value, PatchWire.readZigzagVarLong(input(bytes.toByteArray())), "value " + value);
        }
    }

    @Test
    void overlongHeightDeltaAndRevisionDeltaAreRejected() {
        assertThrows(ProtoException.class, () -> PatchWire.readZigzagVarint(
            input(new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0})));
        assertThrows(ProtoException.class, () -> PatchWire.readZigzagVarLong(
            input(new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80,
                (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0})));
    }

    @Test
    void unsignedVarintByteCountsMatchTheWireEncoding() throws Exception {
        for (final int[] pair : new int[][] {{0, 1}, {127, 1}, {128, 2}, {16_383, 2}, {16_384, 3}}) {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            PatchWire.writeUnsignedVarint(new DataOutputStream(bytes), pair[0]);
            assertEquals(pair[1], bytes.size(), "length for " + pair[0]);
            assertEquals(pair[1], PatchWire.unsignedVarintBytes(pair[0]), "count for " + pair[0]);
            assertEquals(pair[0], PatchWire.readUnsignedVarint(input(bytes.toByteArray()), 5));
        }
    }

    @Test
    void unsignedVarintBeyondTheByteBudgetIsRejected() {
        assertThrows(ProtoException.class, () -> PatchWire.readUnsignedVarint(
            input(new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80, 0}), 3));
    }

    @Test
    void setBitAndHasBitAgree() {
        final byte[] bits = new byte[2];
        PatchWire.setBit(bits, 0);
        PatchWire.setBit(bits, 8);
        PatchWire.setBit(bits, 15);
        assertTrue(PatchWire.hasBit(bits, 0));
        assertTrue(PatchWire.hasBit(bits, 8));
        assertTrue(PatchWire.hasBit(bits, 15));
        assertTrue(!PatchWire.hasBit(bits, 1) && !PatchWire.hasBit(bits, 7) && !PatchWire.hasBit(bits, 14));
    }

    @Test
    void setBitRejectsIndexesOutsideTheMask() {
        final byte[] bits = new byte[1];
        assertThrows(IndexOutOfBoundsException.class, () -> PatchWire.setBit(bits, -1));
        assertThrows(IndexOutOfBoundsException.class, () -> PatchWire.setBit(bits, 8));
        assertThrows(IndexOutOfBoundsException.class, () -> PatchWire.setBit(null, 0));
    }

    @Test
    void unsignedBytePlaneReadsExactlyCountValues() throws Exception {
        final DataInputStream in = input(new byte[] {4, 5, 6, 7});
        assertArrayEquals(new int[] {4, 5}, PatchWire.readUnsignedBytePlane(in, 2));
        assertEquals(6, in.readUnsignedByte(), "plane must not over-read");
    }

    @Test
    void materialTableStartsWithTheEmptyFallback() {
        final var table = PatchWire.materialTable(List.of(
            new PatchCodec.Sample(0, 1, 64, 1, 1, 0, 0,
                "minecraft:stone", "", "minecraft:glass")
        ));
        assertEquals(0, table.get(""));
        assertEquals(1, table.get("minecraft:stone"));
        assertEquals(2, table.get("minecraft:glass"));
    }

    @Test
    void materialIdsPastTheUtf8CapAreRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> PatchWire.writeMaterial(output(), "a".repeat(PatchCodec.MAX_MATERIAL_BYTES + 1)));
    }

    @Test
    void emptyMaterialTableAndIndexesOutsideItAreRejected() throws Exception {
        final ByteArrayOutputStream emptyTable = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(emptyTable);
        out.writeShort(0);
        assertThrows(ProtoException.class,
            () -> PatchWire.readMaterialTable(input(emptyTable.toByteArray())));

        final ByteArrayOutputStream outsideTable = new ByteArrayOutputStream();
        final DataOutputStream tableOut = new DataOutputStream(outsideTable);
        tableOut.writeShort(1);
        PatchWire.writeMaterial(tableOut, "");
        tableOut.writeShort(5);
        assertThrows(ProtoException.class, () -> PatchWire.readMaterialPlane(
            input(outsideTable.toByteArray()), new String[] {""}, new String[1]));
    }

    @Test
    void materialTablePastTheCapIsRejected() {
        final List<PatchCodec.Sample> samples = new java.util.ArrayList<>();
        int next = 0;
        for (int i = 0; i < (PatchCodec.MAX_MATERIALS + 2) / 3 + 1; i++) {
            samples.add(new PatchCodec.Sample(0, 1, 64, 1, 1, 0, 0,
                "block:" + next++, "block:" + next++, "block:" + next++));
        }
        assertThrows(IllegalArgumentException.class, () -> PatchWire.materialTable(samples));
    }

    @Test
    void deflateAndInflateRoundTrip() throws Exception {
        final byte[] raw = new byte[64 * 1024];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) (i * 31);
        }
        final byte[] body = PatchWire.deflate(raw, ChunkPatchCodec.MAX_COMPRESSED_BYTES);
        assertArrayEquals(raw, PatchWire.inflate(body, ChunkPatchCodec.MAX_RAW_BYTES));
    }

    @Test
    void capsAreEnforcedByTheWireLayer() throws Exception {
        final byte[] raw = new byte[64 * 1024];
        assertThrows(IllegalArgumentException.class, () -> PatchWire.deflate(raw, 16));
        final byte[] body = PatchWire.deflate(raw, ChunkPatchCodec.MAX_COMPRESSED_BYTES);
        assertThrows(ProtoException.class, () -> PatchWire.inflate(body, 16));
        assertThrows(ProtoException.class, () -> PatchWire.inflate(new byte[] {1, 2, 3}, 64));
    }

    private static DataInputStream input(final byte[] bytes) {
        return new DataInputStream(new ByteArrayInputStream(bytes));
    }

    private static DataOutputStream output() {
        return new DataOutputStream(new ByteArrayOutputStream());
    }
}
