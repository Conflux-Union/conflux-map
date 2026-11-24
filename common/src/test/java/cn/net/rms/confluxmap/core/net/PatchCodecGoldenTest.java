package cn.net.rms.confluxmap.core.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Golden wire vectors recorded from the codecs before their primitive layer moved into
 * {@link PatchWire}: a negative surface-height delta, a two-byte varint delta (surfaces
 * 40, 30, 200), an empty and a full material table, every format version either codec
 * can encode. The bytes on the wire must not change.
 */
class PatchCodecGoldenTest {
    private static final List<PatchCodec.Sample> PLAIN_SAMPLES = List.of(
        new PatchCodec.Sample(0, 1, 40, 1, 3, 0, 255),
        new PatchCodec.Sample(1, 2, 30, 2, 4, 1, 10),
        new PatchCodec.Sample(300, 3, 200, 1, 5, 0, 0)
    );

    private static final List<PatchCodec.Sample> MATERIAL_SAMPLES = List.of(
        new PatchCodec.Sample(0, 1, 40, 1, 3, 0, 255,
            "minecraft:glowstone", "minecraft:magma_block", "minecraft:glass"),
        new PatchCodec.Sample(1, 2, 30, 2, 4, 1, 10,
            "minecraft:stone", "", ""),
        new PatchCodec.Sample(300, 3, 200, 1, 5, 0, 0,
            "minecraft:dirt", "minecraft:dirt", "minecraft:snow")
    );

    private static final ChunkPatchCodec.Patch CHUNK_PLAIN = new ChunkPatchCodec.Patch(
        1, 1, 4, new byte[] {(byte) 1}, new byte[] {(byte) 7, 0}, List.of(
            new PatchCodec.Sample(0, 1, 40, 1, 3, 0, 255),
            new PatchCodec.Sample(1, 2, 30, 2, 4, 1, 10),
            new PatchCodec.Sample(2, 3, 200, 1, 5, 0, 0)
        )
    );

    private static final ChunkPatchCodec.Patch CHUNK_MATERIALS = new ChunkPatchCodec.Patch(
        1, 1, 4, new byte[] {(byte) 1}, new byte[] {(byte) 7, 0}, List.of(
            new PatchCodec.Sample(0, 1, 40, 1, 3, 0, 255,
                "minecraft:glowstone", "minecraft:magma_block", "minecraft:glass"),
            new PatchCodec.Sample(1, 2, 30, 2, 4, 1, 10,
                "minecraft:stone", "", ""),
            new PatchCodec.Sample(2, 3, 200, 1, 5, 0, 0,
                "minecraft:dirt", "minecraft:dirt", "minecraft:snow")
        )
    );

    @Test
    void patchTileFormatVersionSixIsStable() {
        assertGolden("78 9c 63 63 65 c0 0f 98 09 c8 03 81 00 3e 49 5a 9b cf c8 c4 1c 20 7c 85 89 91"
            + " 89 91 99 85 95 81 91 e1 3f 17 50 0c 9b c2 ff 30 00 91 05 00 f2 ac 0b 89",
            PatchCodec.encode(new PatchCodec.Patch(PLAIN_SAMPLES)));
    }

    @Test
    void patchTileMaterialVersionFiveIsStable() {
        assertGolden("78 9c 63 65 65 c0 0f 98 09 c8 03 81 00 3e 49 5a 9b cf c8 c4 1c 20 7c 85 89 91"
            + " 89 91 99 85 95 81 91 e1 3f 17 50 0c 55 c9 7f 18 80 88 03 00 e2 53 0b 88",
            PatchCodec.encodeMaterial(new PatchCodec.Patch(PLAIN_SAMPLES)));
    }

    @Test
    void patchTileSourceLightVersionFourIsStable() {
        assertGolden("78 9c 63 61 65 c0 0f 98 09 c8 03 81 00 3e 49 5a 9b cf c8 c4 1c 20 7c 85 89 91"
            + " 89 91 99 85 95 81 91 e1 3f 17 c3 7f 18 60 04 ab 00 00 b8 62 0b 86",
            PatchCodec.encodeSourceLight(new PatchCodec.Patch(PLAIN_SAMPLES)));
    }

    @Test
    void patchTileLegacyVersionThreeIsStable() {
        assertGolden("78 9c 63 66 65 c0 0f 98 09 c8 03 81 00 3e 49 5a 9b cf c8 c4 1c 20 7c 85 89 91"
            + " 89 91 99 85 95 81 91 e1 3f 17 03 00 2e 99 02 8d",
            PatchCodec.encodeLegacy(new PatchCodec.Patch(PLAIN_SAMPLES)));
    }

    @Test
    void patchTileFullMaterialTableFormatVersionSixIsStable() {
        assertGolden("78 9c 63 63 65 c0 0f 98 09 c8 03 81 00 3e 49 5a 9b cf c8 c4 1c 20 7c 85 89 91"
            + " 89 91 99 85 95 81 91 e1 3f 17 03 03 3b 50 5c 38 37 33 2f 35 b9 28 31 ad c4 2a 3d 27"
            + " bf bc b8 24 3f 2f 95 41 14 21 98 9b 98 9e 9b 18 9f 94 93 9f 9c cd c0 8f ac 36 b1 b8"
            + " 18 59 00 a2 91 0f 21 90 92 59 54 82 cc 2f ce cb 2f 07 da cb 02 f4 29 13 d8 bf 20 1f"
            + " b1 fd 87 01 46 b0 2b 01 e0 f5 33 c9",
            PatchCodec.encode(new PatchCodec.Patch(MATERIAL_SAMPLES)));
    }

    @Test
    void patchTileFullMaterialTableMaterialVersionFiveIsStable() {
        assertGolden("78 9c 63 65 65 c0 0f 98 09 c8 03 81 00 3e 49 5a 9b cf c8 c4 1c 20 7c 85 89 91"
            + " 89 91 99 85 95 81 91 e1 3f 17 03 03 3b 50 5c 38 37 33 2f 35 b9 28 31 ad c4 2a 3d 27"
            + " bf bc b8 24 3f 2f 95 41 14 21 98 9b 98 9e 9b 18 9f 94 93 9f 9c cd c0 8f ac 36 b1 b8"
            + " 18 59 00 a2 91 0f 21 90 92 59 54 82 cc 2f ce cb 2f 07 da cb 02 f4 29 13 c8 bf ff 61"
            + " 80 11 ec 3e 00 de 39 33 bf",
            PatchCodec.encodeMaterial(new PatchCodec.Patch(MATERIAL_SAMPLES)));
    }

    @Test
    void patchTileMaterialProfilesStripIds() {
        assertGolden("78 9c 63 61 65 c0 0f 98 09 c8 03 81 00 3e 49 5a 9b cf c8 c4 1c 20 7c 85 89 91"
            + " 89 91 99 85 95 81 91 e1 3f 17 c3 7f 18 60 04 ab 00 00 b8 62 0b 86",
            PatchCodec.encodeSourceLight(new PatchCodec.Patch(MATERIAL_SAMPLES)));
        assertGolden("78 9c 63 66 65 c0 0f 98 09 c8 03 81 00 3e 49 5a 9b cf c8 c4 1c 20 7c 85 89 91"
            + " 89 91 99 85 95 81 91 e1 3f 17 03 00 2e 99 02 8d",
            PatchCodec.encodeLegacy(new PatchCodec.Patch(MATERIAL_SAMPLES)));
    }

    @Test
    void chunkRegionFormatVersionFourIsStable() {
        assertGolden("78 9c 63 61 64 64 61 60 64 60 67 00 22 46 26 e6 00 e1 2b 4c 8c 4c 8c cc 2c ac"
            + " 40 c1 ff 5c 0c 40 12 0b f8 0f 03 20 59 00 bf 02 0b 6c",
            ChunkPatchCodec.encode(CHUNK_PLAIN));
    }

    @Test
    void chunkRegionMaterialVersionThreeIsStable() {
        assertGolden("78 9c 63 66 64 64 61 60 64 60 67 00 22 46 26 e6 00 e1 2b 4c 8c 4c 8c cc 2c ac"
            + " 40 c1 ff 5c 0c 40 12 05 fc 87 01 90 38 00 b0 0e 0b 6b",
            ChunkPatchCodec.encodeMaterial(CHUNK_PLAIN));
    }

    @Test
    void chunkRegionSourceLightVersionTwoIsStable() {
        assertGolden("78 9c 63 62 64 64 61 60 64 60 67 00 22 46 26 e6 00 e1 2b 4c 8c 4c 8c cc 2c ac"
            + " 40 c1 ff 5c 0c ff 61 80 91 81 81 01 00 88 a6 0b 69",
            ChunkPatchCodec.encodeSourceLight(CHUNK_PLAIN));
    }

    @Test
    void chunkRegionLegacyVersionOneIsStable() {
        assertGolden("78 9c 63 64 64 64 61 60 64 60 67 00 22 46 26 e6 00 e1 2b 4c 8c 4c 8c cc 2c ac"
            + " 40 c1 ff 5c 0c 00 18 17 02 70",
            ChunkPatchCodec.encodeLegacy(CHUNK_PLAIN));
    }

    @Test
    void chunkRegionFullMaterialTableFormatVersionFourIsStable() {
        assertGolden("78 9c 63 61 64 64 61 60 64 60 67 00 22 46 26 e6 00 e1 2b 4c 8c 4c 8c cc 2c ac"
            + " 40 c1 ff 5c 20 51 06 06 e1 dc cc bc d4 e4 a2 c4 b4 12 ab f4 9c fc f2 e2 92 fc bc 54"
            + " 06 51 84 60 6e 62 7a 6e 62 7c 52 4e 7e 72 36 03 3f b2 da c4 e2 62 64 01 88 46 3e 84"
            + " 40 4a 66 51 09 32 bf 38 2f bf 1c 68 2f 0b 03 2b 03 13 d0 5e 56 06 66 20 c9 f6 1f 06"
            + " 18 81 3c 00 50 55 33 ac",
            ChunkPatchCodec.encode(CHUNK_MATERIALS));
    }

    @Test
    void chunkRegionFullMaterialTableMaterialVersionThreeIsStable() {
        assertGolden("78 9c 63 66 64 64 61 60 64 60 67 00 22 46 26 e6 00 e1 2b 4c 8c 4c 8c cc 2c ac"
            + " 40 c1 ff 5c 20 51 06 06 e1 dc cc bc d4 e4 a2 c4 b4 12 ab f4 9c fc f2 e2 92 fc bc 54"
            + " 06 51 84 60 6e 62 7a 6e 62 7c 52 4e 7e 72 36 03 3f b2 da c4 e2 62 64 01 88 46 3e 84"
            + " 40 4a 66 51 09 32 bf 38 2f bf 1c 68 2f 0b 03 2b 03 13 d0 5e d6 ff 30 c0 08 e4 01 00"
            + " 4f 10 33 a2",
            ChunkPatchCodec.encodeMaterial(CHUNK_MATERIALS));
    }

    @Test
    void chunkRegionMaterialProfilesStripIds() {
        assertGolden("78 9c 63 62 64 64 61 60 64 60 67 00 22 46 26 e6 00 e1 2b 4c 8c 4c 8c cc 2c ac"
            + " 40 c1 ff 5c 0c ff 61 80 91 81 81 01 00 88 a6 0b 69",
            ChunkPatchCodec.encodeSourceLight(CHUNK_MATERIALS));
        assertGolden("78 9c 63 64 64 64 61 60 64 60 67 00 22 46 26 e6 00 e1 2b 4c 8c 4c 8c cc 2c ac"
            + " 40 c1 ff 5c 0c 00 18 17 02 70",
            ChunkPatchCodec.encodeLegacy(CHUNK_MATERIALS));
    }

    private static void assertGolden(final String expectedHex, final byte[] actual) {
        final String[] tokens = expectedHex.split(" ");
        final byte[] expected = new byte[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            expected[i] = (byte) Integer.parseInt(tokens[i], 16);
        }
        assertArrayEquals(expected, actual);
    }
}
