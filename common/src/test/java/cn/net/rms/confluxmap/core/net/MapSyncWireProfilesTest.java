package cn.net.rms.confluxmap.core.net;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class MapSyncWireProfilesTest {
    @Test
    void tileDowngradeKeepsTerrainAndDropsEnhancedMetadata() throws Exception {
        final byte[] evaluated = new byte[PatchCodec.MASK_BYTES];
        PatchCodec.setEvaluated(evaluated, 0);
        final long[] revisions = new long[PatchCodec.PIXELS];
        Arrays.fill(revisions, Long.MIN_VALUE);
        revisions[0] = 42L;
        final byte[] light = new byte[PatchCodec.PIXELS];
        light[0] = 12;
        final MapPatchS2C enhanced = new MapPatchS2C(
            1, 0, 0, 0, 0, Proto.PATCH_MODE_ABSOLUTE, 7L,
            new byte[Proto.PATCH_PRESENCE_BYTES],
            PatchCodec.encode(new PatchCodec.Patch(
                evaluated,
                List.of(new PatchCodec.Sample(0, 1, 64, 1, 1, 0)),
                revisions,
                light
            ))
        );

        final MapPatchS2C legacy = (MapPatchS2C) MapSyncWireProfiles.legacy(enhanced);
        final PatchCodec.Patch decoded = PatchCodec.decode(legacy.body());

        assertEquals(64, decoded.sampleAt(0).surfaceY());
        assertEquals(Long.MIN_VALUE, decoded.sourceRevisionAt(0));
        assertEquals(0, decoded.blockLightAt(0));
    }

    @Test
    void regionDowngradeKeepsLegacyRegionShape() throws Exception {
        final ChunkPatchCodec.Patch enhancedBody = new ChunkPatchCodec.Patch(
            1, 1, 1, new byte[] {1}, new byte[] {1}, List.of(),
            new long[] {42L}, new byte[] {12}
        );
        final MapRegionPatchS2C enhanced = new MapRegionPatchS2C(
            1, 0, 4, 0, 0, 0, 0, 0, 0,
            Proto.PATCH_MODE_RESIDUAL, 9L, ChunkPatchCodec.encode(enhancedBody)
        );

        final MapRegionPatchS2C legacy =
            (MapRegionPatchS2C) MapSyncWireProfiles.legacy(enhanced);
        final ChunkPatchCodec.Patch decoded = ChunkPatchCodec.decode(legacy.body());

        assertEquals(Long.MIN_VALUE, decoded.sourceRevisionAt(0));
        assertEquals(0, decoded.blockLightAt(0));
    }
}
