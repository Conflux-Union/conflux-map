package cn.net.rms.confluxmap.core.waypoint;

import cn.net.rms.confluxmap.core.model.DimensionId;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;
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

/**
 * Loads and saves one world/server's waypoint collection as JSON at
 * {@code <gameDir>/confluxmap/waypoints/<serverId>/<worldId>.json}. Mirrors
 * {@link cn.net.rms.confluxmap.core.config.ConfigIo}'s pattern: atomic
 * writes (tmp + move) and a corrupt file is quarantined as {@code *.bad}
 * rather than wedging the mod.
 *
 * <p>Deliberately does not serialize {@link Waypoint} with Gson's default
 * reflective adapter - the on-disk shape is an explicit, independent DTO
 * ({@link Entry}) so the file format doesn't silently drift if the domain
 * class's internal shape changes, and so {@link DimensionId}'s private
 * constructor and {@link UUID}/enum fields never need any Gson magic.
 */
public final class WaypointIo {
    public static final int SCHEMA_VERSION = 2;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private WaypointIo() {
    }

    /** On-disk document shape. */
    private static final class FileShape {
        int schemaVersion = SCHEMA_VERSION;
        List<SetEntry> sets = new ArrayList<>();
        List<Entry> waypoints = new ArrayList<>();
    }

    private static final class SetEntry {
        String name;
    }

    /** On-disk shape of one waypoint. Field types are all Gson-trivial (String/primitive). */
    private static final class Entry {
        String id;
        String name;
        String dimensionId;
        double x;
        double y;
        double z;
        int colorArgb;
        String group;
        boolean visible;
        String type;
        long createdAtEpochMs;
    }

    /** Never throws; a missing or corrupt file yields an empty list (corrupt ones are quarantined first). */
    public static List<Waypoint> load(final Path file, final Logger logger) {
        return loadState(file, logger).waypoints();
    }

    /** Loads collection metadata and waypoints; schema v1 files are upgraded in memory. */
    public static WaypointStore.State loadState(final Path file, final Logger logger) {
        if (!Files.exists(file)) {
            return new WaypointStore.State(List.of(), List.of());
        }
        try {
            final String json = Files.readString(file, StandardCharsets.UTF_8);
            final JsonElement parsed = new JsonParser().parse(json);
            if (!parsed.isJsonObject()) {
                throw new JsonParseException("waypoint file root must be an object");
            }
            final JsonObject root = parsed.getAsJsonObject();
            if (usesFutureSchema(root)) {
                logger.warn("Waypoint file {} uses a future schema; local waypoints are read-only", file);
                return new WaypointStore.State(List.of(), List.of(), false);
            }
            final FileShape shape = GSON.fromJson(root, FileShape.class);
            if (shape == null || shape.waypoints == null) {
                throw new JsonParseException("empty waypoint file");
            }
            final List<Waypoint> result = new ArrayList<>(shape.waypoints.size());
            for (final Entry entry : shape.waypoints) {
                final Waypoint waypoint = toWaypoint(entry, logger);
                if (waypoint != null) {
                    result.add(waypoint);
                }
            }
            final List<WaypointSet> sets = new ArrayList<>();
            if (shape.sets != null) {
                for (final SetEntry entry : shape.sets) {
                    if (entry != null && entry.name != null && !entry.name.isEmpty()) {
                        sets.add(new WaypointSet(entry.name));
                    }
                }
            }
            return new WaypointStore.State(sets, result);
        } catch (final IOException | RuntimeException e) {
            logger.warn("Waypoint file {} unreadable ({}), quarantining and starting empty", file, e.toString());
            quarantine(file, logger);
            return new WaypointStore.State(List.of(), List.of());
        }
    }

    public static void save(final Path file, final List<Waypoint> waypoints, final Logger logger) {
        save(file, new WaypointStore.State(List.of(), waypoints), logger);
    }

    public static void save(final Path file, final WaypointStore.State state, final Logger logger) {
        if (!state.persistenceWritable()) {
            logger.warn("Refusing to overwrite read-only future-schema waypoint file {}", file);
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            final FileShape shape = new FileShape();
            for (final WaypointSet set : state.sets()) {
                if (!set.isDefault()) {
                    final SetEntry entry = new SetEntry();
                    entry.name = set.name();
                    shape.sets.add(entry);
                }
            }
            for (final Waypoint waypoint : state.waypoints()) {
                shape.waypoints.add(toEntry(waypoint));
            }
            final Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(shape), StandardCharsets.UTF_8);
            move(tmp, file);
        } catch (final IOException e) {
            logger.error("Failed to save waypoints to {}", file, e);
        }
    }

    private static Waypoint toWaypoint(final Entry entry, final Logger logger) {
        if (entry.id == null || entry.name == null || entry.dimensionId == null) {
            return null;
        }
        final UUID id;
        try {
            id = UUID.fromString(entry.id);
        } catch (final IllegalArgumentException e) {
            logger.warn("Dropping waypoint entry with invalid uuid {}", entry.id);
            return null;
        }
        Waypoint.Type type;
        try {
            type = entry.type == null ? Waypoint.Type.NORMAL : Waypoint.Type.valueOf(entry.type);
        } catch (final IllegalArgumentException e) {
            type = Waypoint.Type.NORMAL;
        }
        return new Waypoint(
            id, entry.name, DimensionId.parse(entry.dimensionId),
            entry.x, entry.y, entry.z, entry.colorArgb,
            entry.group == null ? "" : entry.group, entry.visible, type, entry.createdAtEpochMs
        );
    }

    private static boolean usesFutureSchema(final JsonObject root) {
        final JsonElement schema = root.get("schemaVersion");
        if (schema == null
            || !schema.isJsonPrimitive()
            || !schema.getAsJsonPrimitive().isNumber()) {
            throw new JsonParseException("waypoint file has no numeric schemaVersion");
        }
        final BigDecimal version;
        try {
            version = schema.getAsBigDecimal().stripTrailingZeros();
        } catch (final NumberFormatException e) {
            throw new JsonParseException("waypoint file has an invalid schemaVersion");
        }
        if (version.scale() > 0) {
            throw new JsonParseException("waypoint schemaVersion must be an integer");
        }
        if (version.compareTo(BigDecimal.valueOf(SCHEMA_VERSION)) > 0) {
            return true;
        }
        if (version.compareTo(BigDecimal.ONE) < 0) {
            throw new JsonParseException("unsupported waypoint schemaVersion " + version);
        }
        return false;
    }

    private static Entry toEntry(final Waypoint waypoint) {
        final Entry entry = new Entry();
        entry.id = waypoint.id.toString();
        entry.name = waypoint.name;
        entry.dimensionId = waypoint.dimensionId.toString();
        entry.x = waypoint.x;
        entry.y = waypoint.y;
        entry.z = waypoint.z;
        entry.colorArgb = waypoint.colorArgb;
        entry.group = waypoint.group;
        entry.visible = waypoint.visible;
        entry.type = waypoint.type.name();
        entry.createdAtEpochMs = waypoint.createdAtEpochMs;
        return entry;
    }

    private static void move(final Path tmp, final Path file) throws IOException {
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void quarantine(final Path file, final Logger logger) {
        try {
            Files.move(file, file.resolveSibling(file.getFileName() + ".bad"), StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException e) {
            logger.warn("Could not quarantine {}", file, e);
        }
    }
}
