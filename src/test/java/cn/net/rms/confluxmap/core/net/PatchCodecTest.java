package cn.net.rms.confluxmap.core.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import org.junit.jupiter.api.Test;

class PatchCodecTest {
    @Test
    void roundTripUsesCoordinatesNotIterationOrder() throws Exception {
        final PatchCodec.Patch patch = new PatchCodec.Patch(List.of(
            new PatchCodec.Sample(15 * 256 + 240, 4, 71, 2, 6, 8),
            new PatchCodec.Sample(0, 1, 64, 1, 1, 0),
            new PatchCodec.Sample(16 * 256 + 16, 2, 65, 2, 255, 2)
        ));
        final PatchCodec.Patch decoded = PatchCodec.decode(PatchCodec.encode(patch));
        assertEquals(patch.samples().size(), decoded.samples().size());
        assertEquals(patch.samples().get(0), decoded.sampleAt(patch.samples().get(0).pixelIndex()));
        assertEquals(patch.samples().get(1), decoded.sampleAt(patch.samples().get(1).pixelIndex()));
        assertEquals(patch.samples().get(2), decoded.sampleAt(patch.samples().get(2).pixelIndex()));
    }

    @Test
    void removalsAndExtremeHeightsRoundTrip() throws Exception {
        final PatchCodec.Patch patch = new PatchCodec.Patch(List.of(
            PatchCodec.removal(77),
            new PatchCodec.Sample(78, 3, Short.MAX_VALUE, 1, 9, 0),
            new PatchCodec.Sample(79, 3, Short.MIN_VALUE, 1, 9, 0),
            new PatchCodec.Sample(255 * 256 + 255, 250, -60, 4, 60, 15)
        ));
        final PatchCodec.Patch decoded = PatchCodec.decode(PatchCodec.encode(patch));
        assertEquals(patch.samples().size(), decoded.samples().size());
        for (final PatchCodec.Sample sample : patch.samples()) {
            assertEquals(sample, decoded.sampleAt(sample.pixelIndex()));
        }
        assertTrue(PatchCodec.isRemoval(decoded.sampleAt(77)));
    }

    @Test
    void fullTileAbsolutePatchStaysUnderCompressedCap() throws Exception {
        final List<PatchCodec.Sample> samples = new ArrayList<>(PatchCodec.PIXELS);
        for (int z = 0; z < 256; z++) {
            for (int x = 0; x < 256; x++) {
                final int surfaceY = 72 + (int) (18 * Math.sin(x / 41.0) * Math.cos(z / 53.0)
                    + 7 * Math.sin((x + z) / 17.0));
                final int biome = ((x / 60) * 3 + (z / 70) * 5) % 6 * 7;
                samples.add(new PatchCodec.Sample(
                    z * 256 + x, biome, surfaceY,
                    (x * 31 + z) % 5 == 0 ? 2 : 1,
                    (biome * 3 + 1) % 60,
                    biome == 0 ? 1 + (x + z) % 9 : 0
                ));
            }
        }
        final byte[] body = PatchCodec.encode(new PatchCodec.Patch(samples));
        assertTrue(body.length <= PatchCodec.MAX_COMPRESSED_BYTES,
            "full-tile body is " + body.length + " bytes, cap " + PatchCodec.MAX_COMPRESSED_BYTES);
        final PatchCodec.Patch decoded = PatchCodec.decode(body);
        assertEquals(PatchCodec.PIXELS, decoded.size());
        assertEquals(samples.get(129 * 256 + 200), decoded.sampleAt(129 * 256 + 200));
    }

    @Test
    void malformedBodyIsRejected() {
        assertThrows(ProtoException.class, () -> PatchCodec.decode(new byte[] {1, 2, 3}));
    }

    @Test
    void truncatedFieldPlaneIsRejected() throws Exception {
        // Coarse bit 0 set, fine bits 0 and 1 set, then only one of the two biome bytes.
        final byte[] raw = new byte[PatchCodec.COARSE_MASK_BYTES + PatchCodec.FINE_MASK_BYTES + 1];
        raw[0] = 1;
        raw[PatchCodec.COARSE_MASK_BYTES] = 3;
        assertThrows(ProtoException.class, () -> PatchCodec.decode(deflate(raw)));
    }

    @Test
    void overlongHeightVarintIsRejected() throws Exception {
        final ByteArrayOutputStream raw = new ByteArrayOutputStream();
        final byte[] masks = new byte[PatchCodec.COARSE_MASK_BYTES + PatchCodec.FINE_MASK_BYTES];
        masks[0] = 1;
        masks[PatchCodec.COARSE_MASK_BYTES] = 1;
        raw.write(masks);
        raw.write(4);
        raw.write(new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 1});
        assertThrows(ProtoException.class, () -> PatchCodec.decode(deflate(raw.toByteArray())));
    }

    private static byte[] deflate(final byte[] raw) throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final Deflater deflater = new Deflater();
        try (DeflaterOutputStream stream = new DeflaterOutputStream(out, deflater)) {
            stream.write(raw);
        } finally {
            deflater.end();
        }
        return out.toByteArray();
    }
}
