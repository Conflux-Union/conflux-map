package cn.net.rms.confluxmap.mc.ui.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.util.ChunkViewport;
import org.junit.jupiter.api.Test;

class MinimapCaptureViewportTest {
    @Test
    void squareAtWorstCaseRotationPublishesItsWholeVisibleChunkSquare() {
        final ChunkViewport viewport = MinimapHudRenderer.captureViewport(
            0.0, 0.0, 90, 1.0, ConfluxConfig.Shape.SQUARE, 45.0
        );

        assertEquals(new ChunkViewport(-5, 4, -5, 4), viewport);
        assertEquals(100, viewport.chunkCount());
    }

    @Test
    void axisAlignedSquareDoesNotPublishTheUnusedRotationCorners() {
        final ChunkViewport viewport = MinimapHudRenderer.captureViewport(
            0.0, 0.0, 90, 1.0, ConfluxConfig.Shape.SQUARE, 0.0
        );

        assertEquals(new ChunkViewport(-4, 3, -4, 3), viewport);
        assertEquals(64, viewport.chunkCount());
    }

    @Test
    void circleDoesNotGrowWhenTheMapRotates() {
        final ChunkViewport viewport = MinimapHudRenderer.captureViewport(
            0.0, 0.0, 90, 1.0, ConfluxConfig.Shape.CIRCLE, 45.0
        );

        assertEquals(new ChunkViewport(-4, 3, -4, 3), viewport);
        assertEquals(64, viewport.chunkCount());
    }

    @Test
    void testedLargeZoomedMinimapHasABoundedWorstCaseViewport() {
        final ChunkViewport viewport = MinimapHudRenderer.captureViewport(
            0.0, 0.0, 128, 2.0, ConfluxConfig.Shape.SQUARE, 45.0
        );

        assertEquals(new ChunkViewport(-12, 11, -12, 11), viewport);
        assertEquals(576, viewport.chunkCount());
    }
}
