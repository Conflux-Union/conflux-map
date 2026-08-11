package cn.net.rms.confluxmap.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

final class WebPlayerMarkerBehaviorTest {
    @Test
    void playerMarkersUseSquareFallbackAvatarsAndSmoothMovement() throws Exception {
        final Process nodeProbe;
        try {
            nodeProbe = new ProcessBuilder("node", "--version").start();
        } catch (final Exception unavailable) {
            Assumptions.assumeTrue(false, "Node.js unavailable");
            return;
        }
        Assumptions.assumeTrue(nodeProbe.waitFor() == 0, "Node.js unavailable");
        final Process process = new ProcessBuilder(
            "node",
            resource("webmap/web-player-marker-behavior.mjs").toString(),
            resource("webmap/app.js").toString(),
            resource("webmap/app.css").toString()
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
            WebPlayerMarkerBehaviorTest.class.getClassLoader().getResource(name).toURI()
        );
    }
}
