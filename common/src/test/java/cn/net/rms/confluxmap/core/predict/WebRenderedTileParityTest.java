package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.confluxmap.nativepredict.McVersions;
import cn.net.rms.confluxmap.nativepredict.NativeLib;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.net.PatchCodec;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.server.web.WebMapManifest;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Pins the browser's final tile pixels to the production Java composer. */
final class WebRenderedTileParityTest {
    private static final long SEED = 123456789L;

    @Test
    void browserWasmMatchesEveryFinalJavaPixelAcrossLods(@TempDir final Path tempDir)
        throws Exception {
        Assumptions.assumeTrue(NativeLib.initForTests(), "native prediction library unavailable");
        final int version = McVersions.toCubiomes("1.21.8").orElseThrow();
        final Path manifest = tempDir.resolve("manifest.json");
        Files.writeString(manifest, new WebMapManifest(
            "parity", "1.21.8", SEED, version,
            List.of(new WebMapManifest.Dimension(
                0, "minecraft:overworld", "overworld", true, WorldPreset.DEFAULT
            ))
        ).toJson());
        for (final int lod : new int[] {0, 2, 4}) {
            assertPredictedParity(version, lod, 0, 0, manifest, tempDir);
        }
        assertPredictedParity(version, 4, -65_536, -65_536, manifest, tempDir);
        assertCorrectedParity(version, tempDir);
    }

    private static void assertPredictedParity(
        final int version,
        final int lod,
        final int blockX,
        final int blockZ,
        final Path manifest,
        final Path tempDir
    ) throws Exception {
        final BaselineGrid grid = LodSampling.sample(
            new NativeBaselineSampler(version, SEED, PredictionDimensions.OVERWORLD, 0),
            false, lod, blockX, blockZ
        );
        final DerivedGrid derived = BaselineDeriver.derive(grid);
        CanopyStylizer.apply(derived, grid, SEED, lod, blockX, blockZ);
        final int[] expected = PredictedTileComposer.compose(
            derived, grid, PredictionPalette.defaults(), null,
            PredictionViewMode.EVERYWHERE, lod
        );
        final Path image = tempDir.resolve(
            "expected-" + lod + "-" + blockX + "-" + blockZ + ".argb"
        );
        try (DataOutputStream output = new DataOutputStream(
            new BufferedOutputStream(Files.newOutputStream(image))
        )) {
            for (final int pixel : expected) output.writeInt(pixel);
        }
        assertNodeParity(version, lod, blockX, blockZ, manifest, image);
    }

    private static void assertCorrectedParity(final int version, final Path tempDir)
        throws Exception {
        final int lod = 0;
        final BaselineGrid grid = LodSampling.sample(
            new NativeBaselineSampler(version, SEED, PredictionDimensions.OVERWORLD, 0),
            false, lod, 0, 0
        );
        final DerivedGrid derived = BaselineDeriver.derive(grid);
        CanopyStylizer.apply(derived, grid, SEED, lod, 0, 0);
        final int pixel = 127 * 256 + 127;
        final PatchCodec.Patch patch = new PatchCodec.Patch(List.of(new PatchCodec.Sample(
            pixel, 1, 92, SurfaceKind.LAND.ordinal(), 11, 0, 255,
            "minecraft:glowstone", ""
        )));
        final CorrectionTile corrections = new CorrectionTile(lod);
        corrections.applyPatch(1L, new byte[Proto.PATCH_PRESENCE_BYTES], patch);
        final SyncedMaterialPalette materials = new SyncedMaterialPalette();
        final int[] expected = PredictedTileComposer.compose(
            derived, grid, PredictionPalette.defaults(), corrections,
            PredictionViewMode.EVERYWHERE, lod, Proto.MAP_COLOR_NONE,
            derived, grid, Proto.MAP_COLOR_NONE, true, 0xFFFFFFFF, materials
        );
        final Path image = tempDir.resolve("expected-corrected.argb");
        try (DataOutputStream output = new DataOutputStream(
            new BufferedOutputStream(Files.newOutputStream(image))
        )) {
            for (final int value : expected) output.writeInt(value);
        }
        final Path encodedPatch = tempDir.resolve("correction.bin");
        Files.write(encodedPatch, PatchCodec.encode(patch));
        final Path manifest = tempDir.resolve("corrected-manifest.json");
        Files.writeString(manifest, new WebMapManifest(
            "parity", "1.21.8", SEED, version,
            List.of(new WebMapManifest.Dimension(
                0, "minecraft:overworld", "overworld", true, WorldPreset.DEFAULT
            ))
        ).toJson());
        assertNodeParity(version, lod, 0, 0, manifest, image, encodedPatch);
    }

    private static void assertNodeParity(
        final int version,
        final int lod,
        final int blockX,
        final int blockZ,
        final Path manifest,
        final Path expected
    ) throws Exception {
        assertNodeParity(version, lod, blockX, blockZ, manifest, expected, null);
    }

    private static void assertNodeParity(
        final int version,
        final int lod,
        final int blockX,
        final int blockZ,
        final Path manifest,
        final Path expected,
        final Path patch
    ) throws Exception {
        final Process process;
        try {
            final java.util.ArrayList<String> command = new java.util.ArrayList<>(List.of(
                "node", resource("webmap/web-rendered-tile-parity.mjs").toString(),
                resource("webmap/predictor.wasm").toString(),
                resource("webmap/map-renderer.js").toString(),
                manifest.toString(), expected.toString(), Integer.toString(version),
                Long.toString(SEED), Integer.toString(lod),
                Integer.toString(PredictionDimensions.OVERWORLD),
                Integer.toString(blockX), Integer.toString(blockZ)
            ));
            if (patch != null) command.add(patch.toString());
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (final IOException unavailable) {
            Assumptions.assumeTrue(false, "Node.js unavailable");
            return;
        }
        final String output = new String(
            process.getInputStream().readAllBytes(), StandardCharsets.UTF_8
        );
        assertEquals(0, process.waitFor(), output);
    }

    private static Path resource(final String name) throws Exception {
        return Path.of(WebRenderedTileParityTest.class.getClassLoader().getResource(name).toURI());
    }
}
