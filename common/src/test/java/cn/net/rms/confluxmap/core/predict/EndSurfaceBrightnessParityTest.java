package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.color.DaylightModel;
import cn.net.rms.confluxmap.core.color.LightTint;
import cn.net.rms.confluxmap.core.color.MapColorStyle;
import cn.net.rms.confluxmap.core.color.MaterialDetailProfile;
import cn.net.rms.confluxmap.core.color.ShadingPipeline;
import cn.net.rms.confluxmap.core.color.XaeroMapStyle;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.SampleSource;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.model.TileKey;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.net.PatchCodec;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.store.MapWorld;
import cn.net.rms.confluxmap.core.store.MapWorldService;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.tile.TileService;
import cn.net.rms.confluxmap.core.util.Argb;
import cn.net.rms.confluxmap.nativepredict.McVersions;
import cn.net.rms.confluxmap.nativepredict.NativeLib;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Anchors the "End map renders as an all-white sheet after teleporting" report at the pipeline
 * level. The End has neither sky light nor block light, so authoritative END_SURFACE pixels owe
 * the same dark-light contract as caves and the Nether roof (cave-nether-layers.md renders the
 * entire End with the cave algorithm): the zero-sky-light {@link LightTint} curve - readability
 * floor plus warm tint, gamma-aware - applied on top of the raw surface colour.
 *
 * <p>Two authoritative pipelines are pinned against that contract for one uniform end-stone
 * column: the local capture path ({@code TileService} composing a raw END_SURFACE snapshot,
 * exactly the representation {@code McChunkSnapshotFactory#writeSurface} stores) and the
 * synchronized-correction path ({@code PredictionTileService#composeTile} composing a full-cover
 * companion patch, the representation {@code ChunkColumnSummarizer} produces). Before the fix
 * both skipped the curve entirely - the capture fed raw unlit colours into a gamma-only relight
 * and the correction plane baked an identity ambient tint - leaving pale end stone at full
 * brightness, which reads as an all-white map.
 *
 * <p>Exact equality between the pipelines is not asserted: the correction path stores its pixels
 * with the zero-light curve already baked in and re-scales by channel ratio, while the capture
 * path multiplies a raw pixel once, so a one-step rounding difference per channel is legitimate.
 */
final class EndSurfaceBrightnessParityTest {
    private static final DimensionId DIM = DimensionId.END;
    private static final WorldIdentity WORLD = WorldIdentity.singleplayer("end-brightness-parity");
    private static final String MATERIAL = "minecraft:end_stone";
    /** Average of vanilla 1.17.1 {@code end_stone.png}, the raw colour a live capture samples. */
    private static final int END_STONE_COLOR = 0xFFDBDE9E;
    /** Vanilla map colour SAND: what the companion reports for an end-stone column. */
    private static final int END_STONE_MAP_COLOR = 2;
    private static final int SURFACE_Y = 64;
    private static final long SEED = 146008555L;
    private static final int MC_VERSION = McVersions.toCubiomes("1.17").orElseThrow();
    private static final float LIVE_GAMMA = 0.5f;
    /** The correction path's ratio re-scale may legitimately differ by one step per channel. */
    private static final int PARITY_CHANNEL_TOLERANCE = 1;

    @Test
    void localEndSurfaceAppliesTheDarkLightContract() throws Exception {
        final LocalHarness harness = new LocalHarness(MapColorStyle.CONFLUX, 0f);
        try {
            assertContractPixel(
                harness.pixel(0),
                expectedConfluxPixel(0, 0f),
                "an unlit end column must sit on the readability floor, not full brightness"
            );
        } finally {
            harness.close();
        }
    }

    @Test
    void localEndSurfaceLiftsTorchLight() throws Exception {
        final LocalHarness harness = new LocalHarness(MapColorStyle.CONFLUX, 0f);
        try {
            assertContractPixel(
                harness.pixel(14),
                expectedConfluxPixel(14, 0f),
                "a torch-lit end column must keep its block-light lift over the floor"
            );
            assertTrue(
                Argb.red(harness.pixel(14)) > Argb.red(harness.pixel(0)),
                "block light 14 must brighten the end column"
            );
        } finally {
            harness.close();
        }
    }

    @Test
    void localEndSurfaceHonorsLiveGamma() throws Exception {
        final LocalHarness harness = new LocalHarness(MapColorStyle.CONFLUX, LIVE_GAMMA);
        try {
            assertContractPixel(
                harness.pixel(0),
                expectedConfluxPixel(0, LIVE_GAMMA),
                "live gamma must relight the end column through the gamma-aware curve"
            );
        } finally {
            harness.close();
        }
    }

    @Test
    void localEndSurfaceAppliesTheContractInXaeroStyle() throws Exception {
        final LocalHarness harness = new LocalHarness(MapColorStyle.XAERO, 0f);
        try {
            assertContractPixel(
                harness.pixel(0),
                expectedXaeroPixel(0, 0f),
                "the xaero style must darken end columns by the same curve"
            );
        } finally {
            harness.close();
        }
    }

    @Test
    void synchronizedEndCorrectionsMatchLocalCapture(@TempDir final Path tempDir) throws Exception {
        Assumptions.assumeTrue(NativeLib.initForTests(), "native prediction library unavailable");
        final LocalHarness local = new LocalHarness(MapColorStyle.CONFLUX, 0f);
        final SyncHarness sync = new SyncHarness(MapColorStyle.CONFLUX, 0f, tempDir);
        try {
            for (final int light : new int[] {0, 14}) {
                assertParityPixel(
                    local.pixel(light),
                    sync.pixel(light),
                    "a synchronized end column must match the captured one (block light " + light + ")"
                );
            }
        } finally {
            local.close();
            sync.close();
        }
    }

    @Test
    void synchronizedEndCorrectionsMatchLocalCaptureInXaeroStyleWithGamma(
        @TempDir final Path tempDir
    ) throws Exception {
        Assumptions.assumeTrue(NativeLib.initForTests(), "native prediction library unavailable");
        final LocalHarness local = new LocalHarness(MapColorStyle.XAERO, LIVE_GAMMA);
        final SyncHarness sync = new SyncHarness(MapColorStyle.XAERO, LIVE_GAMMA, tempDir);
        try {
            for (final int light : new int[] {0, 14}) {
                assertParityPixel(
                    local.pixel(light),
                    sync.pixel(light),
                    "the xaero style must keep end parity under live gamma (block light " + light + ")"
                );
            }
        } finally {
            local.close();
            sync.close();
        }
    }

    /** The captured tile's own formula: raw colour x height shade, then the zero-sky-light curve. */
    private static int expectedConfluxPixel(final int blockLight, final float gamma) {
        final double heightShade = ShadingPipeline.detailedHeightShade(
            SURFACE_Y, ShadingPipeline.REFERENCE_HEIGHT
        );
        final int shaded = ShadingPipeline.applyBrightnessMultiplier(
            ShadingPipeline.applyShade(Argb.multiply(END_STONE_COLOR, 0xFFFFFFFF), heightShade),
            1.0
        );
        return LightTint.applyBlockLightTint(shaded, blockLight, false, gamma);
    }

    private static int expectedXaeroPixel(final int blockLight, final float gamma) {
        final int terrain = XaeroMapStyle.applyTerrain(
            Argb.multiply(END_STONE_COLOR, 0xFFFFFFFF),
            SURFACE_Y, SURFACE_Y, SURFACE_Y, 1, true, XaeroMapStyle.shadowFor(DIM)
        );
        return LightTint.applyBlockLightTint(terrain, blockLight, false, gamma);
    }

    private static void assertContractPixel(
        final int actual,
        final int expected,
        final String message
    ) {
        assertEquals(
            String.format("%08x", expected), String.format("%08x", actual), message
        );
    }

    private static void assertParityPixel(
        final int captured,
        final int synchronizedPixel,
        final String message
    ) {
        assertTrue(
            Math.abs(Argb.red(captured) - Argb.red(synchronizedPixel)) <= PARITY_CHANNEL_TOLERANCE
                && Math.abs(Argb.green(captured) - Argb.green(synchronizedPixel)) <= PARITY_CHANNEL_TOLERANCE
                && Math.abs(Argb.blue(captured) - Argb.blue(synchronizedPixel)) <= PARITY_CHANNEL_TOLERANCE,
            message + ": captured=" + String.format("%08x", captured)
                + " synchronized=" + String.format("%08x", synchronizedPixel)
        );
    }

    /**
     * One End session with one uniform captured end-stone region: the exact raw representation
     * {@code McChunkSnapshotFactory#writeSurface} stores for END_SURFACE columns (no baked light;
     * {@code light} carries the block light above the surface).
     */
    private static final class LocalHarness implements AutoCloseable {
        private static final int CENTER_X = 128;
        private static final int CENTER_Z = 128;
        private final MapExecutors executors = new MapExecutors();
        private final MapWorldService worlds;
        private final TileService tiles;

        LocalHarness(final MapColorStyle style, final float gamma) {
            final SessionGuard.Session session = new SessionGuard().begin(WORLD, DIM);
            worlds = new MapWorldService();
            worlds.switchSession(session);
            final ConfluxConfig config = new ConfluxConfig();
            config.mapColorStyle = style;
            final DaylightModel daylight = new DaylightModel();
            daylight.update(1f, gamma);
            tiles = new TileService(worlds, executors, config, daylight);
        }

        int pixel(final int blockLight) throws Exception {
            final MapWorld world = worlds.current();
            for (int chunkZ = 0; chunkZ < 16; chunkZ++) {
                for (int chunkX = 0; chunkX < 16; chunkX++) {
                    world.put(
                        MapLayer.END_SURFACE, snapshot(chunkX, chunkZ, blockLight),
                        SampleSource.REAL_LIVE
                    );
                }
            }
            final int[] pixels = tiles.snapshotTile(
                new TileKey(WORLD, DIM, MapLayer.END_SURFACE.cacheId(), 0, 0, 0),
                true, 1f
            ).get(30, TimeUnit.SECONDS);
            final int center = CENTER_Z * 256 + CENTER_X;
            assertTrue(pixels[center] != Argb.TRANSPARENT, "the captured end column must render");
            return pixels[center];
        }

        private static ChunkSnapshot snapshot(final int chunkX, final int chunkZ, final int blockLight) {
            return new ChunkSnapshot(
                chunkX, chunkZ, 1L, 1L,
                fill(new short[ChunkSnapshot.COLUMNS], (short) SURFACE_Y),
                fill(new String[ChunkSnapshot.COLUMNS], "minecraft:the_end"),
                new byte[ChunkSnapshot.COLUMNS],
                fill(new int[ChunkSnapshot.COLUMNS], END_STONE_COLOR),
                fill(new int[ChunkSnapshot.COLUMNS], END_STONE_COLOR),
                fill(new int[ChunkSnapshot.COLUMNS], 0xFFFFFFFF),
                fill(new int[ChunkSnapshot.COLUMNS], Argb.TRANSPARENT),
                fill(new int[ChunkSnapshot.COLUMNS], Argb.TRANSPARENT),
                fill(new byte[ChunkSnapshot.COLUMNS], (byte) SurfaceKind.LAND.ordinal()),
                fill(new byte[ChunkSnapshot.COLUMNS], (byte) blockLight)
            );
        }

        @Override
        public void close() {
            executors.shutdown(2000L);
        }
    }

    /**
     * One End session whose entire tile is covered by an absolute companion patch of the same
     * end-stone column, composed through the real synchronized-correction pipeline
     * ({@code PredictionTileService#composeTile}), including its block-light plane.
     */
    private static final class SyncHarness implements AutoCloseable {
        private static final int CENTER_X = 128;
        private static final int CENTER_Z = 128;
        private final MapExecutors executors = new MapExecutors();
        private final CorrectionStore corrections;
        private final PredictionTileService predictionTiles;
        private int appliedLight = -1;

        SyncHarness(final MapColorStyle style, final float gamma, final Path tempDir) {
            final SessionGuard sessionGuard = new SessionGuard();
            final SessionGuard.Session session = sessionGuard.begin(WORLD, DIM);
            final MapWorldService worlds = new MapWorldService();
            worlds.switchSession(session);
            final DaylightModel daylight = new DaylightModel();
            daylight.update(1f, gamma);
            final TileService uploads = new TileService(
                worlds, executors, new ConfluxConfig(), daylight
            );
            final PredictionState state = new PredictionState();
            state.setPresets(WorldPreset.DEFAULT, WorldPreset.DEFAULT, WorldPreset.DEFAULT);
            state.setSeed(SEED, MC_VERSION);
            predictionTiles = new PredictionTileService(sessionGuard, state, executors, uploads);
            predictionTiles.setMapColorStyle(style);
            predictionTiles.bindDaylightModel(daylight);
            predictionTiles.syncedMaterials().put(MATERIAL, new SyncedMaterialPalette.Sample(
                END_STONE_COLOR, MaterialDetailProfile.flat(), SyncedMaterialPalette.Tint.NONE, 0, 0
            ));
            corrections = new CorrectionStore(tempDir);
            predictionTiles.bindCorrectionStore(corrections);
        }

        int pixel(final int blockLight) throws Exception {
            if (blockLight != appliedLight) {
                applyFullCoverPatch(blockLight);
                appliedLight = blockLight;
            }
            final int[] pixels = predictionTiles.snapshotTile(
                new TileKey(
                    WORLD, DIM, MapLayer.END_SURFACE.cacheId() + PredictedTileKeys.SUFFIX, 0, 0, 0
                ),
                PredictionViewMode.EVERYWHERE
            ).get(30, TimeUnit.SECONDS);
            assertNotNull(pixels, "the end tile must compose");
            final int center = CENTER_Z * 256 + CENTER_X;
            assertTrue(pixels[center] != Argb.TRANSPARENT, "the synchronized end column must render");
            return pixels[center];
        }

        private void applyFullCoverPatch(final int blockLight) {
            final List<PatchCodec.Sample> samples = new ArrayList<>(PatchCodec.PIXELS);
            for (int pixel = 0; pixel < PatchCodec.PIXELS; pixel++) {
                samples.add(new PatchCodec.Sample(
                    pixel, 9, SURFACE_Y, SurfaceKind.LAND.ordinal(),
                    END_STONE_MAP_COLOR, 0, 255, MATERIAL, ""
                ));
            }
            final PatchCodec.Patch patch;
            if (blockLight == 0) {
                patch = new PatchCodec.Patch(samples);
            } else {
                final byte[] evaluated = new byte[PatchCodec.MASK_BYTES];
                java.util.Arrays.fill(evaluated, (byte) 0xFF);
                final byte[] lightPlane = new byte[PatchCodec.PIXELS];
                java.util.Arrays.fill(lightPlane, (byte) blockLight);
                patch = new PatchCodec.Patch(evaluated, samples, unknownRevisions(), lightPlane);
            }
            final byte[] presence = new byte[Proto.PATCH_PRESENCE_BYTES];
            for (int i = 0; i < presence.length; i++) {
                presence[i] = (byte) 0xFF;
            }
            assertTrue(
                corrections.apply(
                    new CorrectionStore.Key(DIM.toString(), 0, 0, 0), 42L, presence, patch,
                    Proto.PATCH_MODE_ABSOLUTE, "", System.currentTimeMillis(),
                    cn.net.rms.confluxmap.core.net.CorrectionProfile.SOURCE_LIGHT_V2
                ),
                "the correction store must accept the full-cover absolute end patch"
            );
        }

        private static long[] unknownRevisions() {
            final long[] revisions = new long[PatchCodec.PIXELS];
            java.util.Arrays.fill(revisions, Long.MIN_VALUE);
            return revisions;
        }

        @Override
        public void close() {
            executors.shutdown(2000L);
        }
    }

    private static short[] fill(final short[] array, final short value) {
        java.util.Arrays.fill(array, value);
        return array;
    }

    private static String[] fill(final String[] array, final String value) {
        java.util.Arrays.fill(array, value);
        return array;
    }

    private static int[] fill(final int[] array, final int value) {
        java.util.Arrays.fill(array, value);
        return array;
    }

    private static byte[] fill(final byte[] array, final byte value) {
        java.util.Arrays.fill(array, value);
        return array;
    }
}
