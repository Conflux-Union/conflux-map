package cn.net.rms.confluxmap.core.annotation;

/** Minecraft-free geometry shared by editing, persistence, and both map renderers. */
public interface AnnotationGeometry {
    AnnotationBounds bounds();

    AnnotationGeometry translate(double dx, double dz);
}
