package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.model.WorldIdentity;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Lazy, persistent structure candidates with tri-state server verification. */
public final class StructureIndex {
    public enum StructureType {
        VILLAGE(6, "village", "V"),
        OCEAN_MONUMENT(9, "ocean_monument", "M"),
        WOODLAND_MANSION(10, "woodland_mansion", "W"),
        PILLAGER_OUTPOST(11, "pillager_outpost", "P"),
        RUINED_PORTAL(12, "ruined_portal", "R"),
        END_CITY(21, "end_city", "E");

        private final int nativeId;
        private final String id;
        private final String badge;

        StructureType(final int nativeId, final String id, final String badge) {
            this.nativeId = nativeId;
            this.id = id;
            this.badge = badge;
        }

        public int nativeId() { return nativeId; }
        public String id() { return id; }
        public String badge() { return badge; }
    }

    public enum State { CANDIDATE, VERIFIED, NONEXISTENT }

    public record Marker(StructureType type, int blockX, int blockZ, State state) {
    }

    @FunctionalInterface
    public interface CandidateProvider {
        long[] candidates(StructureType type, int regionX, int regionZ);
    }

    private final Path file;
    private final CandidateProvider provider;
    private final Map<String, Marker> markers = new HashMap<>();
    private boolean dirty;

    /**
     * Compatibility constructor for callers that do not have a world identity. New callers must
     * use {@link #StructureIndex(Path, WorldIdentity, String, CandidateProvider)}; without that
     * namespace there is no way to safely separate two worlds sharing a dimension.
     */
    @Deprecated
    public StructureIndex(final Path cacheRoot, final String dimension, final CandidateProvider provider) {
        this(cacheRoot.resolve("structures_" + sanitize(dimension) + ".json"), provider);
    }

    public StructureIndex(
        final Path cacheRoot,
        final WorldIdentity world,
        final String dimension,
        final CandidateProvider provider
    ) {
        this(
            cacheRoot
                .resolve("structures")
                .resolve(sanitize(world.serverId()))
                .resolve(sanitize(world.worldId()))
                .resolve("structures_" + sanitize(dimension) + ".json"),
            provider
        );
    }

    private StructureIndex(final Path file, final CandidateProvider provider) {
        this.file = file;
        this.provider = provider;
        load();
    }

    public synchronized List<Marker> query(final int minBlockX, final int maxBlockX, final int minBlockZ, final int maxBlockZ) {
        final int minRegionX = Math.floorDiv(minBlockX, 32);
        final int maxRegionX = Math.floorDiv(maxBlockX, 32);
        final int minRegionZ = Math.floorDiv(minBlockZ, 32);
        final int maxRegionZ = Math.floorDiv(maxBlockZ, 32);
        if (provider != null) {
            for (final StructureType type : StructureType.values()) {
                for (int rz = minRegionZ; rz <= maxRegionZ; rz++) {
                    for (int rx = minRegionX; rx <= maxRegionX; rx++) {
                        addCandidates(type, provider.candidates(type, rx, rz));
                    }
                }
            }
        }
        final List<Marker> visible = new ArrayList<>();
        for (final Marker marker : markers.values()) {
            if (marker.blockX() >= minBlockX && marker.blockX() <= maxBlockX
                && marker.blockZ() >= minBlockZ && marker.blockZ() <= maxBlockZ
                && marker.state() != State.NONEXISTENT) {
                visible.add(marker);
            }
        }
        visible.sort(Comparator.comparingInt(Marker::blockX).thenComparingInt(Marker::blockZ));
        return visible;
    }

    public synchronized void verify(final StructureType type, final int blockX, final int blockZ, final boolean exists) {
        final String key = key(type, blockX, blockZ);
        if (markers.containsKey(key)) {
            markers.put(key, new Marker(type, blockX, blockZ, exists ? State.VERIFIED : State.NONEXISTENT));
            dirty = true;
        }
    }

    public synchronized void save() {
        if (!dirty) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            final JsonArray array = new JsonArray();
            for (final Marker marker : markers.values()) {
                final JsonObject object = new JsonObject();
                object.addProperty("type", marker.type().id());
                object.addProperty("x", marker.blockX());
                object.addProperty("z", marker.blockZ());
                object.addProperty("state", marker.state().name());
                array.add(object);
            }
            final Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().create().toJson(array), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
        } catch (IOException ignored) {
            // A prediction cache is disposable; live markers remain available for this session.
        }
    }

    private void addCandidates(final StructureType type, final long[] positions) {
        if (positions == null) {
            return;
        }
        for (final long packed : positions) {
            final int x = (int) (packed >>> 32);
            final int z = (int) packed;
            if (!markers.containsKey(key(type, x, z))) {
                markers.put(key(type, x, z), new Marker(type, x, z, State.CANDIDATE));
                dirty = true;
            }
        }
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            final JsonArray array = new Gson().fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonArray.class);
            for (final JsonElement element : array) {
                final JsonObject object = element.getAsJsonObject();
                final StructureType type = typeById(object.get("type").getAsString());
                if (type == null) {
                    continue;
                }
                final State state;
                try {
                    state = State.valueOf(object.get("state").getAsString());
                } catch (IllegalArgumentException e) {
                    continue;
                }
                final int x = object.get("x").getAsInt();
                final int z = object.get("z").getAsInt();
                markers.put(key(type, x, z), new Marker(type, x, z, state));
            }
        } catch (Exception ignored) {
            // Ignore malformed optional prediction metadata.
        }
    }

    private static String key(final StructureType type, final int x, final int z) {
        return type.id() + ":" + x + ":" + z;
    }

    private static StructureType typeById(final String id) {
        for (final StructureType type : StructureType.values()) {
            if (type.id().equals(id)) {
                return type;
            }
        }
        return null;
    }

    private static String sanitize(final String value) {
        final String safe = value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.startsWith(".") ? "_" + safe.replaceFirst("^\\.+", "") : safe;
    }
}
