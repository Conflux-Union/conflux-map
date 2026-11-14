package cn.net.rms.confluxmap.server.waypointimage;

import cn.net.rms.confluxmap.core.waypoint.image.WaypointImageStore;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.apache.logging.log4j.Logger;

/**
 * Collects waypoint images that no shared waypoint references anymore. The
 * server's waypoint file is the single source of truth: it is scanned for
 * non-empty {@code imageId} values, and the store sweeps everything else that
 * is older than the grace window. A missing or unreadable file sweeps nothing,
 * so a broken catalog can never cascade into mass image deletion.
 */
public final class SharedWaypointImageGc {
    public static final long GRACE_MS = 24L * 3600 * 1000;

    private final WaypointImageStore imageStore;
    private final Supplier<Optional<Path>> waypointsFile;
    private final Logger logger;

    public SharedWaypointImageGc(
        final WaypointImageStore imageStore,
        final Supplier<Optional<Path>> waypointsFile,
        final Logger logger
    ) {
        this.imageStore = Objects.requireNonNull(imageStore, "imageStore");
        this.waypointsFile = Objects.requireNonNull(waypointsFile, "waypointsFile");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public int collect(final long nowEpochMs) {
        return collect(nowEpochMs, (owner, bytes) -> {});
    }

    /**
     * Runs one collection pass; {@code onDeleted} receives each freed image's
     * owner and byte size so callers can keep quota accounting in step.
     */
    public int collect(
        final long nowEpochMs,
        final java.util.function.BiConsumer<UUID, Long> onDeleted
    ) {
        final Optional<Path> file = waypointsFile.get();
        if (file.isEmpty() || !Files.exists(file.get())) {
            return 0;
        }
        final Set<String> referenced = new HashSet<>();
        try {
            final JsonElement parsed = JsonParser.parseString(Files.readString(file.get()));
            if (!parsed.isJsonObject() || !parsed.getAsJsonObject().has("waypoints")) {
                return 0;
            }
            final JsonObject root = parsed.getAsJsonObject();
            for (final JsonElement element : root.getAsJsonArray("waypoints")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                final JsonElement imageId = element.getAsJsonObject().get("imageId");
                if (imageId == null || !imageId.isJsonPrimitive() || imageId.getAsString().isBlank()) {
                    continue;
                }
                try {
                    referenced.add(UUID.fromString(imageId.getAsString()).toString());
                } catch (final IllegalArgumentException e) {
                    logger.warn("Ignoring non-uuid waypoint imageId {}", imageId.getAsString());
                }
            }
        } catch (final IOException | RuntimeException e) {
            logger.warn("Waypoint image GC skipped: waypoint file unreadable ({})", e.toString());
            return 0;
        }
        try {
            return imageStore.sweep(referenced, GRACE_MS, nowEpochMs, onDeleted);
        } catch (final IOException e) {
            logger.warn("Waypoint image sweep failed", e);
            return 0;
        }
    }
}
