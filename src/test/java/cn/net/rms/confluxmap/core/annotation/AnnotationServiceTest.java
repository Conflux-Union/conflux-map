package cn.net.rms.confluxmap.core.annotation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import java.nio.file.Path;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnnotationServiceTest {
    private static final Logger LOGGER = LogManager.getLogger("AnnotationServiceTest");

    @Test
    void dimensionChangeKeepsTemporaryAnnotationsButReconnectLoadsOnlyPersistentOnes(
        @TempDir final Path tempDir
    ) {
        final WorldIdentity world = new WorldIdentity("server", "world");
        final MapExecutors executors = new MapExecutors();
        try {
            final AnnotationService service = new AnnotationService(tempDir, executors, LOGGER);
            service.onSessionChanged(new SessionGuard.Session(1L, world, DimensionId.OVERWORLD));
            final Annotation persistent = annotation(DimensionId.OVERWORLD, AnnotationPersistence.PERSISTENT);
            final Annotation temporary = annotation(DimensionId.NETHER, AnnotationPersistence.TRANSIENT);
            assertTrue(service.current().add(persistent));
            assertTrue(service.current().add(temporary));

            service.onSessionChanged(new SessionGuard.Session(2L, world, DimensionId.NETHER));
            assertEquals(2, service.list().size());
            assertEquals(temporary, service.current().list(DimensionId.NETHER).get(0));

            service.onSessionChanged(SessionGuard.Session.NONE);
            service.onSessionChanged(new SessionGuard.Session(3L, world, DimensionId.OVERWORLD));

            assertEquals(java.util.List.of(persistent), service.list());
        } finally {
            executors.shutdown(1000L);
        }
    }

    @Test
    void worldIdentitiesUseSeparateFiles(@TempDir final Path tempDir) {
        final WorldIdentity first = new WorldIdentity("server", "first");
        final WorldIdentity second = new WorldIdentity("server", "second");
        final MapExecutors executors = new MapExecutors();
        try {
            final AnnotationService service = new AnnotationService(tempDir, executors, LOGGER);
            service.onSessionChanged(new SessionGuard.Session(1L, first, DimensionId.OVERWORLD));
            service.current().add(annotation(DimensionId.OVERWORLD, AnnotationPersistence.PERSISTENT));

            service.onSessionChanged(new SessionGuard.Session(2L, second, DimensionId.OVERWORLD));
            assertTrue(service.list().isEmpty());

            service.onSessionChanged(new SessionGuard.Session(3L, first, DimensionId.OVERWORLD));
            assertEquals(1, service.list().size());
        } finally {
            executors.shutdown(1000L);
        }
    }

    private static Annotation annotation(
        final DimensionId dimension,
        final AnnotationPersistence persistence
    ) {
        return new Annotation(
            UUID.randomUUID(),
            dimension,
            new LineAnnotationGeometry(new AnnotationPoint(0, 0), new AnnotationPoint(10, 10)),
            new AnnotationStyle(0xFF3498DB),
            "Test",
            persistence,
            123L
        );
    }
}
