package cn.net.rms.confluxmap.core.annotation;

import cn.net.rms.confluxmap.core.model.DimensionId;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.logging.log4j.Logger;

/** Explicit, versioned JSON persistence for one world's persistent annotations. */
public final class AnnotationIo {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_ANNOTATIONS = 10_000;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private AnnotationIo() {
    }

    private static final class FileShape {
        int schemaVersion = SCHEMA_VERSION;
        List<Entry> annotations = new ArrayList<>();
    }

    private static final class Entry {
        String id;
        String dimensionId;
        String geometryType;
        double x1;
        double z1;
        double x2;
        double z2;
        double radius;
        List<PointEntry> points;
        int colorArgb;
        String label;
        long createdAtEpochMs;
    }

    private static final class PointEntry {
        double x;
        double z;
    }

    public static AnnotationStore.State load(final Path file, final Logger logger) {
        if (!Files.exists(file)) {
            return new AnnotationStore.State(List.of());
        }
        try {
            final JsonElement parsed = new JsonParser().parse(Files.readString(file, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new JsonParseException("annotation file root must be an object");
            }
            final JsonObject root = parsed.getAsJsonObject();
            if (usesFutureSchema(root)) {
                logger.warn("Annotation file {} uses a future schema; persistent annotations are read-only", file);
                return new AnnotationStore.State(List.of(), false);
            }
            final FileShape shape = GSON.fromJson(root, FileShape.class);
            if (shape == null || shape.annotations == null) {
                throw new JsonParseException("empty annotation file");
            }
            if (shape.annotations.size() > MAX_ANNOTATIONS) {
                throw new JsonParseException("too many annotations");
            }
            final List<Annotation> annotations = new ArrayList<>();
            for (final Entry entry : shape.annotations) {
                try {
                    final Annotation annotation = toAnnotation(entry);
                    if (annotation != null) {
                        annotations.add(annotation);
                    }
                } catch (final IllegalArgumentException | NullPointerException e) {
                    logger.warn("Dropping invalid annotation entry in {} ({})", file, e.toString());
                }
            }
            return new AnnotationStore.State(annotations);
        } catch (final IOException | RuntimeException e) {
            logger.warn("Annotation file {} unreadable ({}), quarantining and starting empty", file, e.toString());
            quarantine(file, logger);
            return new AnnotationStore.State(List.of());
        }
    }

    public static void save(final Path file, final AnnotationStore.State state, final Logger logger) {
        if (!state.persistenceWritable()) {
            logger.warn("Refusing to overwrite read-only future-schema annotation file {}", file);
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            final FileShape shape = new FileShape();
            for (final Annotation annotation : state.annotations()) {
                if (annotation.persistence() == AnnotationPersistence.PERSISTENT) {
                    shape.annotations.add(toEntry(annotation));
                }
            }
            final Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, GSON.toJson(shape), StandardCharsets.UTF_8);
            move(temporary, file);
        } catch (final IOException | RuntimeException e) {
            logger.error("Failed to save annotations to {}", file, e);
        }
    }

    private static Annotation toAnnotation(final Entry entry) {
        if (entry == null || entry.id == null || entry.dimensionId == null || entry.geometryType == null) {
            return null;
        }
        final AnnotationGeometry geometry = switch (entry.geometryType) {
            case "LINE" -> new LineAnnotationGeometry(
                new AnnotationPoint(entry.x1, entry.z1), new AnnotationPoint(entry.x2, entry.z2)
            );
            case "CIRCLE" -> new CircleAnnotationGeometry(
                new AnnotationPoint(entry.x1, entry.z1), entry.radius
            );
            case "RECTANGLE" -> RectangleAnnotationGeometry.between(
                new AnnotationPoint(entry.x1, entry.z1), new AnnotationPoint(entry.x2, entry.z2)
            );
            case "FREEHAND" -> new FreehandAnnotationGeometry(toPoints(entry.points));
            default -> throw new IllegalArgumentException("unknown annotation geometry " + entry.geometryType);
        };
        return new Annotation(
            UUID.fromString(entry.id),
            DimensionId.parse(entry.dimensionId),
            geometry,
            new AnnotationStyle(entry.colorArgb),
            entry.label,
            AnnotationPersistence.PERSISTENT,
            entry.createdAtEpochMs
        );
    }

    private static List<AnnotationPoint> toPoints(final List<PointEntry> entries) {
        if (entries == null || entries.size() > FreehandAnnotationGeometry.MAX_POINTS) {
            throw new IllegalArgumentException("invalid freehand point list");
        }
        final List<AnnotationPoint> points = new ArrayList<>(entries.size());
        for (final PointEntry entry : entries) {
            if (entry == null) {
                throw new IllegalArgumentException("null freehand point");
            }
            points.add(new AnnotationPoint(entry.x, entry.z));
        }
        return points;
    }

    private static Entry toEntry(final Annotation annotation) {
        final Entry entry = new Entry();
        entry.id = annotation.id().toString();
        entry.dimensionId = annotation.dimension().toString();
        entry.colorArgb = annotation.style().colorArgb();
        entry.label = annotation.label();
        entry.createdAtEpochMs = annotation.createdAtEpochMs();
        final AnnotationGeometry geometry = annotation.geometry();
        if (geometry instanceof final LineAnnotationGeometry line) {
            entry.geometryType = "LINE";
            setEndpoints(entry, line.start(), line.end());
        } else if (geometry instanceof final CircleAnnotationGeometry circle) {
            entry.geometryType = "CIRCLE";
            entry.x1 = circle.center().x();
            entry.z1 = circle.center().z();
            entry.radius = circle.radius();
        } else if (geometry instanceof final RectangleAnnotationGeometry rectangle) {
            entry.geometryType = "RECTANGLE";
            setEndpoints(entry, rectangle.min(), rectangle.max());
        } else if (geometry instanceof final FreehandAnnotationGeometry freehand) {
            entry.geometryType = "FREEHAND";
            entry.points = new ArrayList<>(freehand.points().size());
            for (final AnnotationPoint point : freehand.points()) {
                final PointEntry savedPoint = new PointEntry();
                savedPoint.x = point.x();
                savedPoint.z = point.z();
                entry.points.add(savedPoint);
            }
        } else {
            throw new IllegalArgumentException("unsupported annotation geometry " + geometry.getClass());
        }
        return entry;
    }

    private static void setEndpoints(
        final Entry entry,
        final AnnotationPoint first,
        final AnnotationPoint second
    ) {
        entry.x1 = first.x();
        entry.z1 = first.z();
        entry.x2 = second.x();
        entry.z2 = second.z();
    }

    private static boolean usesFutureSchema(final JsonObject root) {
        final JsonElement schema = root.get("schemaVersion");
        if (schema == null || !schema.isJsonPrimitive() || !schema.getAsJsonPrimitive().isNumber()) {
            throw new JsonParseException("annotation file has no numeric schemaVersion");
        }
        final BigDecimal version = schema.getAsBigDecimal().stripTrailingZeros();
        if (version.scale() > 0) {
            throw new JsonParseException("annotation schemaVersion must be an integer");
        }
        if (version.compareTo(BigDecimal.valueOf(SCHEMA_VERSION)) > 0) {
            return true;
        }
        if (version.compareTo(BigDecimal.ONE) < 0) {
            throw new JsonParseException("unsupported annotation schemaVersion " + version);
        }
        return false;
    }

    private static void move(final Path source, final Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void quarantine(final Path file, final Logger logger) {
        try {
            Files.move(file, file.resolveSibling(file.getFileName() + ".bad"), StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException e) {
            logger.warn("Could not quarantine annotation file {}", file, e);
        }
    }
}
