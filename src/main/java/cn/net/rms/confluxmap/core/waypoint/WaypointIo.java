package cn.net.rms.confluxmap.core.waypoint;

import cn.net.rms.confluxmap.core.model.DimensionId;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
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
    public static final int SCHEMA_VERSION = 1;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private WaypointIo() {
    }

    /** On-disk document shape. */
    private static final class FileShape {
        int schemaVersion = SCHEMA_VERSION;
        List<Entry> waypoints = new ArrayList<>();
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
        if (!Files.exists(file)) {
            return Collections.emptyList();
        }
        try {
            final String json = Files.readString(file, StandardCharsets.UTF_8);
            final FileShape shape = GSON.fromJson(json, FileShape.class);
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
            return result;
        } catch (final IOException | RuntimeException e) {
            logger.warn("Waypoint file {} unreadable ({}), quarantining and starting empty", file, e.toString());
            quarantine(file, logger);
            return Collections.emptyList();
        }
    }

    public static void save(final Path file, final List<Waypoint> waypoints, final Logger logger) {
        try {
            Files.createDirectories(file.getParent());
            final FileShape shape = new FileShape();
            for (final Waypoint waypoint : waypoints) {
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
