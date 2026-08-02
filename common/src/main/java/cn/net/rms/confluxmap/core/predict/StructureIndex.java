package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.store.WorldStorageMigration;
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
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Lazy, persistent structure candidates with tri-state server verification. */
public final class StructureIndex {
    private static final Logger LOGGER = LogManager.getLogger("ConfluxMap/StructureIndex");
    private static final int MC_1_17_1 = 21;
    private static final String CACHE_PREFIX = "structures_v3_mc";

    public enum StructureType {
        DESERT_PYRAMID(1, "desert_pyramid", "DP", 32, 32, DimensionId.OVERWORLD, MC_1_17_1, 16.0),
        JUNGLE_TEMPLE(2, "jungle_temple", "JT", 32, 32, DimensionId.OVERWORLD, MC_1_17_1, 16.0),
        SWAMP_HUT(3, "swamp_hut", "SH", 32, 32, DimensionId.OVERWORLD, MC_1_17_1, 16.0),
        IGLOO(4, "igloo", "IG", 32, 32, DimensionId.OVERWORLD, MC_1_17_1, 16.0),
        VILLAGE(5, "village", "VI", 32, 34, DimensionId.OVERWORLD, MC_1_17_1, 16.0),
        OCEAN_RUIN(6, "ocean_ruin", "OR", 20, 20, DimensionId.OVERWORLD, MC_1_17_1, 16.0),
        SHIPWRECK(7, "shipwreck", "SW", 24, 24, DimensionId.OVERWORLD, MC_1_17_1, 16.0),
        OCEAN_MONUMENT(8, "ocean_monument", "OM", 32, 32, DimensionId.OVERWORLD, MC_1_17_1, 16.0),
        WOODLAND_MANSION(9, "woodland_mansion", "WM", 80, 80, DimensionId.OVERWORLD, MC_1_17_1, 16.0),
        PILLAGER_OUTPOST(10, "pillager_outpost", "PO", 32, 32, DimensionId.OVERWORLD, MC_1_17_1, 16.0),
        RUINED_PORTAL(11, "ruined_portal", "RP", 40, 40, DimensionId.OVERWORLD, MC_1_17_1, 16.0),
        RUINED_PORTAL_NETHER(12, "ruined_portal_nether", "RP", 25, 40, DimensionId.NETHER, MC_1_17_1, 16.0),
        ANCIENT_CITY(13, "ancient_city", "AC", 24, 24, DimensionId.OVERWORLD, 23, 16.0),
        BURIED_TREASURE(14, "buried_treasure", "BT", 1, 1, DimensionId.OVERWORLD, MC_1_17_1, 1.0),
        MINESHAFT(15, "mineshaft", "MS", 1, 1, DimensionId.OVERWORLD, MC_1_17_1, 1.0),
        FORTRESS(18, "fortress", "FO", 27, 27, DimensionId.NETHER, MC_1_17_1, 16.0),
        BASTION_REMNANT(19, "bastion_remnant", "BA", 27, 27, DimensionId.NETHER, MC_1_17_1, 16.0),
        END_CITY(20, "end_city", "EC", 20, 20, DimensionId.END, MC_1_17_1, 16.0),
        TRAIL_RUINS(23, "trail_ruins", "TR", 34, 34, DimensionId.OVERWORLD, 25, 16.0),
        TRIAL_CHAMBERS(24, "trial_chambers", "TC", 34, 34, DimensionId.OVERWORLD, 26, 16.0),
        STRONGHOLD(25, "stronghold", "ST", 0, 0, DimensionId.OVERWORLD, MC_1_17_1, 16.0),
        NETHER_FOSSIL(26, "nether_fossil", "NF", 2, 2, DimensionId.NETHER, MC_1_17_1, 2.0);

        private final int nativeId;
        private final String id;
        private final String badge;
        private final int legacySpacingChunks;
        private final int modernSpacingChunks;
        private final DimensionId dimension;
        private final int minMcVersion;
        private final double maxDisplayScale;

        StructureType(
            final int nativeId,
            final String id,
            final String badge,
            final int legacySpacingChunks,
            final int modernSpacingChunks,
            final DimensionId dimension,
            final int minMcVersion,
            final double maxDisplayScale
        ) {
            this.nativeId = nativeId;
            this.id = id;
            this.badge = badge;
            this.legacySpacingChunks = legacySpacingChunks;
            this.modernSpacingChunks = modernSpacingChunks;
            this.dimension = dimension;
            this.minMcVersion = minMcVersion;
            this.maxDisplayScale = maxDisplayScale;
        }

        public int nativeId() { return nativeId; }
        public String id() { return id; }
        public String badge() { return badge; }
        public String translationKey() { return "confluxmap.structure." + id; }
        public boolean globalPlacement() { return legacySpacingChunks == 0; }
        public int regionSizeBlocks(final int mcVersion) {
            final int spacing = mcVersion <= MC_1_17_1 ? legacySpacingChunks : modernSpacingChunks;
            return spacing * 16;
        }
        public boolean supports(final DimensionId candidate) { return dimension.equals(candidate); }
        public boolean supports(final int mcVersion, final DimensionId candidate) {
            return mcVersion >= minMcVersion && supports(candidate);
        }
        public boolean displaysAt(final double blocksPerPixel) { return blocksPerPixel <= maxDisplayScale; }

        public static EnumSet<StructureType> availableIn(final int mcVersion, final DimensionId dimension) {
            final EnumSet<StructureType> result = EnumSet.noneOf(StructureType.class);
            for (final StructureType type : values()) {
                if (type.supports(mcVersion, dimension)) {
                    result.add(type);
                }
            }
            return result;
        }
    }

    public enum State { CANDIDATE, VERIFIED, NONEXISTENT }

    public record Marker(StructureType type, int blockX, int blockZ, State state) {
    }

    @FunctionalInterface
    public interface CandidateProvider {
        long[] candidates(StructureType type, int regionX, int regionZ);

        default long[] candidates(
            final StructureType type,
            final int minRegionX,
            final int minRegionZ,
            final int maxRegionX,
            final int maxRegionZ
        ) {
            final List<Long> combined = new ArrayList<>();
            for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
                for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
                    for (final long position : candidates(type, regionX, regionZ)) {
                        combined.add(position);
                    }
                }
            }
            final long[] result = new long[combined.size()];
            for (int i = 0; i < combined.size(); i++) {
                result[i] = combined.get(i);
            }
            return result;
        }

        default OptionalLong nearest(
            final StructureType type,
            final int blockX,
            final int blockZ,
            final int maxRadius
        ) {
            return OptionalLong.empty();
        }
    }

    private final Path file;
    private final DimensionId dimension;
    private final int mcVersion;
    private final CandidateProvider provider;
    private final Map<String, Marker> markers = new HashMap<>();
    private final Map<StructureType, Set<Long>> queriedRegions = new EnumMap<>(StructureType.class);
    private boolean dirty;

    /**
     * Compatibility constructor for callers that do not have a world identity. New callers must
     * use {@link #StructureIndex(Path, WorldIdentity, DimensionId, CandidateProvider)}; without that
     * namespace there is no way to safely separate two worlds sharing a dimension.
     */
    @Deprecated
    public StructureIndex(final Path cacheRoot, final String dimension, final CandidateProvider provider) {
        this(
            cacheRoot.resolve(cacheFileName(MC_1_17_1, sanitize(dimension))),
            DimensionId.parse(dimension),
            MC_1_17_1,
            provider
        );
    }

    @Deprecated
    public StructureIndex(
        final Path cacheRoot,
        final WorldIdentity world,
        final String dimension,
        final CandidateProvider provider
    ) {
        this(cacheRoot, world, DimensionId.parse(dimension), MC_1_17_1, provider);
    }

    public StructureIndex(
        final Path cacheRoot,
        final WorldIdentity world,
        final DimensionId dimension,
        final CandidateProvider provider
    ) {
        this(cacheRoot, world, dimension, MC_1_17_1, provider);
    }

    public StructureIndex(
        final Path cacheRoot,
        final WorldIdentity world,
        final DimensionId dimension,
        final int mcVersion,
        final CandidateProvider provider
    ) {
        this(
            WorldStorageMigration.directory(cacheRoot.resolve("structures"), world, LOGGER)
                .resolve(cacheFileName(mcVersion, sanitize(dimension.fileName()))),
            dimension,
            mcVersion,
            provider
        );
    }

    private StructureIndex(
        final Path file,
        final DimensionId dimension,
        final int mcVersion,
        final CandidateProvider provider
    ) {
        this.file = file;
        this.dimension = dimension;
        this.mcVersion = mcVersion;
        this.provider = provider;
        for (final StructureType type : StructureType.values()) {
            queriedRegions.put(type, new HashSet<>());
        }
        load();
    }

    public synchronized List<Marker> query(final int minBlockX, final int maxBlockX, final int minBlockZ, final int maxBlockZ) {
        return query(minBlockX, maxBlockX, minBlockZ, maxBlockZ, StructureType.availableIn(mcVersion, dimension));
    }

    public synchronized List<Marker> query(
        final int minBlockX,
        final int maxBlockX,
        final int minBlockZ,
        final int maxBlockZ,
        final Set<StructureType> includedTypes
    ) {
        if (provider != null) {
            for (final StructureType type : StructureType.values()) {
                if (!includedTypes.contains(type) || !type.supports(mcVersion, dimension)) {
                    continue;
                }
                if (type.globalPlacement()) {
                    final Set<Long> covered = queriedRegions.get(type);
                    if (covered.add(0L)) {
                        addCandidates(type, provider.candidates(type, 0, 0));
                    }
                    continue;
                }
                final int regionSize = type.regionSizeBlocks(mcVersion);
                final int minRegionX = Math.floorDiv(minBlockX, regionSize);
                final int maxRegionX = Math.floorDiv(maxBlockX, regionSize);
                final int minRegionZ = Math.floorDiv(minBlockZ, regionSize);
                final int maxRegionZ = Math.floorDiv(maxBlockZ, regionSize);
                final Set<Long> covered = queriedRegions.get(type);
                if (covered.isEmpty()) {
                    addCandidates(type, provider.candidates(
                        type, minRegionX, minRegionZ, maxRegionX, maxRegionZ
                    ));
                    markCovered(covered, minRegionX, minRegionZ, maxRegionX, maxRegionZ);
                    continue;
                }
                for (int rz = minRegionZ; rz <= maxRegionZ; rz++) {
                    for (int rx = minRegionX; rx <= maxRegionX; rx++) {
                        final long region = packRegion(rx, rz);
                        if (covered.contains(region)) {
                            continue;
                        }
                        addCandidates(type, provider.candidates(type, rx, rz));
                        covered.add(region);
                    }
                }
            }
        }
        final List<Marker> visible = new ArrayList<>();
        for (final Marker marker : markers.values()) {
            if (includedTypes.contains(marker.type())
                && marker.type().supports(mcVersion, dimension)
                && marker.blockX() >= minBlockX && marker.blockX() <= maxBlockX
                && marker.blockZ() >= minBlockZ && marker.blockZ() <= maxBlockZ
                && marker.state() != State.NONEXISTENT) {
                visible.add(marker);
            }
        }
        visible.sort(Comparator.comparingInt(Marker::blockX).thenComparingInt(Marker::blockZ));
        return visible;
    }

    public synchronized Optional<Marker> findNearest(
        final StructureType type,
        final int blockX,
        final int blockZ,
        final int maxRadius
    ) {
        if (provider == null || !type.supports(mcVersion, dimension) || maxRadius <= 0) {
            return Optional.empty();
        }
        final OptionalLong located = provider.nearest(type, blockX, blockZ, maxRadius);
        if (located.isPresent()) {
            addCandidates(type, new long[] {located.getAsLong()});
        }
        final long maxDistanceSquared = (long) maxRadius * maxRadius;
        Marker nearest = null;
        long nearestDistanceSquared = Long.MAX_VALUE;
        for (final Marker marker : markers.values()) {
            if (marker.type() != type || marker.state() == State.NONEXISTENT) {
                continue;
            }
            final long dx = marker.blockX() - (long) blockX;
            final long dz = marker.blockZ() - (long) blockZ;
            final long distanceSquared = dx * dx + dz * dz;
            if (distanceSquared <= maxDistanceSquared && distanceSquared < nearestDistanceSquared) {
                nearest = marker;
                nearestDistanceSquared = distanceSquared;
            }
        }
        return Optional.ofNullable(nearest);
    }

    /** Returns the nearest candidates inside one circular search area, closest first. */
    public synchronized List<Marker> findCandidates(
        final StructureType type,
        final int blockX,
        final int blockZ,
        final int maxRadius,
        final int limit
    ) {
        if (provider == null || !type.supports(mcVersion, dimension)
            || maxRadius <= 0 || limit <= 0) {
            return List.of();
        }
        if (type.globalPlacement()) {
            final Set<Long> covered = queriedRegions.get(type);
            if (covered.add(0L)) {
                addCandidates(type, provider.candidates(type, 0, 0));
            }
            return nearestCandidates(type, blockX, blockZ, maxRadius, limit);
        }

        final int regionSize = type.regionSizeBlocks(mcVersion);
        final int initialRadius = (int) Math.min(
            maxRadius,
            (long) Math.max(1, (int) Math.ceil(Math.sqrt(limit))) * regionSize
        );
        int radius = Math.max(1, initialRadius);
        while (true) {
            final int minRegionX = Math.floorDiv(blockX - radius, regionSize);
            final int maxRegionX = Math.floorDiv(blockX + radius, regionSize);
            final int minRegionZ = Math.floorDiv(blockZ - radius, regionSize);
            final int maxRegionZ = Math.floorDiv(blockZ + radius, regionSize);
            final long cells = ((long) maxRegionX - minRegionX + 1L)
                * ((long) maxRegionZ - minRegionZ + 1L);
            if (cells > 1_048_576L) {
                break;
            }
            final Set<Long> covered = queriedRegions.get(type);
            if (!coversAll(covered, minRegionX, minRegionZ, maxRegionX, maxRegionZ)) {
                addCandidates(type, provider.candidates(
                    type, minRegionX, minRegionZ, maxRegionX, maxRegionZ
                ));
                markCovered(covered, minRegionX, minRegionZ, maxRegionX, maxRegionZ);
            }
            final List<Marker> result = nearestCandidates(
                type, blockX, blockZ, radius, limit
            );
            if (result.size() >= limit || radius >= maxRadius) {
                return result;
            }
            final long expanded = Math.min((long) maxRadius, radius * 2L);
            if (expanded == radius) {
                return result;
            }
            radius = (int) expanded;
        }
        return nearestCandidates(type, blockX, blockZ, maxRadius, limit);
    }

    private List<Marker> nearestCandidates(
        final StructureType type,
        final int blockX,
        final int blockZ,
        final int maxRadius,
        final int limit
    ) {
        final long maxDistanceSquared = (long) maxRadius * maxRadius;
        final List<Marker> result = new ArrayList<>();
        for (final Marker marker : markers.values()) {
            if (marker.type() != type || marker.state() == State.NONEXISTENT) {
                continue;
            }
            final long dx = marker.blockX() - (long) blockX;
            final long dz = marker.blockZ() - (long) blockZ;
            if (dx * dx + dz * dz <= maxDistanceSquared) {
                result.add(marker);
            }
        }
        result.sort(
            Comparator.comparingLong((Marker marker) -> {
                final long dx = marker.blockX() - (long) blockX;
                final long dz = marker.blockZ() - (long) blockZ;
                return dx * dx + dz * dz;
            }).thenComparingInt(Marker::blockX).thenComparingInt(Marker::blockZ)
        );
        return result.size() <= limit ? List.copyOf(result) : List.copyOf(result.subList(0, limit));
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

    private static long packRegion(final int x, final int z) {
        return ((long) x << 32) | (z & 0xFFFF_FFFFL);
    }

    private static void markCovered(
        final Set<Long> covered,
        final int minRegionX,
        final int minRegionZ,
        final int maxRegionX,
        final int maxRegionZ
    ) {
        for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
            for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
                covered.add(packRegion(regionX, regionZ));
            }
        }
    }

    private static boolean coversAll(
        final Set<Long> covered,
        final int minRegionX,
        final int minRegionZ,
        final int maxRegionX,
        final int maxRegionZ
    ) {
        for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
            for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
                if (!covered.contains(packRegion(regionX, regionZ))) {
                    return false;
                }
            }
        }
        return true;
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

    private static String cacheFileName(final int mcVersion, final String dimension) {
        return CACHE_PREFIX + mcVersion + "_" + dimension + ".json";
    }
}
