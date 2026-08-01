package cn.net.rms.confluxmap.core.annotation;

/** Visual attributes deliberately independent from geometry and screen widgets. */
public record AnnotationStyle(int colorArgb) {
    public static final double STROKE_WIDTH_PX = 2.0;
}
