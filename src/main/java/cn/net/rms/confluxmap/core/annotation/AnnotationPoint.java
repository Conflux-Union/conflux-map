package cn.net.rms.confluxmap.core.annotation;

/** One immutable X/Z point in world coordinates. */
public record AnnotationPoint(double x, double z) {
    public AnnotationPoint {
        if (!Double.isFinite(x) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("annotation coordinates must be finite");
        }
    }

    public AnnotationPoint translate(final double dx, final double dz) {
        return new AnnotationPoint(x + dx, z + dz);
    }
}
