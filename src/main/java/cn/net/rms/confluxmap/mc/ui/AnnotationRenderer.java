package cn.net.rms.confluxmap.mc.ui;

import cn.net.rms.confluxmap.core.annotation.Annotation;
import cn.net.rms.confluxmap.core.annotation.AnnotationGeometry;
import cn.net.rms.confluxmap.core.annotation.AnnotationPoint;
import cn.net.rms.confluxmap.core.annotation.AnnotationProjection;
import cn.net.rms.confluxmap.core.annotation.CircleAnnotationGeometry;
import cn.net.rms.confluxmap.core.annotation.FreehandAnnotationGeometry;
import cn.net.rms.confluxmap.core.annotation.LineAnnotationGeometry;
import cn.net.rms.confluxmap.core.annotation.RectangleAnnotationGeometry;
import cn.net.rms.confluxmap.mc.render.RenderUtil;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.util.math.MatrixStack;

/** Stateless renderer shared by the fullscreen map and minimap HUD. */
public final class AnnotationRenderer {
    public enum ClipShape { RECTANGLE, CIRCLE }

    private static final float STROKE_WIDTH = 2.0f;
    private static final float SELECTED_STROKE_WIDTH = 4.0f;
    private static final int SELECTED_OUTLINE = 0xE6FFFFFF;

    private AnnotationRenderer() {
    }

    public static void drawGeometry(
        final MatrixStack matrices,
        final List<Annotation> annotations,
        final AnnotationProjection projection,
        final UUID selectedId
    ) {
        for (final Annotation annotation : annotations) {
            if (!projection.mayBeVisible(annotation.geometry().bounds(), SELECTED_STROKE_WIDTH)) {
                continue;
            }
            if (annotation.id().equals(selectedId)) {
                drawGeometry(matrices, annotation.geometry(), projection, SELECTED_STROKE_WIDTH, SELECTED_OUTLINE);
            }
            drawGeometry(
                matrices, annotation.geometry(), projection, STROKE_WIDTH,
                annotation.style().colorArgb()
            );
        }
    }

    public static void drawLabels(
        final GuiDraw draw,
        final TextRenderer textRenderer,
        final List<Annotation> annotations,
        final AnnotationProjection projection,
        final double clipX,
        final double clipY,
        final double clipWidth,
        final double clipHeight,
        final ClipShape clipShape
    ) {
        for (final Annotation annotation : annotations) {
            if (annotation.label().isEmpty()
                || !projection.mayBeVisible(annotation.geometry().bounds(), 2.0)) {
                continue;
            }
            final AnnotationProjection.ScreenPoint anchor = projection.project(annotation.geometry().bounds().center());
            final int textWidth = textWidth(textRenderer, annotation.label());
            final double left = anchor.x() - textWidth / 2.0;
            final double top = anchor.y() - textRenderer.fontHeight / 2.0;
            if (!fitsClip(
                left, top, textWidth, textRenderer.fontHeight,
                clipX, clipY, clipWidth, clipHeight, clipShape
            )) {
                continue;
            }
            draw.drawTextWithShadow(
                textRenderer,
                annotation.label(),
                (float) left,
                (float) top,
                annotation.style().colorArgb() | 0xFF000000
            );
        }
    }

    private static boolean fitsClip(
        final double x,
        final double y,
        final double width,
        final double height,
        final double clipX,
        final double clipY,
        final double clipWidth,
        final double clipHeight,
        final ClipShape shape
    ) {
        if (x < clipX || y < clipY || x + width > clipX + clipWidth || y + height > clipY + clipHeight) {
            return false;
        }
        if (shape == ClipShape.RECTANGLE) {
            return true;
        }
        final double centerX = clipX + clipWidth / 2.0;
        final double centerY = clipY + clipHeight / 2.0;
        final double radius = Math.min(clipWidth, clipHeight) / 2.0;
        return insideCircle(x, y, centerX, centerY, radius)
            && insideCircle(x + width, y, centerX, centerY, radius)
            && insideCircle(x, y + height, centerX, centerY, radius)
            && insideCircle(x + width, y + height, centerX, centerY, radius);
    }

    private static int textWidth(final TextRenderer renderer, final String text) {
        //#if MC>=260100
        //$$ return renderer.width(text);
        //#else
        return renderer.getWidth(text);
        //#endif
    }

    private static boolean insideCircle(
        final double x,
        final double y,
        final double centerX,
        final double centerY,
        final double radius
    ) {
        return Math.hypot(x - centerX, y - centerY) <= radius;
    }

    private static void drawGeometry(
        final MatrixStack matrices,
        final AnnotationGeometry geometry,
        final AnnotationProjection projection,
        final float width,
        final int color
    ) {
        if (geometry instanceof final LineAnnotationGeometry line) {
            stroke(matrices, projection.project(line.start()), projection.project(line.end()), width, color);
        } else if (geometry instanceof final CircleAnnotationGeometry circle) {
            final AnnotationProjection.ScreenPoint center = projection.project(circle.center());
            final float radius = (float) (circle.radius() / projection.blocksPerPixel());
            if (radius > 0.0f) {
                RenderUtil.drawRing(matrices, (float) center.x(), (float) center.y(), radius, width, color);
            }
        } else if (geometry instanceof final RectangleAnnotationGeometry rectangle) {
            final AnnotationPoint min = rectangle.min();
            final AnnotationPoint max = rectangle.max();
            final AnnotationProjection.ScreenPoint topLeft = projection.project(min);
            final AnnotationProjection.ScreenPoint topRight = projection.project(new AnnotationPoint(max.x(), min.z()));
            final AnnotationProjection.ScreenPoint bottomRight = projection.project(max);
            final AnnotationProjection.ScreenPoint bottomLeft = projection.project(new AnnotationPoint(min.x(), max.z()));
            stroke(matrices, topLeft, topRight, width, color);
            stroke(matrices, topRight, bottomRight, width, color);
            stroke(matrices, bottomRight, bottomLeft, width, color);
            stroke(matrices, bottomLeft, topLeft, width, color);
        } else if (geometry instanceof final FreehandAnnotationGeometry freehand) {
            for (int index = 1; index < freehand.points().size(); index++) {
                stroke(
                    matrices,
                    projection.project(freehand.points().get(index - 1)),
                    projection.project(freehand.points().get(index)),
                    width,
                    color
                );
            }
        }
    }

    private static void stroke(
        final MatrixStack matrices,
        final AnnotationProjection.ScreenPoint start,
        final AnnotationProjection.ScreenPoint end,
        final float width,
        final int color
    ) {
        final double dx = end.x() - start.x();
        final double dy = end.y() - start.y();
        final double length = Math.hypot(dx, dy);
        if (length == 0.0) {
            return;
        }
        final float px = (float) (-dy / length * width / 2.0);
        final float py = (float) (dx / length * width / 2.0);
        final float x0 = (float) start.x() + px;
        final float y0 = (float) start.y() + py;
        final float x1 = (float) end.x() + px;
        final float y1 = (float) end.y() + py;
        final float x2 = (float) end.x() - px;
        final float y2 = (float) end.y() - py;
        final float x3 = (float) start.x() - px;
        final float y3 = (float) start.y() - py;
        RenderUtil.fillTriangle(matrices, x0, y0, x1, y1, x2, y2, color);
        RenderUtil.fillTriangle(matrices, x0, y0, x2, y2, x3, y3, color);
    }
}
