package cn.net.rms.confluxmap.core.portal;

import cn.net.rms.confluxmap.core.model.DimensionId;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.Logger;

/**
 * Loads and saves one world's portal markers as JSON at
 * {@code <gameDir>/confluxmap/portals/<serverId>/<worldId>.json}, following
 * {@link cn.net.rms.confluxmap.core.waypoint.WaypointIo}: explicit on-disk DTO,
 * atomic writes, corrupt files quarantined as {@code *.bad}, and a future
 * schema loads as empty read-only rather than being clobbered.
 */
public final class PortalIo {
    public static final int SCHEMA_VERSION = 1;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MARKER_LIST_TYPE = new TypeToken<List<MarkerEntry>>() {
    }.getType();

    private PortalIo() {
    }

    /** On-disk shape of one marker; all Gson-trivial fields, no domain types. */
    private static final class MarkerEntry {
        String kind;
        int chunkX;
        int chunkZ;
        int x;
        int z;
    }

    /** Never throws; a missing or corrupt file yields an empty map. */
    public static Map<DimensionId, List<PortalMarker>> load(final Path file, final Logger logger) {
        if (!Files.exists(file)) {
            return new LinkedHashMap<>();
        }
        try {
            final String json = Files.readString(file, StandardCharsets.UTF_8);
            final JsonElement parsed = new JsonParser().parse(json);
            if (!parsed.isJsonObject()) {
                throw new JsonParseException("portal file root must be an object");
            }
            final JsonObject root = parsed.getAsJsonObject();
            if (futureSchema(root)) {
                logger.warn("Portal file {} uses a future schema; portal markers are read-only", file);
                return new LinkedHashMap<>();
            }
            final Map<DimensionId, List<PortalMarker>> result = new LinkedHashMap<>();
            for (final Map.Entry<String, JsonElement> dimension : root.getAsJsonObject(
                "dimensions"
            ).entrySet()) {
                final DimensionId dimensionId = DimensionId.parse(dimension.getKey());
                final List<MarkerEntry> entries = GSON.fromJson(
                    dimension.getValue(), MARKER_LIST_TYPE
                );
                if (entries == null) {
                    continue;
                }
                for (final MarkerEntry entry : entries) {
                    if (entry == null) {
                        continue;
                    }
                    final PortalMarker marker = toMarker(dimensionId, entry);
                    if (marker != null) {
                        result.computeIfAbsent(dimensionId, ignored -> new ArrayList<>()).add(marker);
                    }
                }
            }
            return result;
        } catch (final IOException | RuntimeException e) {
            logger.warn("Portal file {} unreadable ({}), quarantining and starting empty", file, e.toString());
            quarantine(file, logger);
            return new LinkedHashMap<>();
        }
    }

    public static void save(
        final Path file,
        final Map<DimensionId, List<PortalMarker>> markers,
        final Logger logger
    ) {
        try {
            saveChecked(file, markers);
        } catch (final IOException e) {
            logger.error("Failed to save portal markers to {}", file, e);
        }
    }

    static void saveChecked(
        final Path file,
        final Map<DimensionId, List<PortalMarker>> markers
    ) throws IOException {
        Files.createDirectories(file.getParent());
        final JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        final JsonObject dimensions = new JsonObject();
        for (final Map.Entry<DimensionId, List<PortalMarker>> entry : markers.entrySet()) {
            final List<MarkerEntry> entries = new ArrayList<>(entry.getValue().size());
            for (final PortalMarker marker : entry.getValue()) {
                final MarkerEntry shape = new MarkerEntry();
                shape.kind = marker.kind().blockId();
                shape.chunkX = marker.chunkX();
                shape.chunkZ = marker.chunkZ();
                shape.x = marker.anchorX();
                shape.z = marker.anchorZ();
                entries.add(shape);
            }
            dimensions.add(entry.getKey().toString(), GSON.toJsonTree(entries, MARKER_LIST_TYPE));
        }
        root.add("dimensions", dimensions);
        final Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
        move(tmp, file);
    }

    private static PortalMarker toMarker(final DimensionId dimension, final MarkerEntry entry) {
        final PortalKind kind = PortalKind.parse(entry.kind);
        if (kind == null) {
            return null;
        }
        return new PortalMarker(dimension, entry.chunkX, entry.chunkZ, kind, entry.x, entry.z);
    }

    private static boolean futureSchema(final JsonObject root) {
        final JsonElement schema = root.get("schemaVersion");
        if (schema == null
            || !schema.isJsonPrimitive()
            || !schema.getAsJsonPrimitive().isNumber()) {
            throw new JsonParseException("portal file has no numeric schemaVersion");
        }
        return schema.getAsInt() > SCHEMA_VERSION;
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
