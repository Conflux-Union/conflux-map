package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import cn.net.rms.confluxmap.nativepredict.McVersions;
import cn.net.rms.confluxmap.nativepredict.NativeLib;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Pins the browser WASM baseline to the production Java/JNI predictor. */
class WebPredictorParityTest {
    private static final long SEED = 123456789L;

    @Test
    void overworldBaselineMatchesProductionAtEveryExactResidualBoundary() throws Exception {
        Assumptions.assumeTrue(
            NativeLib.initForTests(), "native prediction library unavailable"
        );
        final Process nodeProbe;
        try {
            nodeProbe = new ProcessBuilder("node", "--version").start();
        } catch (final Exception unavailable) {
            Assumptions.assumeTrue(false, "Node.js unavailable");
            return;
        }
        Assumptions.assumeTrue(nodeProbe.waitFor() == 0, "Node.js unavailable");
        final Path wasm = resource("webmap/predictor.wasm");
        final Path dumpScript = resource("webmap/web-predictor-dump.mjs");
        for (final String release : new String[] {"1.17.1", "1.21.8"}) {
            final int version = McVersions.toCubiomes(release).orElseThrow();
            for (int lod = 0; lod <= 2; lod++) {
                assertParity(version, PredictionDimensions.OVERWORLD, lod, wasm, dumpScript);
                assertParity(version, PredictionDimensions.END, lod, wasm, dumpScript);
            }
        }
        final int current = McVersions.toCubiomes("1.21.8").orElseThrow();
        for (int lod = 3; lod <= 4; lod++) {
            assertParity(current, PredictionDimensions.OVERWORLD, lod, wasm, dumpScript);
        }
    }

    private static void assertParity(
        final int version,
        final int dimension,
        final int lod,
        final Path wasm,
        final Path dumpScript
    ) throws Exception {
        final BaselineGrid javaGrid = LodSampling.sample(
            new NativeBaselineSampler(
                version, SEED, dimension, 0
            ),
            dimension == PredictionDimensions.END, lod, 0, 0
        );
        assertNotNull(javaGrid, "Java baseline LOD" + lod);
        final DerivedGrid derived = BaselineDeriver.derive(javaGrid);
        final int[] beforeCanopy = derived.surfaceY.clone();
        final int[] beforeSubCanopy = derived.subSurfaceY.clone();
        CanopyStylizer.apply(derived, javaGrid, SEED, lod, 0, 0);
        final Process process = new ProcessBuilder(
            "node", dumpScript.toString(), wasm.toString(),
            Integer.toString(version), Long.toString(SEED), Integer.toString(lod),
            Integer.toString(dimension)
        ).redirectErrorStream(true).start();
        int index = 0;
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(process.getInputStream())
        )) {
            String line;
            while ((line = reader.readLine()) != null) {
                final String[] values = line.split(",");
                assertEquals(
                    javaGrid.supersampled() ? 16 : 4,
                    values.length, "WASM output at line " + index
                );
                assertEquals(
                    javaGrid.terrainY[index], Integer.parseInt(values[0]),
                    "terrain height at LOD" + lod + " index " + index
                );
                assertEquals(
                    javaGrid.biomeId[index], Integer.parseInt(values[1]),
                    "surface biome at LOD" + lod + " index " + index
                );
                final int expectedSurface = javaGrid.terrainY[index] == BaselineGrid.NO_SURFACE
                    ? 2
                    : (javaGrid.surfaceFlags[index] & BaselineGrid.SURFACE_FLUID) != 0 ? 1 : 0;
                assertEquals(
                    expectedSurface, Integer.parseInt(values[2]),
                    "fluid surface at LOD" + lod + " index " + index
                );
                assertEquals(
                    derived.surfaceY[index] - beforeCanopy[index],
                    Integer.parseInt(values[3]),
                    "canopy height at LOD" + lod + " index " + index
                );
                if (javaGrid.supersampled()) {
                    for (int sample = 0; sample < 4; sample++) {
                        final int sub = index * 4 + sample;
                        final int offset = 4 + sample * 3;
                        assertEquals(
                            javaGrid.subBiomeId[sub], Integer.parseInt(values[offset]),
                            "sub-biome at LOD" + lod + " index " + sub
                        );
                        assertEquals(
                            (javaGrid.subSurfaceFlags[sub] & BaselineGrid.SURFACE_FLUID) != 0
                                ? 1 : 0,
                            Integer.parseInt(values[offset + 1]),
                            "sub-fluid at LOD" + lod + " index " + sub
                        );
                        assertEquals(
                            derived.subSurfaceY[sub] - beforeSubCanopy[sub],
                            Integer.parseInt(values[offset + 2]),
                            "sub-canopy at LOD" + lod + " index " + sub
                        );
                    }
                }
                index++;
            }
        }
        assertEquals(258 * 258, index, "WASM cell count at LOD" + lod);
        assertEquals(0, process.waitFor(), "WASM dump process at LOD" + lod);
    }

    private static Path resource(final String name) throws Exception {
        return Path.of(WebPredictorParityTest.class.getClassLoader().getResource(name).toURI());
    }
}
