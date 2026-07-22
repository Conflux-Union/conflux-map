package cn.net.rms.confluxmap.core.waypoint;

import cn.net.rms.confluxmap.core.model.WorldIdentity;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * In-memory waypoint collection for one world/server identity. Thread-confined
 * to the main thread: every mutating call and every listener callback happens
 * there. {@link #list()} always returns independent {@link Waypoint#copy()}
 * instances (never the live entries) so a caller on another thread - notably
 * {@link WaypointService}'s IO-thread save - can safely read a snapshot while
 * the main thread keeps mutating the store.
 */
public final class WaypointStore {
    public enum MutationResult {
        APPLIED,
        NO_CHANGE,
        INVALID_NAME,
        SET_NOT_FOUND,
        SET_ALREADY_EXISTS,
        WAYPOINT_NOT_FOUND,
        INVALID_REQUEST,
        DEFAULT_SET_PROTECTED,
        READ_ONLY
    }

    /** Result of deleting one set and all waypoints owned by it. */
    public record DeleteSetResult(MutationResult result, int deletedWaypoints) {
    }

    /** Result of one all-or-nothing multi-waypoint set assignment. */
    public record BatchMoveResult(MutationResult result, int movedWaypoints) {
    }

    /** Immutable-at-the-boundary persistence state; waypoint accessors return fresh copies. */
    public static final class State {
        private final List<WaypointSet> sets;
        private final List<Waypoint> waypoints;
        private final boolean persistenceWritable;

        public State(final List<WaypointSet> sets, final List<Waypoint> waypoints) {
            this(sets, waypoints, true);
        }

        State(
            final List<WaypointSet> sets,
            final List<Waypoint> waypoints,
            final boolean persistenceWritable
        ) {
            final Map<String, WaypointSet> normalizedSets = new LinkedHashMap<>();
            normalizedSets.put(WaypointSet.DEFAULT_NAME, WaypointSet.DEFAULT);
            if (sets != null) {
                for (final WaypointSet set : sets) {
                    if (set != null) {
                        normalizedSets.putIfAbsent(set.name(), set);
                    }
                }
            }
            final List<Waypoint> waypointCopies = new ArrayList<>();
            if (waypoints != null) {
                for (final Waypoint waypoint : waypoints) {
                    if (waypoint == null) {
                        continue;
                    }
                    final Waypoint copy = waypoint.copy();
                    waypointCopies.add(copy);
                    if (!copy.group.isEmpty()) {
                        normalizedSets.putIfAbsent(copy.group, new WaypointSet(copy.group));
                    }
                }
            }
            this.sets = List.copyOf(normalizedSets.values());
            this.waypoints = List.copyOf(waypointCopies);
            this.persistenceWritable = persistenceWritable;
        }

        public List<WaypointSet> sets() {
            return sets;
        }

        public List<Waypoint> waypoints() {
            final List<Waypoint> copies = new ArrayList<>(waypoints.size());
            for (final Waypoint waypoint : waypoints) {
                copies.add(waypoint.copy());
            }
            return copies;
        }

        public boolean persistenceWritable() {
            return persistenceWritable;
        }
    }

    private final WorldIdentity world;
    private final Map<UUID, Waypoint> byId = new LinkedHashMap<>();
    private final Map<String, WaypointSet> setsByName = new LinkedHashMap<>();
    private final List<Consumer<List<Waypoint>>> listeners = new ArrayList<>();
    private final boolean persistenceWritable;
    private long revision;

    public WaypointStore(final WorldIdentity world, final List<Waypoint> initial) {
        this(world, new State(List.of(), initial));
    }

    public WaypointStore(final WorldIdentity world, final State initial) {
        this.world = world;
        this.persistenceWritable = initial.persistenceWritable();
        for (final WaypointSet set : initial.sets()) {
            setsByName.put(set.name(), set);
        }
        for (final Waypoint w : initial.waypoints()) {
            byId.put(w.id, w.copy());
        }
    }

    public WorldIdentity world() {
        return world;
    }

    /** Independent copies, in insertion/load order. Safe to read from any thread. */
    public List<Waypoint> list() {
        final List<Waypoint> out = new ArrayList<>(byId.size());
        for (final Waypoint w : byId.values()) {
            out.add(w.copy());
        }
        return out;
    }

    /** Default collection first, followed by custom collections in creation/load order. */
    public List<WaypointSet> sets() {
        return List.copyOf(setsByName.values());
    }

    /** One consistent persistence snapshot containing empty sets as well as member waypoints. */
    public State state() {
        return new State(sets(), list(), persistenceWritable);
    }

    public boolean persistenceWritable() {
        return persistenceWritable;
    }

    /** Monotonic in-memory mutation counter for UI snapshot invalidation. */
    public long revision() {
        return revision;
    }

    /** Immutable projection used by {@link WaypointDataView}. */
    WaypointDataView.Snapshot dataSnapshot() {
        final List<WaypointDataView.Entry> entries = new ArrayList<>(byId.size());
        for (final Waypoint waypoint : byId.values()) {
            entries.add(WaypointDataView.Entry.from(waypoint));
        }
        return new WaypointDataView.Snapshot(world, sets(), entries);
    }

    public void add(final Waypoint waypoint) {
        if (!persistenceWritable) {
            return;
        }
        final Waypoint copy = waypoint.copy();
        ensureSetForGroup(copy.group);
        byId.put(copy.id, copy);
        notifyListeners();
    }

    /**
     * Replaces the entry with the same {@link Waypoint#id}; a no-op if it isn't present.
     * Never creates sets: an edit-form copy holding a group that was renamed or deleted
     * while the form was open keeps the live entry's current group instead of
     * resurrecting the stale set.
     */
    public void update(final Waypoint waypoint) {
        if (!persistenceWritable) {
            return;
        }
        final Waypoint existing = byId.get(waypoint.id);
        if (existing == null) {
            return;
        }
        final Waypoint copy = waypoint.copy();
        if (!copy.group.isEmpty() && !setsByName.containsKey(copy.group)) {
            copy.group = existing.group;
        }
        byId.put(copy.id, copy);
        notifyListeners();
    }

    public void remove(final UUID id) {
        if (persistenceWritable && byId.remove(id) != null) {
            notifyListeners();
        }
    }

    public int size() {
        return byId.size();
    }

    /** Number of waypoints currently assigned to a set; useful for destructive-action previews. */
    public int waypointCount(final String setName) {
        int count = 0;
        for (final Waypoint waypoint : byId.values()) {
            if (waypoint.group.equals(setName)) {
                count++;
            }
        }
        return count;
    }

    public MutationResult createSet(final String requestedName) {
        if (!persistenceWritable) {
            return MutationResult.READ_ONLY;
        }
        final String name = WaypointSet.normalizeCustomName(requestedName);
        if (name == null) {
            return MutationResult.INVALID_NAME;
        }
        if (setsByName.containsKey(name)) {
            return MutationResult.SET_ALREADY_EXISTS;
        }
        setsByName.put(name, new WaypointSet(name));
        notifyListeners();
        return MutationResult.APPLIED;
    }

    public MutationResult renameSet(final String currentName, final String requestedName) {
        if (!persistenceWritable) {
            return MutationResult.READ_ONLY;
        }
        if (WaypointSet.DEFAULT_NAME.equals(currentName)) {
            return MutationResult.DEFAULT_SET_PROTECTED;
        }
        if (currentName == null || !setsByName.containsKey(currentName)) {
            return MutationResult.SET_NOT_FOUND;
        }
        final String newName = WaypointSet.normalizeCustomName(requestedName);
        if (newName == null) {
            return MutationResult.INVALID_NAME;
        }
        if (currentName.equals(newName)) {
            return MutationResult.NO_CHANGE;
        }
        if (setsByName.containsKey(newName)) {
            return MutationResult.SET_ALREADY_EXISTS;
        }

        final Map<String, WaypointSet> renamed = new LinkedHashMap<>();
        for (final WaypointSet set : setsByName.values()) {
            if (set.name().equals(currentName)) {
                renamed.put(newName, new WaypointSet(newName));
            } else {
                renamed.put(set.name(), set);
            }
        }
        setsByName.clear();
        setsByName.putAll(renamed);
        for (final Waypoint waypoint : byId.values()) {
            if (waypoint.group.equals(currentName)) {
                waypoint.group = newName;
            }
        }
        notifyListeners();
        return MutationResult.APPLIED;
    }

    /** Deletes a custom collection and every waypoint assigned to it in one notification. */
    public DeleteSetResult deleteSet(final String name) {
        if (!persistenceWritable) {
            return new DeleteSetResult(MutationResult.READ_ONLY, 0);
        }
        if (WaypointSet.DEFAULT_NAME.equals(name)) {
            return new DeleteSetResult(MutationResult.DEFAULT_SET_PROTECTED, 0);
        }
        if (name == null || !setsByName.containsKey(name)) {
            return new DeleteSetResult(MutationResult.SET_NOT_FOUND, 0);
        }
        setsByName.remove(name);
        final int oldSize = byId.size();
        byId.values().removeIf(waypoint -> waypoint.group.equals(name));
        final int deletedWaypoints = oldSize - byId.size();
        notifyListeners();
        return new DeleteSetResult(MutationResult.APPLIED, deletedWaypoints);
    }

    public MutationResult assignToSet(final UUID waypointId, final String setName) {
        if (!persistenceWritable) {
            return MutationResult.READ_ONLY;
        }
        if (waypointId == null) {
            return MutationResult.INVALID_REQUEST;
        }
        return moveToSet(List.of(waypointId), setName).result();
    }

    /**
     * Moves all requested waypoints together. A missing target set or waypoint
     * rejects the whole request before any live entry is changed.
     */
    public BatchMoveResult moveToSet(final Collection<UUID> waypointIds, final String setName) {
        if (!persistenceWritable) {
            return new BatchMoveResult(MutationResult.READ_ONLY, 0);
        }
        if (waypointIds == null) {
            return new BatchMoveResult(MutationResult.INVALID_REQUEST, 0);
        }
        if (setName == null || !setsByName.containsKey(setName)) {
            return new BatchMoveResult(MutationResult.SET_NOT_FOUND, 0);
        }
        final LinkedHashSet<UUID> uniqueIds = new LinkedHashSet<>(waypointIds);
        if (uniqueIds.contains(null)) {
            return new BatchMoveResult(MutationResult.INVALID_REQUEST, 0);
        }
        for (final UUID waypointId : uniqueIds) {
            if (!byId.containsKey(waypointId)) {
                return new BatchMoveResult(MutationResult.WAYPOINT_NOT_FOUND, 0);
            }
        }

        int moved = 0;
        for (final UUID waypointId : uniqueIds) {
            final Waypoint waypoint = byId.get(waypointId);
            if (!waypoint.group.equals(setName)) {
                waypoint.group = setName;
                moved++;
            }
        }
        if (moved == 0) {
            return new BatchMoveResult(MutationResult.NO_CHANGE, 0);
        }
        notifyListeners();
        return new BatchMoveResult(MutationResult.APPLIED, moved);
    }

    /** Fires after every mutation (add/update/remove). Does not fire on registration. */
    public void addListener(final Consumer<List<Waypoint>> listener) {
        listeners.add(listener);
    }

    private void ensureSetForGroup(final String group) {
        if (!group.isEmpty()) {
            setsByName.putIfAbsent(group, new WaypointSet(group));
        }
    }

    private void notifyListeners() {
        revision++;
        final List<Waypoint> snapshot = list();
        // Iterate a copy so a listener registering another listener mid-notify cannot CME.
        for (final Consumer<List<Waypoint>> listener : List.copyOf(listeners)) {
            listener.accept(snapshot);
        }
    }
}
