package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.net.PatchCodec;
import cn.net.rms.confluxmap.core.net.CorrectionProfile;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.net.ProtoException;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

class PredictionTileCodecTest {
    @Test
    void negotiatedCorrectionProfileRoundTrips() throws Exception {
        final PredictionTileCodec.FileData data = new PredictionTileCodec.FileData(
            0, 1, 2, 3L, 4L,
            new byte[Proto.PATCH_PRESENCE_BYTES], new PatchCodec.Patch(List.of()),
            new byte[0], new long[0], new long[0],
            Proto.PATCH_MODE_RESIDUAL, "baseline", CorrectionProfile.LEGACY_V1
        );

        assertEquals(
            CorrectionProfile.LEGACY_V1,
            PredictionTileCodec.decode(PredictionTileCodec.encode(data)).correctionProfile()
        );
    }
    @Test
    void chunkMetadataAndSourceProfileRoundTripInVersionSeventeen() throws Exception {
        final int chunks = (16 << 2) * (16 << 2);
        final byte[] generated = new byte[(chunks + 7) / 8];
        generated[0] = 1;
        final long[] revisions = new long[chunks];
        java.util.Arrays.fill(revisions, Long.MIN_VALUE);
        revisions[0] = 91L;
        final long[] validated = new long[chunks];
        validated[0] = 1_700_000_123_456L;
        final PredictionTileCodec.FileData data = new PredictionTileCodec.FileData(
            2, -1, -1, 10L, 1_700_000_123_456L,
            new byte[Proto.PATCH_PRESENCE_BYTES], new PatchCodec.Patch(List.of()),
            generated, revisions, validated,
            Proto.PATCH_MODE_RESIDUAL,
            "cb:9afc1038ea5a|shim:9|base:14"
        );
        final byte[] encoded = PredictionTileCodec.encode(data);
        final PredictionTileCodec.FileData decoded = PredictionTileCodec.decode(encoded);
        assertEquals(data.lod(), decoded.lod());
        assertEquals(data.tileX(), decoded.tileX());
        assertEquals(data.tileZ(), decoded.tileZ());
        assertEquals(data.revision(), decoded.revision());
        assertEquals(data.validatedAtMillis(), decoded.validatedAtMillis());
        assertArrayEquals(data.presence(), decoded.presence());
        assertEquals(0, decoded.patch().size());
        assertTrue(decoded.hasChunkMetadata());
        assertArrayEquals(generated, decoded.generatedChunks());
        assertArrayEquals(revisions, decoded.chunkRevisions());
        assertArrayEquals(validated, decoded.chunkValidatedAtMillis());
        assertEquals(Proto.PATCH_MODE_RESIDUAL, decoded.patchMode());
        assertEquals("cb:9afc1038ea5a|shim:9|base:14", decoded.baselineProfile());
    }

    @Test
    void rejectsMalformedUtf8SourceProfile() {
        final PredictionTileCodec.FileData data = new PredictionTileCodec.FileData(
            0,
            0,
            0,
            1L,
            1_000L,
            new byte[Proto.PATCH_PRESENCE_BYTES],
            new PatchCodec.Patch(List.of()),
            new byte[0],
            new long[0],
            new long[0],
            Proto.PATCH_MODE_RESIDUAL,
            "x"
        );
        final byte[] encoded = PredictionTileCodec.encode(data);
        encoded[33] = (byte) 0xC3;

        assertThrows(ProtoException.class, () -> PredictionTileCodec.decode(encoded));
    }

    @Test
    void versionFifteenRemainsDrawableButHasNoTrustedChunkMetadata() throws Exception {
        final byte[] encoded = encodeVersionFifteen(new PredictionTileCodec.FileData(
            2, -1, -1, 10L, 1_700_000_123_456L,
            new byte[Proto.PATCH_PRESENCE_BYTES], new PatchCodec.Patch(List.of())
        ));

        final PredictionTileCodec.FileData decoded = PredictionTileCodec.decode(encoded);

        assertEquals(10L, decoded.revision());
        assertEquals(1_700_000_123_456L, decoded.validatedAtMillis());
        assertEquals(0, decoded.patch().size());
        assertTrue(!decoded.hasChunkMetadata());
    }

    @Test
    void unknownCorrectionVersionsAreRejected() throws Exception {
        final PredictionTileCodec.FileData data = new PredictionTileCodec.FileData(
            0, 0, 0, 0L, 0L,
            new byte[Proto.PATCH_PRESENCE_BYTES], new PatchCodec.Patch(List.of())
        );
        final byte[] encoded = PredictionTileCodec.encode(data);
        encoded[4] = (byte) (PredictionTileCodec.FORMAT_VERSION + 1);

        assertThrows(ProtoException.class, () -> PredictionTileCodec.decode(encoded));
    }

    @Test
    void outOfRangeLodIsRejectedBeforeChunkMetadataAllocation() throws Exception {
        final PredictionTileCodec.FileData data = new PredictionTileCodec.FileData(
            0, 0, 0, 0L, 0L,
            new byte[Proto.PATCH_PRESENCE_BYTES], new PatchCodec.Patch(List.of())
        );
        final byte[] encoded = PredictionTileCodec.encode(data);
        encoded[5] = 7;

        assertThrows(ProtoException.class, () -> PredictionTileCodec.decode(encoded));
    }

    private static byte[] encodeVersionFifteen(
        final PredictionTileCodec.FileData data
    ) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(bytes);
        out.write(PredictionTileCodec.MAGIC);
        out.writeByte(15);
        out.writeByte(data.lod());
        out.writeInt(data.tileX());
        out.writeInt(data.tileZ());
        out.writeLong(data.revision());
        out.writeLong(data.validatedAtMillis());
        out.write(data.presence());
        final byte[] body = PatchCodec.encode(data.patch());
        out.writeInt(body.length);
        out.write(body);
        out.flush();
        return bytes.toByteArray();
    }
}
