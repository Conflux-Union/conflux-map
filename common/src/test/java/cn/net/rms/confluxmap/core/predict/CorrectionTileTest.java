package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.net.PatchCodec;
import cn.net.rms.confluxmap.core.net.ChunkPatchCodec;
import cn.net.rms.confluxmap.core.net.CorrectionProfile;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.util.ChunkRegionSlice;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorrectionTileTest {
    @Test
    void regionRevisionUsesTheNegotiatedCorrectionProfile() {
        final CorrectionTile tile = new CorrectionTile(4);
        final ChunkRegionSlice slice = new ChunkRegionSlice(0, 0, 0, 0, 0, 0);
        final ChunkPatchCodec.Patch patch = new ChunkPatchCodec.Patch(
            1, 1, 1, new byte[] {1}, new byte[] {1}, List.of(),
            new long[] {42L}, new byte[] {12}
        );

        tile.applyRegionSlice(
            0, 0, patch, Proto.PATCH_MODE_RESIDUAL, "baseline", 1L,
            CorrectionProfile.LEGACY_V1
        );

        assertEquals(
            ChunkPatchCodec.regionRevision(4, slice, patch, CorrectionProfile.LEGACY_V1),
            tile.regionSliceRevision(0, 0, slice)
        );
    }

    @Test
    void changingCorrectionProfileClearsTheOldRegionSourceMode() {
        final CorrectionTile tile = new CorrectionTile(4);
        final ChunkPatchCodec.Patch patch = new ChunkPatchCodec.Patch(
            1, 1, 1, new byte[] {1}, new byte[] {1}, List.of()
        );

        tile.applyRegionSlice(
            0, 0, patch, Proto.PATCH_MODE_RESIDUAL, "baseline", 1L,
            CorrectionProfile.SOURCE_LIGHT_V2
        );
        tile.applyRegionSlice(
            1, 0, patch, Proto.PATCH_MODE_ABSOLUTE, "", 2L,
            CorrectionProfile.LEGACY_V1
        );

        assertEquals(Proto.PATCH_MODE_ABSOLUTE, tile.patchMode());
        assertEquals("", tile.baselineProfile());
        assertEquals(CorrectionProfile.LEGACY_V1, tile.correctionProfile());
        assertNull(tile.sampleAt(0));
    }

    @Test
    void committedSnapshotRetainsItsWireModeAndBaselineProfile() {
        final CorrectionTile tile = new CorrectionTile();
        tile.applyPatch(
            1L,
            new byte[Proto.PATCH_PRESENCE_BYTES],
            new PatchCodec.Patch(java.util.List.of()),
            Proto.PATCH_MODE_RESIDUAL,
            "cb:9afc1038ea5a|shim:9|base:14",
            1_000L
        );

        assertEquals(Proto.PATCH_MODE_RESIDUAL, tile.patchMode());
        assertEquals("cb:9afc1038ea5a|shim:9|base:14", tile.baselineProfile());
    }

    @Test
    void residualsRequireTheirSourceBaselineButAbsolutePatchesDoNot() {
        final CorrectionTile residual = new CorrectionTile();
        residual.applyPatch(
            1L,
            new byte[Proto.PATCH_PRESENCE_BYTES],
            new PatchCodec.Patch(java.util.List.of()),
            Proto.PATCH_MODE_RESIDUAL,
            "baseline-v1",
            1_000L
        );
        final CorrectionTile absolute = new CorrectionTile();
        absolute.applyPatch(
            1L,
            new byte[Proto.PATCH_PRESENCE_BYTES],
            new PatchCodec.Patch(java.util.List.of()),
            Proto.PATCH_MODE_ABSOLUTE,
            "",
            1_000L
        );

        assertTrue(PredictionTileService.supportsCorrectionBaseline(residual, "baseline-v1"));
        assertFalse(PredictionTileService.supportsCorrectionBaseline(residual, "baseline-v2"));
        assertTrue(PredictionTileService.supportsCorrectionBaseline(absolute, "baseline-v2"));
        assertTrue(residual.matchesSource(Proto.PATCH_MODE_RESIDUAL, "baseline-v1"));
        assertFalse(residual.matchesSource(Proto.PATCH_MODE_RESIDUAL, "baseline-v2"));
        assertFalse(residual.matchesSource(Proto.PATCH_MODE_ABSOLUTE, ""));
        assertTrue(absolute.matchesSource(Proto.PATCH_MODE_ABSOLUTE, "ignored"));
        assertTrue(absolute.matchesSource(Proto.PATCH_MODE_RESIDUAL, "baseline-v1"));
    }

    @Test
    void croppedLodFourRegionPatchReplacesOnlyCoveredChunks() {
        final CorrectionTile tile = new CorrectionTile(4);
        final PatchCodec.Sample retained = new PatchCodec.Sample(9 * 256 + 252, 1, 70, 1, 1, 0);
        tile.applyPatch(
            1L,
            new byte[Proto.PATCH_PRESENCE_BYTES],
            new PatchCodec.Patch(java.util.List.of(retained)),
            1_000L
        );
        final byte[] generated = new byte[ChunkPatchCodec.maskBytes(6)];
        final byte[] evaluated = new byte[ChunkPatchCodec.maskBytes(6)];
        for (int i = 0; i < 6; i++) {
            ChunkPatchCodec.setBit(generated, i);
            ChunkPatchCodec.setBit(evaluated, i);
        }
        final ChunkPatchCodec.Patch page = new ChunkPatchCodec.Patch(
            3, 2, 1, generated, evaluated, java.util.List.of(
                new PatchCodec.Sample(0, 2, 90, 2, 11, 0),
                new PatchCodec.Sample(5, 3, 95, 2, 12, 0)
            )
        );

        tile.applyRegionSlice(253, 10, page, 2_000L);

        assertEquals(retained, tile.sampleAt(9 * 256 + 252));
        assertEquals(90, tile.sampleAt(10 * 256 + 253).surfaceY());
        assertEquals(95, tile.sampleAt(11 * 256 + 255).surfaceY());
        assertTrue(tile.regionSliceFreshAt(253, 10, 3, 2, 2_500L, 1_000L));
        assertFalse(tile.regionSliceFreshAt(252, 10, 4, 2, 2_500L, 1_000L));
    }

    @Test
    void absoluteAndResidualRegionPagesCanShareOneTile() {
        final ChunkPatchCodec.Patch residualPage = new ChunkPatchCodec.Patch(
            1, 1, 1, new byte[] {1}, new byte[] {1}, java.util.List.of(
                new PatchCodec.Sample(0, 1, 70, 1, 1, 0)
            )
        );
        final ChunkPatchCodec.Patch absolutePage = new ChunkPatchCodec.Patch(
            1, 1, 1, new byte[] {1}, new byte[] {1}, java.util.List.of(
                new PatchCodec.Sample(0, 2, 90, 2, 2, 0)
            )
        );
        final CorrectionTile tile = new CorrectionTile(4);

        tile.applyRegionSlice(
            0, 0, residualPage, Proto.PATCH_MODE_RESIDUAL, "baseline-v1", 1_000L
        );
        tile.applyRegionSlice(
            1, 0, absolutePage, Proto.PATCH_MODE_ABSOLUTE, "", 2_000L
        );

        assertEquals(70, tile.sampleAt(0).surfaceY());
        assertEquals(90, tile.sampleAt(1).surfaceY());
        assertEquals(Proto.PATCH_MODE_RESIDUAL, tile.patchMode());
        assertEquals("baseline-v1", tile.baselineProfile());

        final CorrectionTile reverse = new CorrectionTile(4);
        reverse.applyRegionSlice(
            1, 0, absolutePage, Proto.PATCH_MODE_ABSOLUTE, "", 1_000L
        );
        reverse.applyRegionSlice(
            0, 0, residualPage, Proto.PATCH_MODE_RESIDUAL, "baseline-v1", 2_000L
        );

        assertEquals(70, reverse.sampleAt(0).surfaceY());
        assertEquals(90, reverse.sampleAt(1).surfaceY());
        assertEquals(Proto.PATCH_MODE_RESIDUAL, reverse.patchMode());
        assertEquals("baseline-v1", reverse.baselineProfile());
    }

    @Test
    void generatedOnlyVisibilityUsesExactEvaluatedPixelsInsteadOfCoarsePresence() {
        final byte[] presence = new byte[Proto.PATCH_PRESENCE_BYTES];
        presence[0] = 1;
        final byte[] evaluated = new byte[PatchCodec.MASK_BYTES];
        PatchCodec.setEvaluated(evaluated, 64);
        final CorrectionTile tile = new CorrectionTile();
        tile.applyPatch(1L, presence, new PatchCodec.Patch(evaluated, java.util.List.of()));

        assertTrue(tile.hasGeneratedChunkForPixel(64, 2));
        assertFalse(tile.hasGeneratedChunkForPixel(0, 2));
    }

    @Test
    void completeSnapshotOmissionDropsAnOlderCorrectionWithoutTouchingOtherPixels() {
        final byte[] presence = new byte[Proto.PATCH_PRESENCE_BYTES];
        final PatchCodec.Sample removed = new PatchCodec.Sample(1, 1, 80, 1, 11, 0);
        final PatchCodec.Sample retained = new PatchCodec.Sample(2, 1, 79, 1, 11, 0);
        final CorrectionTile tile = new CorrectionTile();
        tile.applyPatch(1L, presence, new PatchCodec.Patch(java.util.List.of(removed, retained)));

        final PatchCodec.Sample lowered = new PatchCodec.Sample(2, 1, 72, 1, 1, 0);
        tile.applyPatch(2L, presence, new PatchCodec.Patch(java.util.List.of(lowered)));

        assertNull(tile.sampleAt(1));
        assertEquals(lowered, tile.sampleAt(2));
    }

    @Test
    void opaqueSnapshotRevisionCanMoveNumericallyBackward() {
        final byte[] presence = new byte[Proto.PATCH_PRESENCE_BYTES];
        final CorrectionTile tile = new CorrectionTile();
        tile.applyPatch(
            100L,
            presence,
            new PatchCodec.Patch(java.util.List.of(new PatchCodec.Sample(1, 1, 80, 1, 11, 0)))
        );
        final PatchCodec.Sample replacement = new PatchCodec.Sample(2, 1, 72, 1, 1, 0);

        assertTrue(tile.applyPatch(5L, presence, new PatchCodec.Patch(java.util.List.of(replacement))));

        assertNull(tile.sampleAt(1));
        assertEquals(replacement, tile.sampleAt(2));
        assertEquals(5L, tile.revision());
    }

    @Test
    void partialScanReplacesItsOverlayWithoutAdvancingCommittedRevision() {
        final byte[] committedPresence = new byte[Proto.PATCH_PRESENCE_BYTES];
        committedPresence[0] = 1;
        final PatchCodec.Sample committed = new PatchCodec.Sample(1, 1, 80, 1, 11, 0);
        final CorrectionTile tile = new CorrectionTile();
        tile.applyPatch(20L, committedPresence, new PatchCodec.Patch(java.util.List.of(committed)));

        final byte[] firstPresence = new byte[Proto.PATCH_PRESENCE_BYTES];
        firstPresence[0] = 2;
        final PatchCodec.Sample progress = new PatchCodec.Sample(2, 1, 95, 1, 11, 0);
        tile.applyPartial(firstPresence, new PatchCodec.Patch(java.util.List.of(progress)));

        assertEquals(20L, tile.revision(), "a partial scan must not become the sinceRevision watermark");
        assertEquals(committed, tile.sampleAt(1), "the last complete snapshot stays drawable");
        assertNull(tile.sampleAt(2), "pending samples are not mixed into the committed snapshot");
        assertTrue(tile.hasGeneratedChunk(0, 0), "committed presence remains visible");
        assertFalse(tile.hasGeneratedChunk(1, 0), "pending presence is not mixed into committed coverage");

        tile.applyPartial(new byte[Proto.PATCH_PRESENCE_BYTES], new PatchCodec.Patch(java.util.List.of()));
        assertEquals(committed, tile.sampleAt(1));
        assertNull(tile.sampleAt(2));
    }

    @Test
    void finalPatchClearsProgressAndCommitsTheNewWatermark() {
        final CorrectionTile tile = new CorrectionTile();
        final PatchCodec.Sample partial = new PatchCodec.Sample(7, 1, 90, 1, 11, 0);
        tile.applyPartial(new byte[Proto.PATCH_PRESENCE_BYTES], new PatchCodec.Patch(java.util.List.of(partial)));

        final PatchCodec.Sample committed = new PatchCodec.Sample(8, 1, 91, 1, 11, 0);
        tile.applyPatch(30L, new byte[Proto.PATCH_PRESENCE_BYTES], new PatchCodec.Patch(java.util.List.of(committed)));

        assertNull(tile.sampleAt(7));
        assertEquals(committed, tile.sampleAt(8));
        assertEquals(30L, tile.revision());
    }

    @Test
    void committedValidationExpiresAndProgressIsNeverReusable() {
        final CorrectionTile tile = new CorrectionTile();
        final PatchCodec.Sample sample = new PatchCodec.Sample(7, 1, 90, 1, 11, 0);
        final PatchCodec.Patch patch = new PatchCodec.Patch(java.util.List.of(sample));
        tile.applyPatch(
            1L,
            new byte[Proto.PATCH_PRESENCE_BYTES],
            patch,
            10_000L
        );

        assertEquals(10_000L, tile.validatedAtMillis());
        assertTrue(tile.isFreshAt(14_999L, 5_000L));
        assertFalse(tile.isFreshAt(15_001L, 5_000L));
        assertFalse(tile.isFreshAt(9_999L, 5_000L), "a future wall-clock stamp must not be trusted");

        assertTrue(tile.invalidateValidation());
        assertEquals(0L, tile.validatedAtMillis());
        assertFalse(tile.isFreshAt(10_001L, 5_000L));
        assertEquals(sample, tile.sampleAt(sample.pixelIndex()));

        tile.applyPartial(
            new byte[Proto.PATCH_PRESENCE_BYTES],
            new PatchCodec.Patch(java.util.List.of())
        );
        assertFalse(tile.isFreshAt(11_000L, 5_000L), "progressive state is not a committed cache entry");
    }
}
