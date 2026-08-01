package cn.net.rms.confluxmap.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class MinimapPlacementTest {

    @Test
    void legacyCornersMapToEquivalentNormalizedPositions() {
        assertEquals(new MinimapPlacement.Position(0.0, 0.0),
            MinimapPlacement.fromLegacyCorner(ConfluxConfig.Corner.TOP_LEFT));
        assertEquals(new MinimapPlacement.Position(1.0, 0.0),
            MinimapPlacement.fromLegacyCorner(ConfluxConfig.Corner.TOP_RIGHT));
        assertEquals(new MinimapPlacement.Position(0.0, 1.0),
            MinimapPlacement.fromLegacyCorner(ConfluxConfig.Corner.BOTTOM_LEFT));
        assertEquals(new MinimapPlacement.Position(1.0, 1.0),
            MinimapPlacement.fromLegacyCorner(ConfluxConfig.Corner.BOTTOM_RIGHT));
    }

    @Test
    void normalizedPositionUsesTheWholeReachableArea() {
        assertEquals(new MinimapPlacement.Layout(4, 4, 128),
            MinimapPlacement.resolve(320, 240, 128, 0.0, 0.0));
        assertEquals(new MinimapPlacement.Layout(188, 108, 128),
            MinimapPlacement.resolve(320, 240, 128, 1.0, 1.0));
        assertEquals(new MinimapPlacement.Layout(96, 56, 128),
            MinimapPlacement.resolve(320, 240, 128, 0.5, 0.5));
    }

    @Test
    void positionRemainsReachableAfterResizeAndGuiScaleChanges() {
        final MinimapPlacement.Layout original = MinimapPlacement.resolve(640, 360, 128, 0.75, 0.25);
        final MinimapPlacement.Layout resized = MinimapPlacement.resolve(320, 180, 128, 0.75, 0.25);

        assertEquals(new MinimapPlacement.Layout(382, 60, 128), original);
        assertEquals(new MinimapPlacement.Layout(142, 15, 128), resized);
    }

    @Test
    void oversizedMinimapShrinksTemporarilyToFitTheViewport() {
        assertEquals(new MinimapPlacement.Layout(14, 4, 52),
            MinimapPlacement.resolve(70, 60, 128, 1.0, 1.0));
    }

    @Test
    void draggedOriginConvertsBackToClampedNormalizedPosition() {
        assertEquals(new MinimapPlacement.Position(0.5, 0.5),
            MinimapPlacement.positionForOrigin(320, 240, 128, 96, 56));
        assertEquals(new MinimapPlacement.Position(0.0, 1.0),
            MinimapPlacement.positionForOrigin(320, 240, 128, -100, 1_000));
    }

    @Test
    void dragStartsOnlyOnTheMinimapAndPreservesTheGrabOffset() {
        final MinimapPlacement.Layout layout = MinimapPlacement.resolve(320, 240, 128, 1.0, 0.0);
        assertNull(MinimapPlacement.startDrag(layout, 100, 100));

        final MinimapPlacement.Drag drag = MinimapPlacement.startDrag(layout, 200, 20);
        assertNotNull(drag);
        assertEquals(new MinimapPlacement.Drag(12.0, 16.0), drag);

        final MinimapPlacement.Position moved = MinimapPlacement.dragTo(320, 240, 128, drag, 100, 100);
        assertEquals(new MinimapPlacement.Layout(88, 84, 128),
            MinimapPlacement.resolve(320, 240, 128, moved.x(), moved.y()));
    }

    @Test
    void keyboardNudgeMovesOneScreenPixelAndStaysClamped() {
        final MinimapPlacement.Position nudged = MinimapPlacement.nudge(
            320, 240, 128, new MinimapPlacement.Position(1.0, 0.0), -1, 1
        );
        assertEquals(new MinimapPlacement.Layout(187, 5, 128),
            MinimapPlacement.resolve(320, 240, 128, nudged.x(), nudged.y()));

        final MinimapPlacement.Position clamped = MinimapPlacement.nudge(
            320, 240, 128, new MinimapPlacement.Position(0.0, 1.0), -1, 1
        );
        assertEquals(new MinimapPlacement.Layout(4, 108, 128),
            MinimapPlacement.resolve(320, 240, 128, clamped.x(), clamped.y()));
    }
}
