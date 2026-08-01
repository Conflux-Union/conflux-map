package cn.net.rms.confluxmap.core.annotation;

import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.store.WorldStorageMigration;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.apache.logging.log4j.Logger;

/** Owns the current world's private annotation store and persistence lifecycle. */
public final class AnnotationService {
    private final Path baseDir;
    private final MapExecutors executors;
    private final Logger logger;

    private volatile AnnotationStore current;

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
            saveOutgoing(current.world(), current.state());
        }
        if (newWorld == null) {
            current = null;
            return;
        }
        final AnnotationStore.State loaded = AnnotationIo.load(fileFor(newWorld), logger);
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
        final Future<?> save = executors.io().submit(() -> AnnotationIo.save(file, state, logger));
        boolean interrupted = false;
        while (true) {
            try {
                save.get();
                break;
            } catch (final InterruptedException e) {
                interrupted = true;
            } catch (final ExecutionException e) {
                logger.error("Failed to save outgoing annotations for {}", world, e.getCause());
                break;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private Path fileFor(final WorldIdentity world) {
        return WorldStorageMigration.file(baseDir, world, ".json", logger);
    }
}
