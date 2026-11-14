package cn.net.rms.confluxmap.core.net.image;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.net.rms.confluxmap.core.net.shared.SharedWaypointProtocolException;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WaypointImageCodecTest {
    private static final UUID IMAGE_ID = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");

    @Test
    void everyMessageTypeRoundTrips() {
        final byte[] png = new byte[64];
        for (int i = 0; i < png.length; i++) {
            png[i] = (byte) (i * 7);
        }
        final WaypointImageMessage.C2S[] c2s = {
            new ImageUploadBeginC2S(IMAGE_ID, WaypointImageProto.FORMAT_PNG, 320, 180, 2048),
            new ImageUploadChunkC2S(IMAGE_ID, 0, png),
            new ImageUploadChunkC2S(IMAGE_ID, 3, png),
            new ImageFetchC2S(IMAGE_ID),
        };
        final WaypointImageMessage.S2C[] s2c = {
            new ImageUploadResultS2C(IMAGE_ID, WaypointImageProto.STATUS_OK),
            new ImageDataS2C(IMAGE_ID, WaypointImageProto.FORMAT_PNG, png),
            new ImageMissingS2C(IMAGE_ID),
        };
        for (final WaypointImageMessage.C2S message : c2s) {
            assertC2SRoundTrips(message);
        }
        for (final WaypointImageMessage.S2C message : s2c) {
            assertS2CRoundTrips(message);
        }
    }

    @Test
    void imageBoundariesAreAccepted() {
        final byte[] fullImage = new byte[WaypointImageProto.MAX_IMAGE_BYTES];
        final byte[] fullChunk = new byte[WaypointImageProto.MAX_CHUNK_BYTES];
        assertC2SRoundTrips(new ImageUploadBeginC2S(
            IMAGE_ID, WaypointImageProto.FORMAT_PNG, 320, 180, WaypointImageProto.MAX_IMAGE_BYTES
        ));
        assertC2SRoundTrips(new ImageUploadBeginC2S(
            IMAGE_ID, WaypointImageProto.FORMAT_PNG, 1, 1, 1
        ));
        assertC2SRoundTrips(new ImageUploadChunkC2S(IMAGE_ID, 0, fullChunk));
        assertS2CRoundTrips(new ImageDataS2C(
            IMAGE_ID, WaypointImageProto.FORMAT_PNG, fullImage
        ));
    }

    @Test
    void outOfRangeFieldsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ImageUploadBeginC2S(
            IMAGE_ID, WaypointImageProto.FORMAT_PNG, 320, 180, 0
        ));
        assertThrows(IllegalArgumentException.class, () -> new ImageUploadBeginC2S(
            IMAGE_ID, WaypointImageProto.FORMAT_PNG, 320, 180, WaypointImageProto.MAX_IMAGE_BYTES + 1
        ));
        assertThrows(IllegalArgumentException.class, () -> new ImageUploadBeginC2S(
            IMAGE_ID, 2, 320, 180, 128
        ));
        assertThrows(IllegalArgumentException.class, () -> new ImageUploadBeginC2S(
            IMAGE_ID, WaypointImageProto.FORMAT_PNG, 0, 180, 128
        ));
        assertThrows(IllegalArgumentException.class, () -> new ImageUploadChunkC2S(
            IMAGE_ID, 0, new byte[WaypointImageProto.MAX_CHUNK_BYTES + 1]
        ));
        assertThrows(IllegalArgumentException.class, () -> new ImageUploadChunkC2S(
            IMAGE_ID, 0, new byte[0]
        ));
        assertThrows(IllegalArgumentException.class, () -> new ImageUploadChunkC2S(
            IMAGE_ID, -1, new byte[8]
        ));
        assertThrows(IllegalArgumentException.class, () -> new ImageDataS2C(
            IMAGE_ID, WaypointImageProto.FORMAT_PNG, new byte[WaypointImageProto.MAX_IMAGE_BYTES + 1]
        ));
        assertThrows(IllegalArgumentException.class, () -> new ImageUploadResultS2C(IMAGE_ID, 6));
        assertThrows(IllegalArgumentException.class, () -> new ImageUploadResultS2C(IMAGE_ID, -1));
    }

    @Test
    void truncatedAndOversizedPayloadsAreRejected() throws IOException, SharedWaypointProtocolException {
        final ImageDataS2C data = new ImageDataS2C(
            IMAGE_ID, WaypointImageProto.FORMAT_PNG, new byte[128]
        );
        final byte[] encoded = WaypointImageCodec.encodeS2C(data);
        assertThrows(SharedWaypointProtocolException.class,
            () -> WaypointImageCodec.decodeS2C(truncate(encoded, encoded.length - 1)));

        final ByteArrayOutputStream raw = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(raw);
        out.writeByte(WaypointImageProto.MSG_IMAGE_UPLOAD_BEGIN);
        writeUuid(out, IMAGE_ID);
        out.writeByte(WaypointImageProto.FORMAT_PNG);
        out.writeInt(320);
        out.writeInt(180);
        out.writeInt(WaypointImageProto.MAX_IMAGE_BYTES + 1);
        assertThrows(SharedWaypointProtocolException.class,
            () -> WaypointImageCodec.decodeC2S(raw.toByteArray()));
    }

    @Test
    void directionAndUnknownTypesAreRejected() throws IOException {
        final ImageFetchC2S fetch = new ImageFetchC2S(IMAGE_ID);
        assertThrows(SharedWaypointProtocolException.class,
            () -> WaypointImageCodec.decodeS2C(WaypointImageCodec.encodeC2S(fetch)));

        final ImageMissingS2C missing = new ImageMissingS2C(IMAGE_ID);
        assertThrows(SharedWaypointProtocolException.class,
            () -> WaypointImageCodec.decodeC2S(WaypointImageCodec.encodeS2C(missing)));

        assertThrows(SharedWaypointProtocolException.class,
            () -> WaypointImageCodec.decodeC2S(new byte[] {0x3F}));
        assertThrows(SharedWaypointProtocolException.class,
            () -> WaypointImageCodec.decodeC2S(new byte[0]));
    }

    @Test
    void chunkDefensivelyCopiesItsBytes() {
        final byte[] bytes = {1, 2, 3};
        final ImageUploadChunkC2S chunk = new ImageUploadChunkC2S(IMAGE_ID, 0, bytes);
        bytes[0] = 9;
        assertArrayEquals(new byte[] {1, 2, 3}, chunk.bytes());
    }

    private static void assertC2SRoundTrips(final WaypointImageMessage.C2S message) {
        try {
            assertEquals(
                message,
                WaypointImageCodec.decodeC2S(WaypointImageCodec.encodeC2S(message)),
                message.getClass().getSimpleName()
            );
        } catch (final SharedWaypointProtocolException e) {
            throw new AssertionError(e);
        }
    }

    private static void assertS2CRoundTrips(final WaypointImageMessage.S2C message) {
        try {
            assertEquals(
                message,
                WaypointImageCodec.decodeS2C(WaypointImageCodec.encodeS2C(message)),
                message.getClass().getSimpleName()
            );
        } catch (final SharedWaypointProtocolException e) {
            throw new AssertionError(e);
        }
    }

    private static byte[] truncate(final byte[] payload, final int length) {
        final byte[] out = new byte[length];
        System.arraycopy(payload, 0, out, 0, length);
        return out;
    }

    private static void writeUuid(final DataOutputStream out, final UUID value) throws IOException {
        out.writeLong(value.getMostSignificantBits());
        out.writeLong(value.getLeastSignificantBits());
    }
}
