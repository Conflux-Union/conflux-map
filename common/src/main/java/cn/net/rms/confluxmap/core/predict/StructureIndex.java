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
    private static final String CACHE_PREFIX = "structures_v4_mc";
    private static final int MAX_CANDIDATE_QUERY_REGIONS = 1_024;

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
        NETHER_FOSSIL(26, "nether_fossil", "NF", 2, 2, DimensionId.NETHER, MC_1_17_1, 4.0);

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
        public String variantTranslationKey(final int variant) {
            return switch (this) {
                case VILLAGE -> switch (variant & 15) {
                    case 1 -> "confluxmap.structure.village.desert";
                    case 2 -> "confluxmap.structure.village.savanna";
                    case 3 -> "confluxmap.structure.village.taiga";
                    case 4 -> "confluxmap.structure.village.snowy";
                    case 8 -> "confluxmap.structure.village.zombie_plains";
                    case 9 -> "confluxmap.structure.village.zombie_desert";
                    case 10 -> "confluxmap.structure.village.zombie_savanna";
                    case 11 -> "confluxmap.structure.village.zombie_taiga";
                    case 12 -> "confluxmap.structure.village.zombie_snowy";
                    default -> "confluxmap.structure.village.plains";
                };
                case IGLOO -> variant == 1
                    ? "confluxmap.structure.igloo.basement"
                    : "confluxmap.structure.igloo.normal";
                case SHIPWRECK -> variant == 1
                    ? "confluxmap.structure.shipwreck.beached"
                    : "confluxmap.structure.shipwreck.water";
                case BASTION_REMNANT -> switch (variant) {
                    case 1 -> "confluxmap.structure.bastion_remnant.hoglin_stable";
                    case 2 -> "confluxmap.structure.bastion_remnant.treasure";
                    case 3 -> "confluxmap.structure.bastion_remnant.bridge";
                    default -> "confluxmap.structure.bastion_remnant.housing";
                };
                case RUINED_PORTAL, RUINED_PORTAL_NETHER -> variant == 1
                    ? "confluxmap.structure.ruined_portal.giant"
                    : "confluxmap.structure.ruined_portal.normal";
                case END_CITY -> variant == 1
                    ? "confluxmap.structure.end_city.ship"
                    : "confluxmap.structure.end_city.normal";
                default -> translationKey();
            };
        }
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

    public record Candidate(int blockX, int blockZ, int variant) {
    }

    public record Marker(StructureType type, int blockX, int blockZ, int variant, State state) {
        public Marker(
            final StructureType type,
            final int blockX,
            final int blockZ,
            final State state
        ) {
            this(type, blockX, blockZ, 0, state);
        }

        public String translationKey() {
            return type.variantTranslationKey(variant);
        }
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

        default Candidate[] detailedCandidates(
            final StructureType type,
            final int minRegionX,
            final int minRegionZ,
            final int maxRegionX,
            final int maxRegionZ
        ) {
            return unpack(candidates(type, minRegionX, minRegionZ, maxRegionX, maxRegionZ));
        }

        default Optional<Candidate> nearestCandidate(
            final StructureType type,
            final int blockX,
            final int blockZ,
            final int maxRadius
        ) {
            final OptionalLong packed = nearest(type, blockX, blockZ, maxRadius);
            return packed.isPresent()
                ? Optional.of(unpack(packed.getAsLong()))
                : Optional.empty();
        }

        private static Candidate[] unpack(final long[] positions) {
            if (positions == null || positions.length == 0) {
                return new Candidate[0];
            }
            final Candidate[] candidates = new Candidate[positions.length];
            for (int index = 0; index < positions.length; index++) {
                candidates[index] = unpack(positions[index]);
            }
            return candidates;
        }

        private static Candidate unpack(final long packed) {
            return new Candidate((int) (packed >>> 32), (int) packed, 0);
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
                        addCandidates(type, provider.detailedCandidates(type, 0, 0, 0, 0));
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
                    addCandidates(type, provider.detailedCandidates(
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
                        addCandidates(type, provider.detailedCandidates(type, rx, rz, rx, rz));
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
        final Optional<Candidate> located = provider.nearestCandidate(type, blockX, blockZ, maxRadius);
        if (located.isPresent()) {
            addCandidates(type, new Candidate[] {located.get()});
        }
        Marker nearest = null;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (final Marker marker : markers.values()) {
            if (marker.type() != type || marker.state() == State.NONEXISTENT) {
                continue;
            }
            final double distance = distance(marker, blockX, blockZ);
            if (distance <= maxRadius && distance < nearestDistance) {
                nearest = marker;
                nearestDistance = distance;
            }
        }
        return Optional.ofNullable(nearest);
    }

    /**
     * Loads a deliberately small square of candidate regions and returns the nearest markers.
     * The UI caps {@code maxCandidates} at 32; the multiplier leaves room for placements that
     * cubiomes rejects while keeping the native batch bounded to a few hundred regions.
     */
    public synchronized List<Marker> findNearestCandidates(
        final StructureType type,
        final int blockX,
        final int blockZ,
        final int maxCandidates
    ) {
        if (provider == null || maxCandidates <= 0 || !type.supports(mcVersion, dimension)) {
            return List.of();
        }
        final int boundedLimit = Math.min(maxCandidates, 32);
        final List<Marker> candidates;
        if (type.globalPlacement()) {
            candidates = query(
                Integer.MIN_VALUE,
                Integer.MAX_VALUE,
                Integer.MIN_VALUE,
                Integer.MAX_VALUE,
                EnumSet.of(type)
            );
        } else {
            final int regionSize = type.regionSizeBlocks(mcVersion);
            final int side = (int) Math.ceil(Math.sqrt(boundedLimit * 4.0));
            final int radiusRegions = Math.max(1, (side - 1) / 2 + 1);
            final long extent = (long) radiusRegions * regionSize;
            final int minBlockX = clampBlockCoordinate(blockX - extent);
            final int maxBlockX = clampBlockCoordinate(blockX + extent);
            final int minBlockZ = clampBlockCoordinate(blockZ - extent);
            final int maxBlockZ = clampBlockCoordinate(blockZ + extent);
            prefetchCandidateArea(type, minBlockX, maxBlockX, minBlockZ, maxBlockZ);
            candidates = query(minBlockX, maxBlockX, minBlockZ, maxBlockZ, EnumSet.of(type));
        }
        candidates.sort(Comparator
            .comparingDouble((Marker marker) -> distance(marker, blockX, blockZ))
            .thenComparingInt(Marker::blockX)
            .thenComparingInt(Marker::blockZ));
        return candidates.size() <= boundedLimit
            ? List.copyOf(candidates)
            : List.copyOf(candidates.subList(0, boundedLimit));
    }

    /**
     * Returns the nearest candidates inside a circular search area. Native generation expands
     * only while the next rectangular batch stays under a fixed region budget, so a large UI
     * radius cannot stall the client thread.
     */
    public synchronized List<Marker> findCandidates(
        final StructureType type,
        final int blockX,
        final int blockZ,
        final int maxRadius,
        final int limit
    ) {
        if (provider == null || maxRadius <= 0 || limit <= 0
            || !type.supports(mcVersion, dimension)) {
            return List.of();
        }
        final int boundedLimit = Math.min(limit, 100);
        if (type.globalPlacement()) {
            query(
                Integer.MIN_VALUE,
                Integer.MAX_VALUE,
                Integer.MIN_VALUE,
                Integer.MAX_VALUE,
                EnumSet.of(type)
            );
            return nearestCandidates(type, blockX, blockZ, maxRadius, boundedLimit);
        }

        final int regionSize = type.regionSizeBlocks(mcVersion);
        final long initialExtent = Math.min(
            maxRadius,
            (long) Math.max(1, (int) Math.ceil(Math.sqrt(boundedLimit))) * regionSize
        );
        long extent = Math.max(1L, initialExtent);
        while (true) {
            final int minBlockX = clampBlockCoordinate((long) blockX - extent);
            final int maxBlockX = clampBlockCoordinate((long) blockX + extent);
            final int minBlockZ = clampBlockCoordinate((long) blockZ - extent);
            final int maxBlockZ = clampBlockCoordinate((long) blockZ + extent);
            if (regionCount(type, minBlockX, maxBlockX, minBlockZ, maxBlockZ)
                > MAX_CANDIDATE_QUERY_REGIONS) {
                break;
            }
            prefetchCandidateArea(type, minBlockX, maxBlockX, minBlockZ, maxBlockZ);
            final List<Marker> result = nearestCandidates(
                type, blockX, blockZ, maxRadius, boundedLimit
            );
            if (result.size() >= boundedLimit || extent >= maxRadius) {
                return result;
            }
            extent = Math.min((long) maxRadius, extent * 2L);
        }
        return nearestCandidates(type, blockX, blockZ, maxRadius, boundedLimit);
    }

    private List<Marker> nearestCandidates(
        final StructureType type,
        final int blockX,
        final int blockZ,
        final int maxRadius,
        final int limit
    ) {
        final List<Marker> candidates = new ArrayList<>();
        for (final Marker marker : markers.values()) {
            if (marker.type() != type || marker.state() == State.NONEXISTENT
                || distance(marker, blockX, blockZ) > maxRadius) {
                continue;
            }
            candidates.add(marker);
        }
        candidates.sort(Comparator
            .comparingDouble((Marker marker) -> distance(marker, blockX, blockZ))
            .thenComparingInt(Marker::blockX)
            .thenComparingInt(Marker::blockZ));
        return candidates.size() <= limit
            ? List.copyOf(candidates)
            : List.copyOf(candidates.subList(0, limit));
    }

    private int regionCount(
        final StructureType type,
        final int minBlockX,
        final int maxBlockX,
        final int minBlockZ,
        final int maxBlockZ
    ) {
        final int regionSize = type.regionSizeBlocks(mcVersion);
        final long width = (long) Math.floorDiv(maxBlockX, regionSize)
            - Math.floorDiv(minBlockX, regionSize) + 1L;
        final long height = (long) Math.floorDiv(maxBlockZ, regionSize)
            - Math.floorDiv(minBlockZ, regionSize) + 1L;
        return width > MAX_CANDIDATE_QUERY_REGIONS
            || height > MAX_CANDIDATE_QUERY_REGIONS
            || width * height > MAX_CANDIDATE_QUERY_REGIONS
            ? MAX_CANDIDATE_QUERY_REGIONS + 1
            : (int) (width * height);
    }

    public synchronized void verify(final StructureType type, final int blockX, final int blockZ, final boolean exists) {
        final String key = key(type, blockX, blockZ);
        final Marker marker = markers.get(key);
        if (marker != null) {
            markers.put(key, new Marker(
                type, blockX, blockZ, marker.variant(),
                exists ? State.VERIFIED : State.NONEXISTENT
            ));
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
                object.addProperty("variant", marker.variant());
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
            addCandidate(type, new Candidate((int) (packed >>> 32), (int) packed, 0));
        }
    }

    private void addCandidates(final StructureType type, final Candidate[] candidates) {
        if (candidates == null) {
            return;
        }
        for (final Candidate candidate : candidates) {
            addCandidate(type, candidate);
        }
    }

    private void addCandidate(final StructureType type, final Candidate candidate) {
        final String key = key(type, candidate.blockX(), candidate.blockZ());
        final Marker existing = markers.get(key);
        if (existing == null) {
            markers.put(key, new Marker(
                type, candidate.blockX(), candidate.blockZ(), candidate.variant(), State.CANDIDATE
            ));
            dirty = true;
        } else if (existing.variant() != candidate.variant()) {
            markers.put(key, new Marker(
                type, candidate.blockX(), candidate.blockZ(), candidate.variant(), existing.state()
            ));
            dirty = true;
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
                final int variant = object.has("variant") ? object.get("variant").getAsInt() : 0;
                markers.put(key(type, x, z), new Marker(type, x, z, variant, state));
            }
        } catch (Exception ignored) {
            // Ignore malformed optional prediction metadata.
        }
    }

    private static String key(final StructureType type, final int x, final int z) {
        return type.id() + ":" + x + ":" + z;
    }

    /**
     * Candidate searches stay small, so loading the requested rectangle again as one batch is
     * cheaper than letting {@link #query} invoke the native provider once for every uncached
     * region after a player changes the search center.
     */
    private void prefetchCandidateArea(
        final StructureType type,
        final int minBlockX,
        final int maxBlockX,
        final int minBlockZ,
        final int maxBlockZ
    ) {
        final int regionSize = type.regionSizeBlocks(mcVersion);
        final int minRegionX = Math.floorDiv(minBlockX, regionSize);
        final int maxRegionX = Math.floorDiv(maxBlockX, regionSize);
        final int minRegionZ = Math.floorDiv(minBlockZ, regionSize);
        final int maxRegionZ = Math.floorDiv(maxBlockZ, regionSize);
        final Set<Long> covered = queriedRegions.get(type);
        boolean missing = false;
        for (int regionZ = minRegionZ; regionZ <= maxRegionZ && !missing; regionZ++) {
            for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
                if (!covered.contains(packRegion(regionX, regionZ))) {
                    missing = true;
                    break;
                }
            }
        }
        if (!missing) {
            return;
        }
        addCandidates(type, provider.detailedCandidates(type, minRegionX, minRegionZ, maxRegionX, maxRegionZ));
        markCovered(covered, minRegionX, minRegionZ, maxRegionX, maxRegionZ);
    }

    private static int clampBlockCoordinate(final long coordinate) {
        return coordinate < Integer.MIN_VALUE
            ? Integer.MIN_VALUE
            : coordinate > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) coordinate;
    }

    private static double distance(final Marker marker, final int blockX, final int blockZ) {
        final long dx = marker.blockX() - (long) blockX;
        final long dz = marker.blockZ() - (long) blockZ;
        return Math.hypot(dx, dz);
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
