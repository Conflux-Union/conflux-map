package cn.net.rms.confluxmap.core.waypoint;

import cn.net.rms.confluxmap.core.model.WorldIdentity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    private final WorldIdentity world;
    private final Map<UUID, Waypoint> byId = new LinkedHashMap<>();
    private final List<Consumer<List<Waypoint>>> listeners = new ArrayList<>();

    public WaypointStore(final WorldIdentity world, final List<Waypoint> initial) {
        this.world = world;
        for (final Waypoint w : initial) {
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

    public void add(final Waypoint waypoint) {
        byId.put(waypoint.id, waypoint.copy());
        notifyListeners();
    }

    /** Replaces the entry with the same {@link Waypoint#id}; a no-op if it isn't present. */
    public void update(final Waypoint waypoint) {
        if (!byId.containsKey(waypoint.id)) {
            return;
        }
        byId.put(waypoint.id, waypoint.copy());
        notifyListeners();
    }

    public void remove(final UUID id) {
        if (byId.remove(id) != null) {
            notifyListeners();
        }
    }

    public int size() {
        return byId.size();
    }

    /** Fires after every mutation (add/update/remove). Does not fire on registration. */
    public void addListener(final Consumer<List<Waypoint>> listener) {
        listeners.add(listener);
    }

    private void notifyListeners() {
        final List<Waypoint> snapshot = list();
        for (final Consumer<List<Waypoint>> listener : listeners) {
            listener.accept(snapshot);
        }
    }
}
