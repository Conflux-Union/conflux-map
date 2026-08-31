package cn.net.rms.confluxmap.mc.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertTrue(source.contains("clamp(screenDx, -limit, limit)"));
        assertTrue(source.contains("player.yawDegrees() - viewpoint.yawDegrees()"));
    }

    @Test
    void bridgeReportsWhetherTheCameraEntityIsDetached() throws Exception {
        final String bridge = Files.readString(preprocessedSource(
            "cn/net/rms/confluxmap/mc/McGameBridge.java"
        ));

        assertTrue(bridge.contains("public boolean isCameraDetached()"));
        assertTrue(bridge.contains("cameraEntity != null && cameraEntity != player"));
    }

    @Test
    void detachedCameraMarkerIsDrawnOnBothLiveMaps() throws Exception {
        final String minimap = Files.readString(preprocessedSource(
            "cn/net/rms/confluxmap/mc/ui/hud/MinimapHudRenderer.java"
        ));
        final String fullscreen = Files.readString(preprocessedSource(
            "cn/net/rms/confluxmap/mc/ui/screen/FullscreenMapScreen.java"
        ));
        final String minimapMarker = sourceBetween(
            minimap,
            "private void drawCameraMarker(",
            "\n    /** Draws the real player's position"
        );
        final String fullscreenMarker = sourceBetween(
            fullscreen,
            "private void drawCameraMarker(",
            "\n    /** Always north-locked"
        );

        assertTrue(minimap.contains("gameBridge.isCameraDetached()"));
        assertTrue(minimap.contains("drawCameraMarker("));
        assertTrue(minimapMarker.contains("0.5f"));
        assertTrue(fullscreen.contains("gameBridge.isCameraDetached()"));
        assertTrue(fullscreen.contains("drawCameraMarker("));
        assertTrue(fullscreenMarker.contains("0.5f"));
        assertTrue(fullscreen.contains(
            "private static final int PLAYER_MARKER_COLOR = 0xFFFFFFFF;"
        ));
    }

    @Test
    void chunkCaptureRefreshFollowsTheActiveCamera() throws Exception {
        final String source = Files.readString(preprocessedSource(
            "cn/net/rms/confluxmap/mc/snapshot/ChunkCaptureService.java"
        ));

        assertTrue(source.contains("final PlayerView viewpoint = gameBridge.viewpoint().orElse(null);"));
        assertTrue(source.contains("final int viewpointChunkX = viewpoint.blockX() >> 4;"));
        assertTrue(source.contains("final int viewpointChunkZ = viewpoint.blockZ() >> 4;"));
        assertTrue(source.contains("reseedViewport(viewpointChunkX, viewpointChunkZ);"));
        assertTrue(source.contains(
            "tickBudget.maximumCandidates(),\n                viewpointChunkX,\n                viewpointChunkZ"
        ));
    }

    @Test
    void currentPositionWaypointCreationFollowsTheActiveCamera() throws Exception {
        final String hotkeySource = sourceBetween(
            Files.readString(preprocessedSource("cn/net/rms/confluxmap/mc/input/KeybindActionHandler.java")),
            "private static boolean openNewWaypointAtViewpoint()",
            "\n    }\n}"
        );
        final String listSource = sourceBetween(
            Files.readString(preprocessedSource("cn/net/rms/confluxmap/mc/ui/screen/WaypointListScreen.java")),
            "private void openCreate(",
            "\n    private void openEdit("
        );

        assertTrue(hotkeySource.contains(
            "final Optional<PlayerView> viewpoint = ConfluxMapClient.get().gameBridge().viewpoint();"
        ));
        assertTrue(listSource.contains("final Optional<PlayerView> viewpoint = gameBridge.viewpoint();"));
        assertFalse(hotkeySource.contains(
            "final Optional<PlayerView> playerView = ConfluxMapClient.get().gameBridge().player();"
        ));
        assertFalse(listSource.contains("final Optional<PlayerView> playerView = gameBridge.player();"));
    }

    private static String sourceBetween(final String source, final String start, final String end) {
        final int startIndex = source.indexOf(start);
        final int endIndex = source.indexOf(end, startIndex);
        if (startIndex < 0 || endIndex < 0) {
            throw new IllegalArgumentException("Could not locate source range");
        }
        return source.substring(startIndex, endIndex);
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
