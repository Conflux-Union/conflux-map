package cn.net.rms.confluxmap.server.shared;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.shared.SharedWaypoint;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.charset.MalformedInputException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.apache.logging.log4j.Logger;

/**
 * Schema-controlled JSON persistence at {@code <worldRoot>/confluxmap/shared_waypoints.json}.
 * Corrupt schema-1 files are quarantined; future schemas are preserved and explicitly rejected.
 */
public final class SharedWaypointIo implements SharedWaypointPersistence {
    public static final int SCHEMA_VERSION = 1;

    private static final long MAX_FILE_BYTES = 8L * 1024L * 1024L;
    private static final int MAX_PERSISTED_WAYPOINTS = 512;
    private static final int MAX_PUBLISHER_NAME_CODE_POINTS = 64;
    private static final Pattern DIMENSION_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final class UnsupportedSchemaVersionException extends IOException {
        private final long schemaVersion;

        UnsupportedSchemaVersionException(final long schemaVersion) {
            super("shared-waypoint schema " + schemaVersion + " is newer than supported schema " + SCHEMA_VERSION);
            this.schemaVersion = schemaVersion;
        }

        public long schemaVersion() {
            return schemaVersion;
        }
    }

    private static final class FileShape {
        int schemaVersion;
        long revision;
        List<Entry> waypoints;
    }

    private static final class Entry {
        String id;
        String publisherId;
        String publisherName;
        String name;
        String dimensionId;
        double x;
        double y;
        double z;
        int colorArgb;
        String type;
        boolean locked;
        long createdAtEpochMs;
        long revision;
    }

    private final Path file;
    private final Logger logger;

    /** {@code worldRoot} is the server's {@code WorldSavePath.ROOT}. */
    public SharedWaypointIo(final Path worldRoot, final Logger logger) {
        this.file = Objects.requireNonNull(worldRoot, "worldRoot")
            .resolve("confluxmap")
            .resolve("shared_waypoints.json");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public Path file() {
        return file;
    }

    @Override
    public SharedWaypointStore.Snapshot load() throws IOException {
        if (!Files.exists(file)) {
            return emptySnapshot();
        }
        if (Files.size(file) > MAX_FILE_BYTES) {
            return quarantineCorrupt("file exceeds " + MAX_FILE_BYTES + " bytes", null);
        }
        final String json;
        try {
            json = Files.readString(file, StandardCharsets.UTF_8);
        } catch (final MalformedInputException e) {
            return quarantineCorrupt("file is not valid UTF-8", e);
        }
        try {
            final JsonElement parsed = new JsonParser().parse(json);
            if (!parsed.isJsonObject()) {
                throw new JsonParseException("root must be an object");
            }
            final JsonObject root = parsed.getAsJsonObject();
            final long schemaVersion = readSchemaVersion(root);
            if (schemaVersion > SCHEMA_VERSION) {
                throw new UnsupportedSchemaVersionException(schemaVersion);
            }
            if (schemaVersion != SCHEMA_VERSION) {
                throw new JsonParseException("unsupported shared-waypoint schema " + schemaVersion);
            }
            final FileShape shape = GSON.fromJson(root, FileShape.class);
            return fromShape(shape);
        } catch (final UnsupportedSchemaVersionException e) {
            throw e;
        } catch (final RuntimeException e) {
            return quarantineCorrupt(e.toString(), e);
        }
    }

    @Override
    public void save(final SharedWaypointStore.Snapshot snapshot) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        final FileShape shape = toShape(snapshot);
        Files.createDirectories(file.getParent());
        final Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        boolean moved = false;
        try {
            Files.writeString(tmp, GSON.toJson(shape), StandardCharsets.UTF_8);
            move(tmp, file);
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(tmp);
            }
        }
    }

    private static long readSchemaVersion(final JsonObject root) {
        final JsonElement schema = root.get("schemaVersion");
        if (schema == null || !schema.isJsonPrimitive() || !schema.getAsJsonPrimitive().isNumber()) {
            throw new JsonParseException("schemaVersion is missing or not numeric");
        }
        final BigDecimal value;
        try {
            value = schema.getAsBigDecimal().stripTrailingZeros();
        } catch (final NumberFormatException e) {
            throw new JsonParseException("schemaVersion is not a valid number", e);
        }
        if (value.scale() > 0) {
            throw new JsonParseException("schemaVersion must be an integer");
        }
        try {
            return value.longValueExact();
        } catch (final ArithmeticException e) {
            if (value.signum() > 0) {
                // Any positive value above long range is necessarily a future schema.
                return Long.MAX_VALUE;
            }
            throw new JsonParseException("schemaVersion is outside the supported numeric range", e);
        }
    }

    private static SharedWaypointStore.Snapshot fromShape(final FileShape shape) {
        if (shape == null || shape.revision < 0 || shape.waypoints == null) {
            throw new JsonParseException("invalid shared-waypoint document");
        }
        if (shape.waypoints.size() > MAX_PERSISTED_WAYPOINTS) {
            throw new JsonParseException("too many persisted shared waypoints");
        }
        final List<SharedWaypoint> waypoints = new ArrayList<>(shape.waypoints.size());
        final Set<UUID> ids = new HashSet<>();
        for (final Entry entry : shape.waypoints) {
            final SharedWaypoint waypoint = fromEntry(entry, shape.revision);
            if (!ids.add(waypoint.id())) {
                throw new JsonParseException("duplicate shared waypoint id " + waypoint.id());
            }
            waypoints.add(waypoint);
        }
        if (shape.revision == 0 && !waypoints.isEmpty()) {
            throw new JsonParseException("revision zero cannot contain waypoints");
        }
        return new SharedWaypointStore.Snapshot(shape.revision, waypoints);
    }

    private static SharedWaypoint fromEntry(final Entry entry, final long globalRevision) {
        if (entry == null || entry.id == null || entry.publisherId == null || entry.publisherName == null
            || entry.name == null || entry.dimensionId == null || entry.type == null) {
            throw new JsonParseException("shared waypoint contains a missing field");
        }
        if (!SharedWaypointValidator.validName(entry.name)
            || !validPublisherName(entry.publisherName)
            || !validDimensionId(entry.dimensionId)
            || !Double.isFinite(entry.x) || !Double.isFinite(entry.y) || !Double.isFinite(entry.z)
            || Math.abs(entry.x) > SharedWaypointValidator.MAX_HORIZONTAL_COORDINATE
            || Math.abs(entry.z) > SharedWaypointValidator.MAX_HORIZONTAL_COORDINATE
            || (entry.colorArgb >>> 24) != 0xFF
            || entry.createdAtEpochMs < 0
            || entry.revision < 1 || entry.revision > globalRevision) {
            throw new JsonParseException("shared waypoint contains an invalid value");
        }
        try {
            return new SharedWaypoint(
                UUID.fromString(entry.id), UUID.fromString(entry.publisherId), entry.publisherName, entry.name,
                DimensionId.parse(entry.dimensionId), entry.x, entry.y, entry.z, entry.colorArgb,
                Waypoint.Type.valueOf(entry.type), entry.locked, entry.createdAtEpochMs, entry.revision
            );
        } catch (final IllegalArgumentException e) {
            throw new JsonParseException("shared waypoint contains an invalid identifier or type", e);
        }
    }

    private static FileShape toShape(final SharedWaypointStore.Snapshot snapshot) throws IOException {
        if (snapshot.waypoints().size() > MAX_PERSISTED_WAYPOINTS) {
            throw new IOException("refusing to persist more than " + MAX_PERSISTED_WAYPOINTS + " shared waypoints");
        }
        final FileShape shape = new FileShape();
        shape.schemaVersion = SCHEMA_VERSION;
        shape.revision = snapshot.revision();
        shape.waypoints = new ArrayList<>(snapshot.waypoints().size());
        final Set<UUID> ids = new HashSet<>();
        for (final SharedWaypoint waypoint : snapshot.waypoints()) {
            if (!ids.add(waypoint.id())) {
                throw new IOException("duplicate shared waypoint id " + waypoint.id());
            }
            shape.waypoints.add(toEntry(waypoint, snapshot.revision()));
        }
        return shape;
    }

    private static Entry toEntry(final SharedWaypoint waypoint, final long globalRevision) throws IOException {
        if (!SharedWaypointValidator.validName(waypoint.name())
            || !validPublisherName(waypoint.publisherName())
            || !validDimensionId(waypoint.dimensionId().toString())
            || !Double.isFinite(waypoint.x()) || !Double.isFinite(waypoint.y()) || !Double.isFinite(waypoint.z())
            || Math.abs(waypoint.x()) > SharedWaypointValidator.MAX_HORIZONTAL_COORDINATE
            || Math.abs(waypoint.z()) > SharedWaypointValidator.MAX_HORIZONTAL_COORDINATE
            || (waypoint.colorArgb() >>> 24) != 0xFF
            || waypoint.createdAtEpochMs() < 0
            || waypoint.revision() < 1 || waypoint.revision() > globalRevision) {
            throw new IOException("refusing to persist an invalid shared waypoint");
        }
        final Entry entry = new Entry();
        entry.id = waypoint.id().toString();
        entry.publisherId = waypoint.publisherId().toString();
        entry.publisherName = waypoint.publisherName();
        entry.name = waypoint.name();
        entry.dimensionId = waypoint.dimensionId().toString();
        entry.x = waypoint.x();
        entry.y = waypoint.y();
        entry.z = waypoint.z();
        entry.colorArgb = waypoint.colorArgb();
        entry.type = waypoint.type().name();
        entry.locked = waypoint.locked();
        entry.createdAtEpochMs = waypoint.createdAtEpochMs();
        entry.revision = waypoint.revision();
        return entry;
    }

    private static boolean validPublisherName(final String value) {
        return SharedWaypointValidator.validDisplayText(value, MAX_PUBLISHER_NAME_CODE_POINTS);
    }

    private static boolean validDimensionId(final String value) {
        return value.length() <= 256 && DIMENSION_ID.matcher(value).matches();
    }

    private SharedWaypointStore.Snapshot quarantineCorrupt(
        final String reason,
        final Exception cause
    ) throws IOException {
        logger.warn("Shared waypoint file {} is corrupt ({}); quarantining", file, reason);
        try {
            Files.move(
                file,
                file.resolveSibling(file.getFileName() + ".bad"),
                StandardCopyOption.REPLACE_EXISTING
            );
        } catch (final IOException quarantineFailure) {
            if (cause != null) {
                quarantineFailure.addSuppressed(cause);
            }
            throw new IOException(
                "Could not quarantine corrupt shared waypoint file " + file,
                quarantineFailure
            );
        }
        logger.warn("Shared waypoint file {} was quarantined; starting empty", file);
        return emptySnapshot();
    }

    private static SharedWaypointStore.Snapshot emptySnapshot() {
        return new SharedWaypointStore.Snapshot(0, List.of());
    }

    private static void move(final Path tmp, final Path destination) throws IOException {
        try {
            Files.move(tmp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final AtomicMoveNotSupportedException e) {
            Files.move(tmp, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
