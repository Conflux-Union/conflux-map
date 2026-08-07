package cn.net.rms.confluxmap.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.net.PatchCodec;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Pins browser authoritative-patch coloring to the Java map rendering formulas. */
final class WebAuthorityRenderingParityTest {
    @Test
    void authoritativePatchMatchesJavaMapShading(@TempDir final Path tempDir) throws Exception {
        final Process nodeProbe;
        try {
            nodeProbe = new ProcessBuilder("node", "--version").start();
        } catch (final Exception unavailable) {
            Assumptions.assumeTrue(false, "Node.js unavailable");
            return;
        }
        Assumptions.assumeTrue(nodeProbe.waitFor() == 0, "Node.js unavailable");
        final Path application = resource("webmap/app.js");
        final Path parity = resource("webmap/web-authority-render-parity.mjs");
        final Path fixture = tempDir.resolve("authority-patch.bin");
        Files.write(fixture, PatchCodec.encode(new PatchCodec.Patch(List.of(
            new PatchCodec.Sample(
                4,
                1,
                80,
                SurfaceKind.LAND.ordinal(),
                1,
                12,
                11,
                "minecraft:grass_block",
                "minecraft:stone"
            )
        ))));
        final Process process = new ProcessBuilder(
            "node", parity.toString(), application.toString(), fixture.toString()
        ).redirectErrorStream(true).start();
        final StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(process.getInputStream())
        )) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        assertEquals(0, process.waitFor(), output.toString());
    }

    private static Path resource(final String name) throws Exception {
        return Path.of(
            WebAuthorityRenderingParityTest.class.getClassLoader().getResource(name).toURI()
        );
    }
}
