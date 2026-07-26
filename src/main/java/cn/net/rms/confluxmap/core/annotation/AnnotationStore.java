package cn.net.rms.confluxmap.core.annotation;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/** Main-thread-owned annotations for one world/server identity. */
public final class AnnotationStore {
    private static final int MAX_HISTORY = 100;

    public record State(List<Annotation> annotations, boolean persistenceWritable) {
        public State {
            annotations = annotations == null ? List.of() : List.copyOf(annotations);
        }

        public State(final List<Annotation> annotations) {
            this(annotations, true);
        }
    }

    private final WorldIdentity world;
    private final boolean persistenceWritable;
    private final Map<UUID, Annotation> annotations = new LinkedHashMap<>();
    private final List<Consumer<State>> listeners = new ArrayList<>();
    private final Deque<List<Annotation>> undoHistory = new ArrayDeque<>();
    private final Deque<List<Annotation>> redoHistory = new ArrayDeque<>();
    private long revision;

    public AnnotationStore(final WorldIdentity world, final State initial) {
        this.world = Objects.requireNonNull(world, "world");
        this.persistenceWritable = initial.persistenceWritable();
        for (final Annotation annotation : initial.annotations()) {
            if (annotation != null) {
                annotations.put(annotation.id(), annotation);
            }
        }
    }

    public WorldIdentity world() {
        return world;
    }

    public boolean persistenceWritable() {
        return persistenceWritable;
    }

    public long revision() {
        return revision;
    }

    public List<Annotation> list() {
        return List.copyOf(annotations.values());
    }

    public List<Annotation> list(final DimensionId dimension) {
        final List<Annotation> result = new ArrayList<>();
        for (final Annotation annotation : annotations.values()) {
            if (annotation.dimension().equals(dimension)) {
                result.add(annotation);
            }
        }
        return List.copyOf(result);
    }

    public Optional<Annotation> get(final UUID id) {
        return Optional.ofNullable(annotations.get(id));
    }

    /** Last-created matching annotation wins, matching visual stacking order. */
    public Optional<Annotation> hit(
        final DimensionId dimension,
        final AnnotationPoint point,
        final double tolerance
    ) {
        final List<Annotation> visible = list(dimension);
        for (int index = visible.size() - 1; index >= 0; index--) {
            final Annotation annotation = visible.get(index);
            if (AnnotationGeometryOps.hit(annotation.geometry(), point, tolerance)) {
                return Optional.of(annotation);
            }
        }
        return Optional.empty();
    }

    public List<Annotation> hits(
        final DimensionId dimension,
        final AnnotationPoint point,
        final double tolerance
    ) {
        final List<Annotation> result = new ArrayList<>();
        for (final Annotation annotation : annotations.values()) {
            if (annotation.dimension().equals(dimension)
                && AnnotationGeometryOps.hit(annotation.geometry(), point, tolerance)) {
                result.add(annotation);
            }
        }
        return List.copyOf(result);
    }

    public boolean add(final Annotation annotation) {
        if (!canWrite(annotation) || annotations.containsKey(annotation.id())) {
            return false;
        }
        recordMutation();
        annotations.put(annotation.id(), annotation);
        changed();
        return true;
    }

    public boolean update(final Annotation annotation) {
        if (!canWrite(annotation) || !annotations.containsKey(annotation.id())) {
            return false;
        }
        if (annotation.equals(annotations.get(annotation.id()))) {
            return false;
        }
        recordMutation();
        annotations.put(annotation.id(), annotation);
        changed();
        return true;
    }

    public boolean remove(final UUID id) {
        final Annotation existing = annotations.get(id);
        if (existing == null || !canWrite(existing)) {
            return false;
        }
        recordMutation();
        annotations.remove(id);
        changed();
        return true;
    }

    public int removeAll(final Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        final LinkedHashSet<UUID> removable = new LinkedHashSet<>();
        for (final UUID id : ids) {
            final Annotation annotation = annotations.get(id);
            if (annotation != null && canWrite(annotation)) {
                removable.add(id);
            }
        }
        if (removable.isEmpty()) {
            return 0;
        }
        recordMutation();
        for (final UUID id : removable) {
            annotations.remove(id);
        }
        changed();
        return removable.size();
    }

    public boolean canUndo() {
        return !undoHistory.isEmpty();
    }

    public boolean canRedo() {
        return !redoHistory.isEmpty();
    }

    public boolean undo() {
        if (undoHistory.isEmpty()) {
            return false;
        }
        pushHistory(redoHistory, list());
        restore(undoHistory.removeLast());
        return true;
    }

    public boolean redo() {
        if (redoHistory.isEmpty()) {
            return false;
        }
        pushHistory(undoHistory, list());
        restore(redoHistory.removeLast());
        return true;
    }

    public State state() {
        return new State(list(), persistenceWritable);
    }

    public void addListener(final Consumer<State> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    private boolean canWrite(final Annotation annotation) {
        return persistenceWritable || annotation.persistence() == AnnotationPersistence.TRANSIENT;
    }

    private void recordMutation() {
        pushHistory(undoHistory, list());
        redoHistory.clear();
    }

    private static void pushHistory(
        final Deque<List<Annotation>> history,
        final List<Annotation> snapshot
    ) {
        history.addLast(snapshot);
        if (history.size() > MAX_HISTORY) {
            history.removeFirst();
        }
    }

    private void restore(final List<Annotation> snapshot) {
        annotations.clear();
        for (final Annotation annotation : snapshot) {
            annotations.put(annotation.id(), annotation);
        }
        changed();
    }

    private void changed() {
        revision++;
        final State snapshot = state();
        for (final Consumer<State> listener : List.copyOf(listeners)) {
            listener.accept(snapshot);
        }
    }
}
