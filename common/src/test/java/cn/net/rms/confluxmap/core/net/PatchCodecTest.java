package cn.net.rms.confluxmap.core.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import org.junit.jupiter.api.Test;

class PatchCodecTest {
    @Test
    void roundTripUsesCoordinatesNotIterationOrder() throws Exception {
        final PatchCodec.Patch patch = new PatchCodec.Patch(List.of(
            new PatchCodec.Sample(15 * 256 + 240, 4, 71, 2, 6, 8, 10),
            new PatchCodec.Sample(0, 1, 64, 1, 1, 0),
            new PatchCodec.Sample(16 * 256 + 16, 2, 65, 2, 255, 2, 11)
        ));
        final PatchCodec.Patch decoded = PatchCodec.decode(PatchCodec.encode(patch));
        assertEquals(patch.samples().size(), decoded.samples().size());
        for (final PatchCodec.Sample sample : patch.samples()) {
            assertEquals(sample, decoded.sampleAt(sample.pixelIndex()));
            assertTrue(decoded.evaluatedAt(sample.pixelIndex()));
        }
    }

    @Test
    void evaluatedPixelWithoutDifferenceRoundTripsUnambiguously() throws Exception {
        final byte[] evaluated = new byte[PatchCodec.MASK_BYTES];
        PatchCodec.setEvaluated(evaluated, 42);

        final PatchCodec.Patch decoded = PatchCodec.decode(PatchCodec.encode(
            new PatchCodec.Patch(evaluated, List.of())
        ));

        assertTrue(decoded.evaluatedAt(42));
        assertFalse(decoded.evaluatedAt(43));
        assertEquals(null, decoded.sampleAt(42));
    }

    @Test
    void enhancedRoundTripCarriesSourceRevisionAndBlockLightForEqualBaselinePixels() throws Exception {
        final byte[] evaluated = new byte[PatchCodec.MASK_BYTES];
        PatchCodec.setEvaluated(evaluated, 42);
        final long[] revisions = new long[PatchCodec.PIXELS];
        Arrays.fill(revisions, Long.MIN_VALUE);
        revisions[42] = 12_345L;
        final byte[] light = new byte[PatchCodec.PIXELS];
        light[42] = 13;

        final PatchCodec.Patch decoded = PatchCodec.decode(PatchCodec.encode(
            new PatchCodec.Patch(evaluated, List.of(), revisions, light)
        ));

        assertEquals(12_345L, decoded.sourceRevisionAt(42));
        assertEquals(13, decoded.blockLightAt(42));
        assertEquals(null, decoded.sampleAt(42));
    }

    @Test
    void materialProfileRoundTripsSurfaceAndFloorRegistryIds() throws Exception {
        final PatchCodec.Sample sample = new PatchCodec.Sample(
            42, 1, 64, 1, 18, 3, 11,
            "minecraft:glowstone", "minecraft:magma_block"
        );

        final PatchCodec.Patch decoded = PatchCodec.decode(PatchCodec.encode(
            new PatchCodec.Patch(List.of(sample))
        ));

        assertEquals("minecraft:glowstone", decoded.sampleAt(42).materialId());
        assertEquals("minecraft:magma_block", decoded.sampleAt(42).floorMaterialId());
    }

    @Test
    void sourceLightProfileDropsMaterialIdsButKeepsLighting() throws Exception {
        final byte[] evaluated = new byte[PatchCodec.MASK_BYTES];
        PatchCodec.setEvaluated(evaluated, 42);
        final long[] revisions = new long[PatchCodec.PIXELS];
        Arrays.fill(revisions, Long.MIN_VALUE);
        revisions[42] = 9L;
        final byte[] light = new byte[PatchCodec.PIXELS];
        light[42] = 14;
        final PatchCodec.Patch patch = new PatchCodec.Patch(
            evaluated,
            List.of(new PatchCodec.Sample(
                42, 1, 64, 1, 18, 0, 255, "minecraft:glowstone", ""
            )),
            revisions,
            light
        );

        final PatchCodec.Patch decoded = PatchCodec.decode(
            PatchCodec.encodeSourceLight(patch)
        );

        assertEquals("", decoded.sampleAt(42).materialId());
        assertEquals(14, decoded.blockLightAt(42));
        assertEquals(9L, decoded.sourceRevisionAt(42));
    }

    @Test
    void legacyRoundTripPreservesTerrainAndMarksNewMetadataUnknown() throws Exception {
        final PatchCodec.Patch decoded = PatchCodec.decode(PatchCodec.encodeLegacy(
            new PatchCodec.Patch(List.of(new PatchCodec.Sample(7, 1, 64, 1, 1, 0)))
        ));

        assertEquals(Long.MIN_VALUE, decoded.sourceRevisionAt(7));
        assertEquals(0, decoded.blockLightAt(7));
        assertEquals(64, decoded.sampleAt(7).surfaceY());
    }

    @Test
    void floorColoursAndExtremeHeightsRoundTrip() throws Exception {
        final PatchCodec.Patch patch = new PatchCodec.Patch(List.of(
            new PatchCodec.Sample(77, 3, 64, 1, 9, 0),
            new PatchCodec.Sample(78, 3, Short.MAX_VALUE, 1, 9, 0),
            new PatchCodec.Sample(79, 3, Short.MIN_VALUE, 1, 9, 0),
            new PatchCodec.Sample(255 * 256 + 255, 250, -60, 4, 60, 15, 10)
        ));
        final PatchCodec.Patch decoded = PatchCodec.decode(PatchCodec.encode(patch));
        assertEquals(patch.samples().size(), decoded.samples().size());
        for (final PatchCodec.Sample sample : patch.samples()) {
            assertEquals(sample, decoded.sampleAt(sample.pixelIndex()));
        }
        assertEquals(10, decoded.sampleAt(255 * 256 + 255).floorMapColorId());
    }

    @Test
    void structuredFullTileStaysUnderCompressedCap() throws Exception {
        final List<PatchCodec.Sample> samples = new ArrayList<>(PatchCodec.PIXELS);
        for (int z = 0; z < 256; z++) {
            for (int x = 0; x < 256; x++) {
                final int surfaceY = 72 + (int) (18 * Math.sin(x / 41.0) * Math.cos(z / 53.0)
                    + 7 * Math.sin((x + z) / 17.0));
                final int biome = ((x / 60) * 3 + (z / 70) * 5) % 6 * 7;
                samples.add(new PatchCodec.Sample(
                    z * 256 + x,
                    biome,
                    surfaceY,
                    (x * 31 + z) % 5 == 0 ? 2 : 1,
                    (biome * 3 + 1) % 60,
                    biome == 0 ? 1 + (x + z) % 9 : 0,
                    biome == 0 ? 10 : 255
                ));
            }
        }
        final byte[] body = PatchCodec.encode(new PatchCodec.Patch(samples));
        assertTrue(body.length <= 48 * 1024,
            "structured full-tile body is " + body.length + " bytes");
        final PatchCodec.Patch decoded = PatchCodec.decode(body);
        assertEquals(PatchCodec.PIXELS, decoded.size());
        assertEquals(samples.get(129 * 256 + 200), decoded.sampleAt(129 * 256 + 200));
    }

    @Test
    void malformedBodyIsRejected() {
        assertThrows(ProtoException.class, () -> PatchCodec.decode(new byte[] {1, 2, 3}));
    }

    @Test
    void truncatedFieldPlaneIsRejected() {
        final byte[] evaluated = new byte[PatchCodec.MASK_BYTES];
        PatchCodec.setEvaluated(evaluated, 0);
        final byte[] valid = PatchCodec.encode(new PatchCodec.Patch(
            evaluated,
            List.of(new PatchCodec.Sample(0, 1, 64, 1, 1, 0))
        ));
        assertThrows(ProtoException.class, () -> PatchCodec.decode(Arrays.copyOf(valid, valid.length - 1)));
    }

    @Test
    void differenceOutsideEvaluatedMaskIsRejected() {
        final byte[] raw = new byte[
            1 + PatchCodec.COARSE_MASK_BYTES * 2 + PatchCodec.FINE_MASK_BYTES + PatchCodec.RECORD_BYTES
        ];
        raw[0] = PatchCodec.FORMAT_VERSION;
        final int differenceCoarse = 1 + PatchCodec.COARSE_MASK_BYTES;
        raw[differenceCoarse] = 1;
        raw[differenceCoarse + PatchCodec.COARSE_MASK_BYTES] = 1;
        assertThrows(ProtoException.class, () -> PatchCodec.decode(deflate(raw)));
    }

    @Test
    void compressedBodyCannotInflatePastRawCap() {
        final byte[] oversized = new byte[PatchCodec.MAX_RAW_BYTES + 1];
        assertThrows(ProtoException.class, () -> PatchCodec.decode(deflate(oversized)));
    }

    @Test
    void trailingCompressedBytesAreRejected() throws Exception {
        final byte[] valid = PatchCodec.encode(new PatchCodec.Patch(List.of()));
        final byte[] trailing = Arrays.copyOf(valid, valid.length + 1);
        assertThrows(ProtoException.class, () -> PatchCodec.decode(trailing));
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
