package cn.net.rms.confluxmap.core.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.List;
import java.util.zip.DeflaterOutputStream;
import org.junit.jupiter.api.Test;

class ChunkPatchCodecTest {
    @Test
    void singleLodFourChunkHasABoundedCompactBody() throws Exception {
        final ChunkPatchCodec.Patch patch = new ChunkPatchCodec.Patch(
            1, 1, 1, new byte[] {1}, new byte[] {1},
            List.of(new PatchCodec.Sample(0, 1, 72, 1, 7, 0, 255))
        );

        final byte[] encoded = ChunkPatchCodec.encode(patch);

        assertTrue(encoded.length <= 64, "one visible LOD4 chunk must stay far below a tile payload");
        assertEquals(patch.sampleAt(0), ChunkPatchCodec.decode(encoded).sampleAt(0));
    }

    @Test
    void lodFourRegionCropRoundTripsOneSamplePerChunk() throws Exception {
        final byte[] generated = new byte[ChunkPatchCodec.maskBytes(6)];
        final byte[] evaluated = new byte[ChunkPatchCodec.maskBytes(6)];
        for (int chunk = 0; chunk < 6; chunk++) {
            ChunkPatchCodec.setBit(generated, chunk);
            ChunkPatchCodec.setBit(evaluated, chunk);
        }
        final ChunkPatchCodec.Patch original = new ChunkPatchCodec.Patch(
            3, 2, 1, generated, evaluated, List.of(
                new PatchCodec.Sample(0, 1, 72, 1, 7, 0, 255),
                new PatchCodec.Sample(5, 4, 91, 2, 11, 3, 10)
            )
        );

        final ChunkPatchCodec.Patch decoded = ChunkPatchCodec.decode(
            ChunkPatchCodec.encode(original)
        );

        assertEquals(3, decoded.chunkWidth());
        assertEquals(2, decoded.chunkHeight());
        assertEquals(3, decoded.sampleWidth());
        assertTrue(decoded.generatedAt(4));
        assertTrue(decoded.evaluatedAt(3));
        assertEquals(original.sampleAt(5), decoded.sampleAt(5));
    }

    @Test
    void lodThreeCropPreservesFourSamplesPerChunk() throws Exception {
        final byte[] generated = new byte[ChunkPatchCodec.maskBytes(2)];
        final byte[] evaluated = new byte[ChunkPatchCodec.maskBytes(8)];
        ChunkPatchCodec.setBit(generated, 0);
        for (int sample = 0; sample < 4; sample++) {
            ChunkPatchCodec.setBit(evaluated, sample % 2 + (sample / 2) * 4);
        }
        final ChunkPatchCodec.Patch original = new ChunkPatchCodec.Patch(
            2, 1, 2, generated, evaluated, List.of(
                new PatchCodec.Sample(0, 2, 80, 1, 1, 0),
                new PatchCodec.Sample(5, 2, 84, 1, 1, 0)
            )
        );

        final ChunkPatchCodec.Patch decoded = ChunkPatchCodec.decode(
            ChunkPatchCodec.encode(original)
        );

        assertEquals(4, decoded.sampleWidth());
        assertEquals(2, decoded.sampleHeight());
        assertTrue(decoded.generatedAt(0));
        assertFalse(decoded.generatedAt(1));
        assertTrue(decoded.evaluatedAt(0));
        assertTrue(decoded.evaluatedAt(5));
        assertEquals(84, decoded.sampleAt(5).surfaceY());
    }

    @Test
    void evaluatedBaselinePixelsNeedNoDifferenceRecord() throws Exception {
        final byte[] generated = {(byte) 1};
        final byte[] evaluated = {(byte) 1};
        final ChunkPatchCodec.Patch decoded = ChunkPatchCodec.decode(
            ChunkPatchCodec.encode(new ChunkPatchCodec.Patch(
                1, 1, 1, generated, evaluated, List.of()
            ))
        );

        assertTrue(decoded.generatedAt(0));
        assertTrue(decoded.evaluatedAt(0));
        assertEquals(null, decoded.sampleAt(0));
    }

    @Test
    void enhancedRegionCarriesChunkRevisionAndPixelBlockLight() throws Exception {
        final ChunkPatchCodec.Patch decoded = ChunkPatchCodec.decode(
            ChunkPatchCodec.encode(new ChunkPatchCodec.Patch(
                1, 1, 1, new byte[] {1}, new byte[] {1}, List.of(),
                new long[] {9_876L}, new byte[] {14}
            ))
        );

        assertEquals(9_876L, decoded.sourceRevisionAt(0));
        assertEquals(14, decoded.blockLightAt(0));
    }

    @Test
    void materialRegionRoundTripsRegistryIds() throws Exception {
        final PatchCodec.Sample sample = new PatchCodec.Sample(
            0, 1, 72, 1, 18, 0, 255, "minecraft:glowstone", ""
        );
        final ChunkPatchCodec.Patch patch = new ChunkPatchCodec.Patch(
            1, 1, 1, new byte[] {1}, new byte[] {1}, List.of(sample)
        );

        final ChunkPatchCodec.Patch decoded = ChunkPatchCodec.decode(
            ChunkPatchCodec.encode(patch)
        );

        assertEquals("minecraft:glowstone", decoded.sampleAt(0).materialId());
    }

    @Test
    void legacyRegionMarksSourceRevisionUnknown() throws Exception {
        final ChunkPatchCodec.Patch decoded = ChunkPatchCodec.decode(
            ChunkPatchCodec.encodeLegacy(new ChunkPatchCodec.Patch(
                1, 1, 1, new byte[] {1}, new byte[] {1}, List.of()
            ))
        );

        assertEquals(Long.MIN_VALUE, decoded.sourceRevisionAt(0));
        assertEquals(0, decoded.blockLightAt(0));
    }

    @Test
    void chunkFingerprintChangesWhenOnlySourceRevisionChanges() {
        final ChunkPatchCodec.Patch older = new ChunkPatchCodec.Patch(
            1, 1, 1, new byte[] {1}, new byte[] {1}, List.of(),
            new long[] {100L}, new byte[] {7}
        );
        final ChunkPatchCodec.Patch newer = new ChunkPatchCodec.Patch(
            1, 1, 1, new byte[] {1}, new byte[] {1}, List.of(),
            new long[] {101L}, new byte[] {7}
        );

        assertNotEquals(
            ChunkPatchCodec.chunkRevisions(older)[0],
            ChunkPatchCodec.chunkRevisions(newer)[0]
        );
    }

    @Test
    void malformedCompressedBodyIsRejected() {
        assertThrows(ProtoException.class, () -> ChunkPatchCodec.decode(new byte[] {1, 2, 3}));
    }

    @Test
    void overlongSparseMaskVarintIsRejectedAsAProtocolError() throws Exception {
        final ByteArrayOutputStream rawBytes = new ByteArrayOutputStream();
        final DataOutputStream raw = new DataOutputStream(rawBytes);
        raw.writeByte(ChunkPatchCodec.FORMAT_VERSION);
        raw.writeByte(1);
        raw.writeByte(1);
        raw.writeByte(1);
        raw.writeByte(1); // sparse-run mask
        raw.write(new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80, 0});
        raw.flush();
        final ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(compressed)) {
            deflater.write(rawBytes.toByteArray());
        }

        assertThrows(ProtoException.class, () -> ChunkPatchCodec.decode(compressed.toByteArray()));
    }
}
