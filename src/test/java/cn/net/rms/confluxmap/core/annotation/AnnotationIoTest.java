package cn.net.rms.confluxmap.core.annotation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnnotationIoTest {
    private static final Logger LOGGER = LogManager.getLogger("AnnotationIoTest");

    @Test
    void roundTripPreservesEveryPersistentGeometryAndSkipsTemporaryEntries(
        @TempDir final Path tempDir
    ) {
        final Path file = tempDir.resolve("annotations.json");
        final List<Annotation> annotations = List.of(
            annotation(new LineAnnotationGeometry(point(1, 2), point(3, 4)), DimensionId.OVERWORLD, "Line", AnnotationPersistence.PERSISTENT),
            annotation(new CircleAnnotationGeometry(point(5, 6), 7), DimensionId.NETHER, "Circle", AnnotationPersistence.PERSISTENT),
            annotation(RectangleAnnotationGeometry.between(point(-2, -3), point(8, 9)), DimensionId.END, "", AnnotationPersistence.PERSISTENT),
            annotation(new FreehandAnnotationGeometry(List.of(point(0, 0), point(1, 2), point(4, 5))), DimensionId.OVERWORLD, "Path", AnnotationPersistence.PERSISTENT),
            annotation(new LineAnnotationGeometry(point(0, 0), point(9, 9)), DimensionId.OVERWORLD, "Temporary", AnnotationPersistence.TRANSIENT)
        );

        AnnotationIo.save(file, new AnnotationStore.State(annotations), LOGGER);
        final AnnotationStore.State loaded = AnnotationIo.load(file, LOGGER);

        assertEquals(4, loaded.annotations().size());
        assertInstanceOf(LineAnnotationGeometry.class, loaded.annotations().get(0).geometry());
        assertInstanceOf(CircleAnnotationGeometry.class, loaded.annotations().get(1).geometry());
        assertInstanceOf(RectangleAnnotationGeometry.class, loaded.annotations().get(2).geometry());
        assertInstanceOf(FreehandAnnotationGeometry.class, loaded.annotations().get(3).geometry());
        assertTrue(loaded.annotations().stream().allMatch(
            annotation -> annotation.persistence() == AnnotationPersistence.PERSISTENT
        ));
        assertFalse(Files.exists(file.resolveSibling("annotations.json.tmp")));
    }

    @Test
    void invalidEntryIsDroppedWithoutLosingValidNeighbors(@TempDir final Path tempDir) throws IOException {
        final Path file = tempDir.resolve("invalid-entry.json");
        Files.writeString(file, """
            {"schemaVersion":1,"annotations":[
              {"id":"00000000-0000-0000-0000-000000000001","dimensionId":"minecraft:overworld","geometryType":"CIRCLE","x1":0,"z1":0,"radius":-1,"colorArgb":-1,"label":"bad"},
              {"id":"00000000-0000-0000-0000-000000000002","dimensionId":"minecraft:overworld","geometryType":"LINE","x1":0,"z1":0,"x2":2,"z2":2,"colorArgb":-1,"label":"kept"}
            ]}
            """);

        final AnnotationStore.State loaded = AnnotationIo.load(file, LOGGER);

        assertEquals(1, loaded.annotations().size());
        assertEquals("kept", loaded.annotations().get(0).label());
        assertTrue(Files.exists(file));
    }

    @Test
    void futureSchemaRemainsByteForByteUntouched(@TempDir final Path tempDir) throws IOException {
        final Path file = tempDir.resolve("future.json");
        final String future = "{\"schemaVersion\":999,\"annotations\":{\"future\":true}}";
        Files.writeString(file, future);

        final AnnotationStore.State loaded = AnnotationIo.load(file, LOGGER);
        AnnotationIo.save(file, loaded, LOGGER);

        assertFalse(loaded.persistenceWritable());
        assertEquals(future, Files.readString(file));
        assertFalse(Files.exists(file.resolveSibling("future.json.bad")));
    }

    private static Annotation annotation(
        final AnnotationGeometry geometry,
        final DimensionId dimension,
        final String label,
        final AnnotationPersistence persistence
    ) {
        return new Annotation(
            UUID.randomUUID(), dimension, geometry, new AnnotationStyle(0xFF336699),
            label, persistence, 123L
        );
    }

    private static AnnotationPoint point(final double x, final double z) {
        return new AnnotationPoint(x, z);
    }
}
