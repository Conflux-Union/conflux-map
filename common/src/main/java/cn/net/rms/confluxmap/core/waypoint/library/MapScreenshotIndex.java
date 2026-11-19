package cn.net.rms.confluxmap.core.waypoint.library;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import org.apache.logging.log4j.Logger;

/** Separate local records for vanilla screenshots. Only references to original PNGs are stored. */
public final class MapScreenshotIndex {
    public record Scope(String server, String world, String dimension) {
        public static Scope of(WorldIdentity world, DimensionId dimension) {
            return new Scope(world.serverId(), world.worldId(), dimension.toString());
        }
    }
    public record Location(Scope scope, double x, double y, double z) {
        public Location {
            Objects.requireNonNull(scope);
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("Invalid screenshot location");
            }
        }
    }
    public record Shot(Location location, String file, long capturedAt, double rotation) {
        public Shot(Location location, String file, long capturedAt) {
            this(location, file, capturedAt, 0);
        }

        public Shot {
            rotation = PhotoRotation.normalize(rotation);
            Objects.requireNonNull(location);
            if (file == null || !file.toLowerCase(Locale.ROOT).endsWith(".png")
                || file.contains("/") || file.contains("\\") || file.contains(":")) {
                throw new IllegalArgumentException("Invalid screenshot filename");
            }
        }
    }
    private static final class Document {
        int version;
        List<Shot> shots;

        Document(int version, List<Shot> shots) {
            this.version = version;
            this.shots = shots;
        }
    }
    private static final class ScopeData {
        String server, world, dimension;

        Scope toScope() {
            return new Scope(server, world, dimension);
        }
    }
    private static final class LocationData {
        Scope scope;
        double x, y, z;

        Location toLocation() {
            return new Location(scope, x, y, z);
        }
    }
    private static final class ShotData {
        Location location;
        String file;
        long capturedAt;
        double rotation;

        Shot toShot() {
            return new Shot(location, file, capturedAt, rotation);
        }
    }
    private static final Gson GSON = new GsonBuilder()
        .registerTypeAdapter(Scope.class, WaypointJson.readUsing(ScopeData.class, ScopeData::toScope))
        .registerTypeAdapter(Location.class, WaypointJson.readUsing(LocationData.class, LocationData::toLocation))
        .registerTypeAdapter(Shot.class, WaypointJson.readUsing(ShotData.class, ShotData::toShot))
        .create();
    private final Path index;
    private final Path screenshots;
    private final Executor io;
    private final Logger logger;
    private volatile Map<Scope, List<Shot>> byScope = Map.of();
    private List<Shot> all = List.of();
    private boolean writable = true;

    /** The executor serializes initial load and subsequent saves. */
    public MapScreenshotIndex(Path index, Path screenshots, Executor io, Logger logger) {
        this.index = index;
        this.screenshots = screenshots.toAbsolutePath().normalize();
        this.io = io;
        this.logger = logger;
        io.execute(this::load);
    }

    public List<Shot> list(Scope scope) { return byScope.getOrDefault(scope, List.of()); }
    public Path file(Shot shot) { return screenshots.resolve(shot.file()); }

    public void save(Location location, Path screenshot, long capturedAt) {
        Path path = screenshot.toAbsolutePath().normalize();
        if (!screenshots.equals(path.getParent()) || !path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png")) return;
        Shot shot = new Shot(location, path.getFileName().toString(), capturedAt);
        io.execute(() -> {
            if (!writable || !Files.isRegularFile(path)) return;
            List<Shot> next = new ArrayList<>(all);
            next.removeIf(previous -> previous.file().equals(shot.file()));
            next.add(0, shot);
            next.sort(Comparator.comparingLong(Shot::capturedAt).reversed());
            try { write(next); }
            catch (IOException error) { logger.warn("Could not save screenshot locations", error); }
        });
    }

    public CompletableFuture<Shot> saveRotation(Shot shot, double rotation) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!writable) throw new IOException("Screenshot index is read-only");
                List<Shot> next = new ArrayList<>(all);
                for (int i = 0; i < next.size(); i++) {
                    Shot current = next.get(i);
                    if (current.file().equals(shot.file()) && current.capturedAt() == shot.capturedAt()
                        && current.location().equals(shot.location())) {
                        Shot rotated = new Shot(current.location(), current.file(), current.capturedAt(), rotation);
                        next.set(i, rotated);
                        write(next);
                        return rotated;
                    }
                }
                throw new IOException("Screenshot no longer exists");
            } catch (IOException error) {
                throw new CompletionException(error);
            }
        }, io);
    }

    private void write(List<Shot> next) throws IOException {
        Files.createDirectories(index.getParent());
        Path temporary = Files.createTempFile(index.getParent(), "screenshots-", ".tmp");
        try {
            Files.writeString(temporary, GSON.toJson(new Document(1, next)));
            try { Files.move(temporary, index, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException error) { Files.move(temporary, index, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
        publish(next);
    }

    private void load() {
        if (!Files.exists(index)) return;
        try {
            Document document = GSON.fromJson(Files.readString(index), Document.class);
            if (document == null || document.version != 1 || document.shots == null) {
                throw new IOException("Unsupported screenshot location index");
            }
            publish(document.shots.stream()
                .filter(shot -> Files.isRegularFile(file(shot)))
                .sorted(Comparator.comparingLong(Shot::capturedAt).reversed()).toList());
        } catch (IOException | RuntimeException error) {
            writable = false;
            logger.warn("Screenshot location index preserved read-only: {}", index, error);
        }
    }

    private void publish(List<Shot> shots) {
        Map<Scope, List<Shot>> grouped = new HashMap<>();
        for (Shot shot : shots) grouped.computeIfAbsent(shot.location().scope(), ignored -> new ArrayList<>()).add(shot);
        grouped.replaceAll((scope, entries) -> List.copyOf(entries));
        all = List.copyOf(shots);
        byScope = Map.copyOf(grouped);
    }
}
