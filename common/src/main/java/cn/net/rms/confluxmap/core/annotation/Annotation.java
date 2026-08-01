package cn.net.rms.confluxmap.core.annotation;

import cn.net.rms.confluxmap.core.model.DimensionId;
import java.util.Objects;
import java.util.UUID;

/** Immutable client-owned annotation. */
public record Annotation(
    UUID id,
    DimensionId dimension,
    AnnotationGeometry geometry,
    AnnotationStyle style,
    String label,
    AnnotationPersistence persistence,
    long createdAtEpochMs
) {
    public static final int MAX_LABEL_LENGTH = 64;

    public Annotation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(geometry, "geometry");
        Objects.requireNonNull(style, "style");
        Objects.requireNonNull(persistence, "persistence");
        label = label == null ? "" : label;
        if (label.length() > MAX_LABEL_LENGTH) {
            throw new IllegalArgumentException("annotation label is too long");
        }
    }

    public Annotation withGeometry(final AnnotationGeometry replacement) {
        return new Annotation(id, dimension, replacement, style, label, persistence, createdAtEpochMs);
    }

    public Annotation withStyle(final AnnotationStyle replacement) {
        return new Annotation(id, dimension, geometry, replacement, label, persistence, createdAtEpochMs);
    }

    public Annotation withLabel(final String replacement) {
        return new Annotation(id, dimension, geometry, style, replacement, persistence, createdAtEpochMs);
    }

    public Annotation withPersistence(final AnnotationPersistence replacement) {
        return new Annotation(id, dimension, geometry, style, label, replacement, createdAtEpochMs);
    }
}
