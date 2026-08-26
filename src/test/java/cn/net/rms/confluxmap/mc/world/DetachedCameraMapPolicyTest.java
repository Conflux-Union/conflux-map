package cn.net.rms.confluxmap.mc.world;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class DetachedCameraMapPolicyTest {
    @Test
    void heightSensitiveLayerSelectionUsesTheActiveCamera() throws Exception {
        final String source = Files.readString(preprocessedSource(
            "cn/net/rms/confluxmap/mc/world/LayerSelector.java"
        ));

        assertTrue(source.contains("final PlayerView viewpoint = gameBridge.viewpoint().orElse(null);"));
        assertTrue(source.contains("Math.floor(viewpoint.eyeY())"));
        assertTrue(source.contains("resolveOverworld(world, viewpoint, eyeY"));
        assertTrue(source.contains("new BlockPos(viewpoint.blockX(), eyeY, viewpoint.blockZ())"));
    }

    @Test
    void minimapKeepsTheLocalPlayerMarkerDistinctFromTheCameraViewpoint() throws Exception {
        final String source = Files.readString(preprocessedSource(
            "cn/net/rms/confluxmap/mc/ui/hud/MinimapHudRenderer.java"
        ));

        assertTrue(source.contains("final Optional<PlayerView> localPlayer = gameBridge.player(tickDelta);"));
        assertTrue(source.contains("player.x() - viewpoint.x()"));
        assertTrue(source.contains("player.z() - viewpoint.z()"));
        assertTrue(source.contains("MathHelper.clamp(screenDx, -limit, limit)"));
        assertTrue(source.contains("player.yawDegrees() - viewpoint.yawDegrees()"));
    }

    private static Path preprocessedSource(final String relativePath) throws URISyntaxException {
        Path current = Path.of(
            DetachedCameraMapPolicyTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()
        );
        while (current != null && !"build".equals(current.getFileName().toString())) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("Could not locate the version build directory");
        }
        final Path preprocessed = current.resolve("preprocessed/main/java").resolve(relativePath);
        if (Files.exists(preprocessed)) {
            return preprocessed;
        }

        return current.getParent().getParent().getParent().resolve("src/main/java").resolve(relativePath);
    }
}
