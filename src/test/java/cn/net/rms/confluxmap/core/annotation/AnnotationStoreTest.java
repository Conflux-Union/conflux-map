package cn.net.rms.confluxmap.core.annotation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AnnotationStoreTest {
    private static final WorldIdentity WORLD = new WorldIdentity("server", "world");

    @Test
    void filtersByDimensionAndSelectsLastMatchingShape() {
        final Annotation first = line(UUID.randomUUID(), DimensionId.OVERWORLD, 0, AnnotationPersistence.PERSISTENT);
        final Annotation top = line(UUID.randomUUID(), DimensionId.OVERWORLD, 0.2, AnnotationPersistence.TRANSIENT);
        final Annotation nether = line(UUID.randomUUID(), DimensionId.NETHER, 0, AnnotationPersistence.PERSISTENT);
        final AnnotationStore store = new AnnotationStore(WORLD, new AnnotationStore.State(List.of(first, top, nether)));

        assertEquals(List.of(first, top), store.list(DimensionId.OVERWORLD));
        assertEquals(top.id(), store.hit(
            DimensionId.OVERWORLD, new AnnotationPoint(5, 0.2), 0.25
        ).orElseThrow().id());
    }

    @Test
    void eachAppliedMutationNotifiesOnce() {
        final AnnotationStore store = new AnnotationStore(WORLD, new AnnotationStore.State(List.of()));
        final AtomicInteger notifications = new AtomicInteger();
        store.addListener(ignored -> notifications.incrementAndGet());
        final Annotation annotation = line(
            UUID.randomUUID(), DimensionId.OVERWORLD, 0, AnnotationPersistence.PERSISTENT
        );

        assertTrue(store.add(annotation));
        assertTrue(store.update(annotation.withLabel("Road")));
        assertFalse(store.update(annotation.withLabel("Road")));
        assertTrue(store.remove(annotation.id()));

        assertEquals(3, notifications.get());
        assertEquals(3, store.revision());
    }

    @Test
    void futureSchemaAllowsTemporaryWorkWithoutOverwritingPersistentEntries() {
        final Annotation persistent = line(
            UUID.randomUUID(), DimensionId.OVERWORLD, 0, AnnotationPersistence.PERSISTENT
        );
        final AnnotationStore store = new AnnotationStore(
            WORLD, new AnnotationStore.State(List.of(persistent), false)
        );
        final Annotation temporary = line(
            UUID.randomUUID(), DimensionId.OVERWORLD, 2, AnnotationPersistence.TRANSIENT
        );

        assertTrue(store.add(temporary));
        assertFalse(store.remove(persistent.id()));
        assertFalse(store.update(persistent.withLabel("blocked")));
        assertEquals(List.of(persistent, temporary), store.list());
    }

    @Test
    void eraserStrokeIsUndoneAndRedoneAsOneChange() {
        final Annotation first = line(
            UUID.randomUUID(), DimensionId.OVERWORLD, 0, AnnotationPersistence.PERSISTENT
        );
        final Annotation second = line(
            UUID.randomUUID(), DimensionId.OVERWORLD, 4, AnnotationPersistence.PERSISTENT
        );
        final AnnotationStore store = new AnnotationStore(
            WORLD, new AnnotationStore.State(List.of(first, second))
        );

        assertEquals(List.of(first, second), store.hits(
            DimensionId.OVERWORLD, new AnnotationPoint(5, 2), 2.1
        ));
        assertEquals(2, store.removeAll(List.of(first.id(), second.id())));
        assertTrue(store.list().isEmpty());

        assertTrue(store.undo());
        assertEquals(List.of(first, second), store.list());
        assertFalse(store.canUndo());

        assertTrue(store.redo());
        assertTrue(store.list().isEmpty());
        assertFalse(store.canRedo());
    }

    @Test
    void newChangeClearsRedoHistory() {
        final Annotation first = line(
            UUID.randomUUID(), DimensionId.OVERWORLD, 0, AnnotationPersistence.PERSISTENT
        );
        final Annotation second = line(
            UUID.randomUUID(), DimensionId.OVERWORLD, 2, AnnotationPersistence.PERSISTENT
        );
        final AnnotationStore store = new AnnotationStore(WORLD, new AnnotationStore.State(List.of()));

        assertTrue(store.add(first));
        assertTrue(store.undo());
        assertTrue(store.canRedo());

        assertTrue(store.add(second));
        assertFalse(store.canRedo());
        assertFalse(store.redo());
        assertEquals(List.of(second), store.list());
    }

    private static Annotation line(
        final UUID id,
        final DimensionId dimension,
        final double z,
        final AnnotationPersistence persistence
    ) {
        return new Annotation(
            id, dimension,
            new LineAnnotationGeometry(new AnnotationPoint(0, z), new AnnotationPoint(10, z)),
            new AnnotationStyle(0xFF3498DB), "", persistence, 1L
        );
    }
}
