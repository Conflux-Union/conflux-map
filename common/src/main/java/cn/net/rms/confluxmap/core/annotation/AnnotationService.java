package cn.net.rms.confluxmap.core.annotation;

import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.store.WorldStorageMigration;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.Logger;

/** Owns the current world's private annotation store and persistence lifecycle. */
public final class AnnotationService {
    private final Path baseDir;
    private final MapExecutors executors;
    private final Logger logger;

    private volatile AnnotationStore current;
    /** Keeps a just-written outgoing state available while its IO task is still queued. */
    private final Map<WorldIdentity, AnnotationStore.State> pendingOutgoing = new ConcurrentHashMap<>();

    public AnnotationService(final Path baseDir, final MapExecutors executors, final Logger logger) {
        this.baseDir = baseDir;
        this.executors = executors;
        this.logger = logger;
    }

    /** Dimension changes retain the same store; a world identity change flushes and reloads it. */
    public void onSessionChanged(final SessionGuard.Session session) {
        final WorldIdentity newWorld = session.active() ? session.world() : null;
        final WorldIdentity oldWorld = current == null ? null : current.world();
        if (Objects.equals(newWorld, oldWorld)) {
            return;
        }
        if (current != null) {
            final WorldIdentity outgoingWorld = current.world();
            final AnnotationStore.State outgoingState = persistentState(current.state());
            pendingOutgoing.put(outgoingWorld, outgoingState);
            saveOutgoing(outgoingWorld, outgoingState);
        }
        if (newWorld == null) {
            current = null;
            return;
        }
        final AnnotationStore.State pendingState = pendingOutgoing.remove(newWorld);
        final AnnotationStore.State loaded = pendingState != null
            ? pendingState
            : AnnotationIo.load(fileFor(newWorld), logger);
        final AnnotationStore store = new AnnotationStore(newWorld, loaded);
        store.addListener(state -> saveAsync(newWorld, state));
        current = store;
    }

    public AnnotationStore current() {
        return current;
    }

    public List<Annotation> list() {
        final AnnotationStore store = current;
        return store == null ? List.of() : store.list();
    }

    private void saveAsync(final WorldIdentity world, final AnnotationStore.State state) {
        final Path file = fileFor(world);
        executors.io().execute(() -> AnnotationIo.save(file, state, logger));
    }

    private void saveOutgoing(final WorldIdentity world, final AnnotationStore.State state) {
        final Path file = fileFor(world);
        try {
            executors.io().execute(() -> {
                try {
                    AnnotationIo.save(file, state, logger);
                } catch (final RuntimeException error) {
                    logger.error("Failed to save outgoing annotations for {}", world, error);
                } finally {
                    pendingOutgoing.remove(world, state);
                }
            });
        } catch (final RuntimeException error) {
            pendingOutgoing.remove(world, state);
            logger.error("Failed to schedule outgoing annotation save for {}", world, error);
        }
    }

    int pendingOutgoingCount() {
        return pendingOutgoing.size();
    }

    private Path fileFor(final WorldIdentity world) {
        return WorldStorageMigration.file(baseDir, world, ".json", logger);
    }

    private static AnnotationStore.State persistentState(final AnnotationStore.State state) {
        return new AnnotationStore.State(
            state.annotations().stream()
                .filter(annotation -> annotation.persistence() == AnnotationPersistence.PERSISTENT)
                .toList(),
            state.persistenceWritable()
        );
    }
}
