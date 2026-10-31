package cn.net.rms.confluxmap.mc.ui.world;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Guards the seam split: {@link WaypointWorldRenderer} is the version-free orchestrator, so
 * every version branch must live in {@link WorldOverlayEvents}, {@link BeamPass}, or
 * {@link LabelPass}. Unlike the other source scans, this reads the version-preprocessed
 * source under {@code src/main} (the preprocessed build output keeps gate lines, so it can
 * never satisfy this assertion). A {@code //#if} here means the per-version extraction
 * ladder leaked back into the orchestration logic.
 */
final class WaypointWorldRendererVersionFreeTest {
    @Test
    void orchestratorCarriesNoVersionGates() throws IOException, URISyntaxException {
        final String source = Files.readString(orchestratorSource());
        assertFalse(
            source.contains("//#if"),
            "WaypointWorldRenderer must stay version-free; move the branch into "
                + "WorldOverlayEvents, BeamPass, or LabelPass"
        );
    }

    private static Path orchestratorSource() throws URISyntaxException, IOException {
        Path current = Path.of(
            WaypointWorldRenderer.class.getProtectionDomain().getCodeSource().getLocation().toURI()
        );
        while (current != null && !"build".equals(current.getFileName().toString())) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("Could not locate the version build directory");
        }
        // Ascend from versions/<version>/build to the repository root that owns src/main.
        final Path relative = Path.of(
            "src", "main", "java", "cn", "net", "rms", "confluxmap", "mc", "ui", "world",
            "WaypointWorldRenderer.java"
        );
        Path root = current.getParent();
        while (root != null && !Files.exists(root.resolve(relative))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IllegalStateException("Could not locate the repository root for src/main");
        }
        return root.resolve(relative);
    }
}
