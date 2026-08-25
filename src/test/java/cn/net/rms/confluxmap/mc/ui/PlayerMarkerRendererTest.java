package cn.net.rms.confluxmap.mc.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class PlayerMarkerRendererTest {
    @Test
    void fallbackMarkerIsSmallerThanTheOriginalChevron() {
        final PlayerMarkerRenderer.ArmGeometry outline = PlayerMarkerRenderer.LEFT_OUTLINE;
        final float minX = Math.min(
            Math.min(outline.ax(), outline.bx()), Math.min(outline.cx(), outline.dx())
        );
        final float maxX = -minX;
        final float minY = Math.min(
            PlayerMarkerRenderer.JOIN_OUTLINE.ay(),
            Math.min(
                Math.min(outline.ay(), outline.by()), Math.min(outline.cy(), outline.dy())
            )
        );
        final float maxY = Math.max(
            Math.max(outline.ay(), outline.by()), Math.max(outline.cy(), outline.dy())
        );

        assertTrue(maxX - minX < 11f);
        assertTrue(maxY - minY < 9f);
    }

    @Test
    void fallbackMarkerJoinsTheArmsWithOutlinedFill() {
        final PlayerMarkerRenderer.TriangleGeometry outline = PlayerMarkerRenderer.JOIN_OUTLINE;
        final PlayerMarkerRenderer.TriangleGeometry fill = PlayerMarkerRenderer.JOIN_FILL;

        assertTrue(outline.bx() < fill.bx());
        assertTrue(outline.cx() > fill.cx());
        assertTrue(outline.ay() < fill.ay());
        assertTrue(outline.by() > fill.by());
        assertTrue(signedArea2(outline) < 0f);
        assertTrue(signedArea2(fill) < 0f);
    }

    @Test
    void centerTriangleUsesMostOfItsOutlinedArea() {
        final float outlineArea2 = Math.abs(signedArea2(PlayerMarkerRenderer.JOIN_OUTLINE));
        final float fillArea2 = Math.abs(signedArea2(PlayerMarkerRenderer.JOIN_FILL));

        assertTrue(fillArea2 / outlineArea2 > 0.65f);
    }

    @Test
    void fallbackMarkerOpensWiderThanTheOriginalChevron() {
        final PlayerMarkerRenderer.ArmGeometry arm = PlayerMarkerRenderer.LEFT_OUTLINE;
        final float startX = (arm.ax() + arm.bx()) / 2f;
        final float startY = (arm.ay() + arm.by()) / 2f;
        final float endX = (arm.cx() + arm.dx()) / 2f;
        final float endY = (arm.cy() + arm.dy()) / 2f;

        assertTrue(Math.abs(endX - startX) / Math.abs(endY - startY) > 0.6f);
    }

    @Test
    void codeDrawnTrianglesUseTheLegacyFrontFacingWinding() {
        final List<PlayerMarkerRenderer.ArmGeometry> arms = List.of(
            PlayerMarkerRenderer.LEFT_OUTLINE,
            PlayerMarkerRenderer.RIGHT_OUTLINE,
            PlayerMarkerRenderer.LEFT_FILL,
            PlayerMarkerRenderer.RIGHT_FILL
        );

        for (final PlayerMarkerRenderer.ArmGeometry arm : arms) {
            // MC < 1.21.5 emits only one winding. Match the negative GUI-space winding used by
            // the original player triangle instead of presenting the back face to legacy culling.
            assertTrue(
                signedArea2(arm.ax(), arm.ay(), arm.cx(), arm.cy(), arm.bx(), arm.by()) < 0f,
                arm.toString()
            );
            assertTrue(
                signedArea2(arm.ax(), arm.ay(), arm.dx(), arm.dy(), arm.cx(), arm.cy()) < 0f,
                arm.toString()
            );
        }
        assertTrue(signedArea2(PlayerMarkerRenderer.TRADITIONAL_OUTLINE) < 0f);
        assertTrue(signedArea2(PlayerMarkerRenderer.TRADITIONAL_FILL) < 0f);
    }

    private static float signedArea2(
        final float x0,
        final float y0,
        final float x1,
        final float y1,
        final float x2,
        final float y2
    ) {
        return (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0);
    }

    private static float signedArea2(final PlayerMarkerRenderer.TriangleGeometry triangle) {
        return signedArea2(
            triangle.ax(), triangle.ay(),
            triangle.bx(), triangle.by(),
            triangle.cx(), triangle.cy()
        );
    }
}
