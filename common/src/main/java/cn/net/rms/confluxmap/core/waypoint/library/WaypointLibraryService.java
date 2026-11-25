package cn.net.rms.confluxmap.core.waypoint.library;

import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.store.WorldStorageMigration;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import org.apache.logging.log4j.Logger;

/** World-bound private metadata; malformed/future documents are preserved read-only. */
public final class WaypointLibraryService {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting()
        .registerTypeAdapter(WaypointMetadata.class, WaypointJson.readUsing(MetadataData.class, MetadataData::toMetadata))
        .registerTypeAdapter(WaypointCollection.class, WaypointJson.readUsing(CollectionData.class, CollectionData::toCollection))
        .create();

    private static final class Document {
        int schemaVersion = 1;
        Map<UUID, WaypointMetadata> waypoints = new LinkedHashMap<>();
        List<WaypointCollection> collections = List.of();
        Set<UUID> sharedStars = Set.of();
        Map<UUID, WaypointMetadata> sharedMetadata = new LinkedHashMap<>();
    }

    private static final class MetadataData {
        String note;
        Set<String> tags, resourceItems;
        boolean starred;
        Set<UUID> collections;
        Map<String, Integer> resourceAmounts;

        WaypointMetadata toMetadata() {
            return new WaypointMetadata(note, tags, starred, collections, resourceItems, resourceAmounts);
        }
    }

    private static final class CollectionData {
        UUID id;
        String name, icon;
        int colorArgb;

        WaypointCollection toCollection() {
            return new WaypointCollection(id, name, colorArgb, icon);
        }
    }

    private static final class State {
        final WorldIdentity world;
        final WaypointLibrary library = new WaypointLibrary();
        boolean readOnly;
        volatile boolean saveFailed;

        State(final WorldIdentity world) {
            this.world = world;
        }
    }

    private final Path root;
    private final MapExecutors executors;
    private final Logger logger;
    private State state;

    public WaypointLibraryService(final Path root, final MapExecutors executors, final Logger logger) {
        this.root = root;
        this.executors = executors;
        this.logger = logger;
    }

    public WorldIdentity world() {
        return state == null ? null : state.world;
    }

    public boolean writable() {
        return state != null && !state.readOnly && !state.saveFailed;
    }

    public WaypointLibrary library() {
        if (state == null) {
            throw new IllegalStateException("No active world");
        }
        return state.library;
    }

    public void onSessionChanged(final SessionGuard.Session session) {
        final WorldIdentity next = session.active() ? session.world() : null;
        if (java.util.Objects.equals(next, world())) {
            return;
        }
        flush();
        state = next == null ? null : load(next);
    }

    private State load(final WorldIdentity world) {
        final State loaded = new State(world);
        final Path file = WorldStorageMigration.file(root, world, ".json", logger);
        if (!Files.exists(file)) {
            return loaded;
        }
        try {
            final JsonObject json = GSON.fromJson(Files.readString(file), JsonObject.class);
            if (!json.has("schemaVersion") || !json.get("schemaVersion").isJsonPrimitive()
                || !json.getAsJsonPrimitive("schemaVersion").isNumber()
                || json.get("schemaVersion").getAsBigDecimal().compareTo(java.math.BigDecimal.ONE) != 0
                || !json.has("waypoints") || !json.has("collections")) {
                throw new IllegalArgumentException("Unsupported library schema");
            }
            final Document document = GSON.fromJson(json, Document.class);
            if (document.waypoints == null || document.collections == null) {
                throw new IllegalArgumentException("Missing library data");
            }
            for (final WaypointCollection collection : document.collections) {
                loaded.library.putCollection(collection);
            }
            document.waypoints.forEach(loaded.library::put);
            if (document.sharedMetadata != null) {
                document.sharedMetadata.forEach((id, extra) -> loaded.library.put(id, true, extra));
            }
            if (document.sharedStars != null) {
                document.sharedStars.forEach(id -> loaded.library.setStarred(id, true, true));
            }
        } catch (final IOException | RuntimeException e) {
            loaded.readOnly = true;
            logger.error("Private waypoint library {} is read-only; original file preserved", file, e);
        }
        return loaded;
    }

    /** Call after a successful main-thread aggregate mutation. IO snapshots are immutable. */
    public void save() {
        if (!writable()) {
            return;
        }
        final State captured = state;
        final Document snapshot = new Document();
        snapshot.waypoints = captured.library.metadata();
        snapshot.collections = captured.library.collections();
        snapshot.sharedStars = captured.library.sharedStars();
        snapshot.sharedMetadata = captured.library.sharedMetadata();
        final Path file = root.resolve(captured.world.serverId()).resolve(captured.world.worldId() + ".json");
        executors.io().execute(() -> {
            try {
                Files.createDirectories(file.getParent());
                final Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
                Files.writeString(temporary, GSON.toJson(snapshot), StandardCharsets.UTF_8);
                try {
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (final AtomicMoveNotSupportedException e) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (final IOException e) {
                captured.saveFailed = true;
                logger.error("Failed to save private waypoint library {}", file, e);
            }
        });
    }

    /** Only authoritative local-store mutations trigger cleanup, never render/sync omissions. */
    public void onWaypointsChanged(final List<Waypoint> waypoints) {
        if (!writable()) {
            return;
        }
        final Set<UUID> existing = waypoints.stream().map(waypoint -> waypoint.id).collect(Collectors.toSet());
        if (!existing.containsAll(state.library.metadata().keySet())) {
            state.library.retainWaypoints(existing);
            save();
        }
    }

    public void flush() {
        if (state == null) {
            return;
        }
        boolean interrupted = false;
        final var barrier = executors.io().submit(() -> {});
        while (true) {
            try {
                barrier.get();
                break;
            } catch (final InterruptedException e) {
                interrupted = true;
            } catch (final ExecutionException e) {
                throw new IllegalStateException("Library IO barrier failed", e);
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
