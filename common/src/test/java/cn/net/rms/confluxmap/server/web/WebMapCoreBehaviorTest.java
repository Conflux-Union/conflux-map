package cn.net.rms.confluxmap.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Exercises the browser map state contract through its public ES module. */
final class WebMapCoreBehaviorTest {
    @Test
    void partialPatchesRemainUncommittedAndUnavailablePatchesReplaceStalePixels() throws Exception {
        final Process process;
        try {
            process = new ProcessBuilder(
                "node",
                resource("webmap/web-map-core-behavior.mjs").toString(),
                resource("webmap/map-core.js").toString()
            ).redirectErrorStream(true).start();
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
        return Path.of(WebMapCoreBehaviorTest.class.getClassLoader().getResource(name).toURI());
    }
}
