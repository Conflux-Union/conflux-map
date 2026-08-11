package cn.net.rms.confluxmap.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

final class WebMapConnectionBehaviorTest {
    @Test
    void disconnectedBrowserSocketReconnectsAndBecomesReadyAgain() throws Exception {
        final Process process;
        try {
            process = new ProcessBuilder(
                "node",
                resource("webmap/web-map-connection-behavior.mjs").toString(),
                resource("webmap/map-connection.js").toString()
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
        return Path.of(WebMapConnectionBehaviorTest.class.getClassLoader().getResource(name).toURI());
    }
}
