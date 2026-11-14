package cn.net.rms.confluxmap.mc.predict;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.predict.ChestLootPreview;
import cn.net.rms.confluxmap.core.predict.PredictionDimensions;
import cn.net.rms.confluxmap.core.predict.PredictionState;
import cn.net.rms.confluxmap.core.predict.QuadrantLayout;
import cn.net.rms.confluxmap.core.predict.SearchOrder;
import cn.net.rms.confluxmap.core.predict.StructureIndex;
import cn.net.rms.confluxmap.core.predict.StructureLootSupport;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.nativepredict.CubiomesContext;
import cn.net.rms.confluxmap.nativepredict.CubiomesContexts;
import cn.net.rms.confluxmap.nativepredict.NativeLib;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/**
 * Owns structure-marker session state, native candidate lookup, and persistence. UI code only
 * supplies a visible block rectangle and renders the returned markers.
 */
public final class StructureMarkerService {
    /** Native status of {@code cfxStructureChests}/{@code cfxRollChestLoot} for a native fault. */
    private static final int NATIVE_FAULT = -2;

    /** Serializes loot queries' native section: the fork's loot-table state is process-global. */
    private static final Object LOOT_ROLL_LOCK = new Object();

    private final Path cacheRoot;
    private final PredictionState prediction;
    private final BooleanSupplier structureSearchAllowed;
    private final MapExecutors executors;
    private final StructureViewportQuery viewportQueries;
    private StructureIndex current;
    private DimensionId currentDimension;
    private long generation;

    public StructureMarkerService(
        final Path cacheRoot,
        final PredictionState prediction,
        final BooleanSupplier structureSearchAllowed,
        final MapExecutors executors
    ) {
        this.cacheRoot = cacheRoot;
        this.prediction = prediction;
        this.structureSearchAllowed = structureSearchAllowed;
        this.executors = executors;
        viewportQueries = new StructureViewportQuery(
            executors.workers(),
            request -> request.index().query(
                request.minBlockX(),
                request.maxBlockX(),
                request.minBlockZ(),
                request.maxBlockZ(),
                request.includedTypes()
            ),
            request -> executors.io().execute(request.index()::save)
        );
    }

    public synchronized void onSessionChanged(final SessionGuard.Session session) {
        final StructureIndex ending = current;
        generation++;
        viewportQueries.clear();
        currentDimension = session.active() ? session.dimension() : null;
        current = session.active()
            ? new StructureIndex(
                prediction.manualSeed()
                    ? cacheRoot.resolve("manual-seeds").resolve(Long.toUnsignedString(prediction.seed(), 16))
                    : cacheRoot,
                session.world(),
                session.dimension(),
                prediction.mcVersion(),
                new StructureIndex.CandidateProvider() {
                    @Override
                    public long[] candidates(
                        final StructureIndex.StructureType type,
                        final int regionX,
                        final int regionZ
                    ) {
                        return positions(StructureMarkerService.this.detailedCandidates(
                            session, type, regionX, regionZ, regionX, regionZ
                        ));
                    }

                    @Override
                    public StructureIndex.Candidate[] detailedCandidates(
                        final StructureIndex.StructureType type,
                        final int minRegionX,
                        final int minRegionZ,
                        final int maxRegionX,
                        final int maxRegionZ
                    ) {
                        return StructureMarkerService.this.detailedCandidates(
                            session, type, minRegionX, minRegionZ, maxRegionX, maxRegionZ
                        );
                    }

                    @Override
                    public Optional<StructureIndex.Candidate> nearestCandidate(
                        final StructureIndex.StructureType type,
                        final int blockX,
                        final int blockZ,
                        final int maxRadius
                    ) {
                        return StructureMarkerService.this.nearestCandidate(
                            session, type, blockX, blockZ, maxRadius
                        );
                    }
                }
            )
            : null;
        if (ending != null) {
            executors.io().execute(ending::save);
        }
    }

    public synchronized List<StructureIndex.Marker> query(
        final int minBlockX,
        final int maxBlockX,
        final int minBlockZ,
        final int maxBlockZ
    ) {
        return current == null || !structureSearchAllowed.getAsBoolean()
            ? List.of()
            : filterQuadrants(current.query(minBlockX, maxBlockX, minBlockZ, maxBlockZ));
    }

    public synchronized List<StructureIndex.Marker> query(
        final int minBlockX,
        final int maxBlockX,
        final int minBlockZ,
        final int maxBlockZ,
        final double blocksPerPixel
    ) {
        return currentDimension == null
            ? List.of()
            : query(
                minBlockX,
                maxBlockX,
                minBlockZ,
                maxBlockZ,
                blocksPerPixel,
                availableTypes(currentDimension)
            );
    }

    public synchronized List<StructureIndex.Marker> query(
        final int minBlockX,
        final int maxBlockX,
        final int minBlockZ,
        final int maxBlockZ,
        final double blocksPerPixel,
        final Set<StructureIndex.StructureType> includedTypes
    ) {
        if (current == null || currentDimension == null || includedTypes.isEmpty()
            || !structureSearchAllowed.getAsBoolean()) {
            return List.of();
        }
        final EnumSet<StructureIndex.StructureType> visible =
            EnumSet.noneOf(StructureIndex.StructureType.class);
        visible.addAll(includedTypes);
        visible.retainAll(availableTypes(currentDimension));
        visible.removeIf(type -> !type.displaysAt(blocksPerPixel));
        return filterQuadrants(current.query(minBlockX, maxBlockX, minBlockZ, maxBlockZ, visible));
    }

    /**
     * Render-thread viewport seam. A new native lookup is queued on a map worker and this call
     * returns an empty list until the newest requested rectangle has completed.
     */
    public synchronized List<StructureIndex.Marker> queryViewport(
        final int minBlockX,
        final int maxBlockX,
        final int minBlockZ,
        final int maxBlockZ,
        final double blocksPerPixel,
        final Set<StructureIndex.StructureType> includedTypes
    ) {
        if (current == null || currentDimension == null || includedTypes.isEmpty()
            || !structureSearchAllowed.getAsBoolean()) {
            viewportQueries.clear();
            return List.of();
        }
        final EnumSet<StructureIndex.StructureType> visible =
            EnumSet.noneOf(StructureIndex.StructureType.class);
        visible.addAll(includedTypes);
        visible.retainAll(availableTypes(currentDimension));
        visible.removeIf(type -> !type.displaysAt(blocksPerPixel));
        if (visible.isEmpty()) {
            viewportQueries.clear();
            return List.of();
        }
        return filterQuadrants(viewportQueries.request(new StructureViewportQuery.Request(
            generation,
            current,
            minBlockX,
            maxBlockX,
            minBlockZ,
            maxBlockZ,
            blocksPerPixel,
            visible
        )));
    }

    public synchronized EnumSet<StructureIndex.StructureType> availableTypes(final DimensionId dimension) {
        return structureSearchAllowed.getAsBoolean()
            ? StructureIndex.StructureType.availableIn(prediction.mcVersion(), dimension)
            : EnumSet.noneOf(StructureIndex.StructureType.class);
    }

    public int mcVersion() {
        return prediction.mcVersion();
    }

    public synchronized Optional<StructureIndex.Marker> findNearest(
        final StructureIndex.StructureType type,
        final int blockX,
        final int blockZ,
        final int maxRadius
    ) {
        if (current == null || !structureSearchAllowed.getAsBoolean()) {
            return Optional.empty();
        }
        return current.findNearest(type, blockX, blockZ, maxRadius)
            .filter(marker -> predictsTerrainAt(marker));
    }

    /** Returns at most 32 nearest candidates from a bounded native lookup area. */
    public synchronized List<StructureIndex.Marker> findNearestCandidates(
        final StructureIndex.StructureType type,
        final int blockX,
        final int blockZ,
        final int maxCandidates
    ) {
        return current == null || !structureSearchAllowed.getAsBoolean()
            ? List.of()
            : filterQuadrants(
                current.findNearestCandidates(type, blockX, blockZ, maxCandidates)
            );
    }

    /** Returns candidates inside the requested radius without exceeding the index query budget. */
    public List<StructureIndex.Marker> findCandidates(
        final StructureIndex.StructureType type,
        final int blockX,
        final int blockZ,
        final int maxRadius,
        final int limit
    ) {
        return findCandidates(type, blockX, blockZ, maxRadius, limit, OptionalInt.empty());
    }

    public List<StructureIndex.Marker> findCandidates(
        final StructureIndex.StructureType type,
        final int blockX,
        final int blockZ,
        final int maxRadius,
        final int limit,
        final OptionalInt variant
    ) {
        return findCandidates(type, blockX, blockZ, maxRadius, limit, variant, SearchOrder.NEAREST);
    }

    public List<StructureIndex.Marker> findCandidates(
        final StructureIndex.StructureType type,
        final int blockX,
        final int blockZ,
        final int maxRadius,
        final int limit,
        final OptionalInt variant,
        final SearchOrder order
    ) {
        return searchCandidates(type, blockX, blockZ, maxRadius, limit, variant, order).markers();
    }

    public StructureIndex.CandidateSearch searchCandidates(
        final StructureIndex.StructureType type,
        final int blockX,
        final int blockZ,
        final int maxRadius,
        final int limit,
        final OptionalInt variant,
        final SearchOrder order
    ) {
        return searchCandidates(type, blockX, blockZ, maxRadius, limit, variant, order, () -> false);
    }

    /**
     * Runs the candidate walk without holding the service monitor across the native batches:
     * render-thread viewport queries only ever wait for one marker-map mutation, never for the
     * whole walk. The walk cancels itself when {@code cancelled} fires or the session
     * generation moves on, so closing the screen or changing worlds stops the work instead of
     * burning the remaining budget.
     */
    public StructureIndex.CandidateSearch searchCandidates(
        final StructureIndex.StructureType type,
        final int blockX,
        final int blockZ,
        final int maxRadius,
        final int limit,
        final OptionalInt variant,
        final SearchOrder order,
        final BooleanSupplier cancelled
    ) {
        final StructureIndex index;
        final DimensionId dimensionAtStart;
        final long generationAtStart;
        synchronized (this) {
            if (current == null || !structureSearchAllowed.getAsBoolean()) {
                return new StructureIndex.CandidateSearch(List.of(), true);
            }
            index = current;
            dimensionAtStart = currentDimension;
            generationAtStart = generation;
        }
        final StructureIndex.CandidateSearch found = index.searchCandidates(
            type, blockX, blockZ, maxRadius, limit, variant, order,
            () -> cancelled.getAsBoolean() || activeGeneration() != generationAtStart
        );
        synchronized (this) {
            return new StructureIndex.CandidateSearch(
                filterQuadrants(found.markers(), dimensionAtStart), found.complete()
            );
        }
    }

    private synchronized long activeGeneration() {
        return generation;
    }

    /**
     * quadra-gen's non-noise quadrants keep structure <em>data</em> but never place structure
     * content (flat quadrants cancel {@code createStructures} outright), so markers there would
     * promise structures that do not visibly exist. {@code null} dimension means no session yet.
     */
    private boolean predictsTerrainAt(final StructureIndex.Marker marker) {
        return predictsTerrainAt(marker, currentDimension);
    }

    private boolean predictsTerrainAt(
        final StructureIndex.Marker marker,
        final DimensionId dimension
    ) {
        final QuadrantLayout layout = dimension == null
            ? null : prediction.quadrantLayout(dimension);
        return layout == null || layout.predictsTerrainAt(marker.blockX(), marker.blockZ());
    }

    private List<StructureIndex.Marker> filterQuadrants(final List<StructureIndex.Marker> markers) {
        return filterQuadrants(markers, currentDimension);
    }

    private List<StructureIndex.Marker> filterQuadrants(
        final List<StructureIndex.Marker> markers,
        final DimensionId dimension
    ) {
        if (dimension == null || prediction.quadrantLayout(dimension) == null) {
            return markers;
        }
        final List<StructureIndex.Marker> visible = new ArrayList<>(markers.size());
        for (final StructureIndex.Marker marker : markers) {
            if (predictsTerrainAt(marker, dimension)) {
                visible.add(marker);
            }
        }
        return visible;
    }

    public synchronized void flush() {
        final StructureIndex snapshot = current;
        if (snapshot != null) {
            executors.io().execute(snapshot::save);
        }
    }

    /** Whether this session's mc version has chest loot for {@code type}. */
    public boolean lootSupported(final StructureIndex.StructureType type) {
        return StructureLootSupport.supports(type, prediction.mcVersion());
    }

    /**
     * Chest loot previews for one structure instance, resolved on a worker with the same gating
     * and session discipline as {@link #detailedCandidates}. Already-completed and empty when
     * the query cannot run (no session, search forbidden, type unsupported for the dimension or
     * version); a session generation change or a native fault completes the scheduled future
     * empty. The context comes from the executing worker's per-thread cache, so enumeration and
     * rolls mutate that worker's own handle state; the native section itself additionally
     * serializes on {@link #LOOT_ROLL_LOCK} because the fork's loot-table state is process-global.
     */
    public CompletableFuture<List<ChestLootPreview>> lootPreviewAsync(
        final StructureIndex.Marker marker
    ) {
        final int mcVersion;
        final long seed;
        final int nativeDim;
        final int flags;
        final long generationAtStart;
        synchronized (this) {
            final DimensionId dimension = currentDimension;
            if (current == null || dimension == null || !structureSearchAllowed.getAsBoolean()
                || !marker.type().supports(prediction.mcVersion(), dimension)
                || !prediction.structuresCubiomesBacked(dimension)
                || !StructureLootSupport.supports(marker.type(), prediction.mcVersion())) {
                return CompletableFuture.completedFuture(List.of());
            }
            nativeDim = PredictionDimensions.nativeStructureDim(dimension);
            if (nativeDim == Integer.MIN_VALUE) {
                return CompletableFuture.completedFuture(List.of());
            }
            mcVersion = prediction.mcVersion();
            seed = prediction.seed();
            flags = prediction.cubiomesFlags(dimension);
            generationAtStart = generation;
        }
        return CompletableFuture.supplyAsync(
            () -> lootPreview(marker, mcVersion, seed, nativeDim, flags, generationAtStart),
            executors.workers()
        );
    }

    private List<ChestLootPreview> lootPreview(
        final StructureIndex.Marker marker,
        final int mcVersion,
        final long seed,
        final int nativeDim,
        final int flags,
        final long generationAtStart
    ) {
        if (activeGeneration() != generationAtStart) {
            return List.of();
        }
        try {
            final CubiomesContext context = CubiomesContexts.get(mcVersion, seed, nativeDim, flags);
            if (context == null) {
                return List.of();
            }
            final int[] positions = new int[CubiomesContext.MAX_CHESTS_PER_STRUCTURE * 3];
            final long[] lootSeeds = new long[CubiomesContext.MAX_CHESTS_PER_STRUCTURE];
            final String[] tables = new String[CubiomesContext.MAX_CHESTS_PER_STRUCTURE];
            final List<ChestLootPreview> previews;
            synchronized (LOOT_ROLL_LOCK) {
                final int chests = context.structureChests(
                    marker.type().nativeId(), marker.blockX(), marker.blockZ(),
                    CubiomesContext.MAX_CHESTS_PER_STRUCTURE, positions, lootSeeds, tables
                );
                if (chests == NATIVE_FAULT) {
                    throw new IllegalStateException("chest enumeration reported a native fault");
                }
                if (chests <= 0) {
                    // -1: unsupported for this type or version - degrade to no previews, never a
                    // native-disable.
                    return List.of();
                }
                final int written = Math.min(chests, CubiomesContext.MAX_CHESTS_PER_STRUCTURE);
                previews = new ArrayList<>(written);
                for (int chest = 0; chest < written; chest++) {
                    previews.add(rollChest(context, positions, lootSeeds, tables, chest));
                }
            }
            return activeGeneration() != generationAtStart ? List.of() : previews;
        } catch (final Throwable fault) {
            NativeLib.disableForSession(fault);
            return List.of();
        }
    }

    private ChestLootPreview rollChest(
        final CubiomesContext context,
        final int[] positions,
        final long[] lootSeeds,
        final String[] tables,
        final int chest
    ) {
        final int[] counts = new int[CubiomesContext.MAX_LOOT_STACKS + 1];
        final int[] itemCounts = new int[CubiomesContext.MAX_LOOT_STACKS];
        final String[] items = new String[CubiomesContext.MAX_LOOT_STACKS];
        final String[] effects = new String[CubiomesContext.MAX_LOOT_STACKS];
        final int enchantCapacity =
            CubiomesContext.MAX_LOOT_STACKS * CubiomesContext.MAX_ENCHANTS_PER_STACK;
        final String[] enchantNames = new String[enchantCapacity];
        final int[] enchantLevels = new int[enchantCapacity];
        final int status = context.rollChestLoot(
            tables[chest], lootSeeds[chest], CubiomesContext.MAX_LOOT_STACKS,
            counts, itemCounts, items, effects, enchantNames, enchantLevels, enchantCapacity
        );
        if (status == NATIVE_FAULT) {
            throw new IllegalStateException("loot roll reported a native fault");
        }
        return new ChestLootPreview(
            new ChestLootPreview.ChestLoot(
                positions[chest * 3], positions[chest * 3 + 1], positions[chest * 3 + 2],
                tables[chest]
            ),
            lootStacks(status, counts, itemCounts, items, effects, enchantNames, enchantLevels),
            status >= 0
        );
    }

    private static List<ChestLootPreview.LootStack> lootStacks(
        final int status,
        final int[] counts,
        final int[] itemCounts,
        final String[] items,
        final String[] effects,
        final String[] enchantNames,
        final int[] enchantLevels
    ) {
        if (status <= 0) {
            return List.of();
        }
        final List<ChestLootPreview.LootStack> stacks = new ArrayList<>(status);
        int enchantBase = 0;
        for (int stack = 0; stack < status; stack++) {
            final int enchantCount = counts[1 + stack];
            final List<ChestLootPreview.LootEnchantment> enchantments =
                new ArrayList<>(enchantCount);
            for (int enchantment = 0; enchantment < enchantCount; enchantment++) {
                enchantments.add(new ChestLootPreview.LootEnchantment(
                    enchantNames[enchantBase + enchantment],
                    enchantLevels[enchantBase + enchantment]
                ));
            }
            enchantBase += enchantCount;
            stacks.add(new ChestLootPreview.LootStack(
                items[stack], itemCounts[stack], enchantments, effects[stack]
            ));
        }
        return stacks;
    }

    private StructureIndex.Candidate[] detailedCandidates(
        final SessionGuard.Session session,
        final StructureIndex.StructureType type,
        final int minRegionX,
        final int minRegionZ,
        final int maxRegionX,
        final int maxRegionZ
    ) {
        if (!structureSearchAllowed.getAsBoolean()
            || !type.supports(prediction.mcVersion(), session.dimension())
            || !prediction.structuresCubiomesBacked(session.dimension())) {
            return new StructureIndex.Candidate[0];
        }
        final int nativeDim = PredictionDimensions.nativeStructureDim(session.dimension());
        if (nativeDim == Integer.MIN_VALUE) {
            return new StructureIndex.Candidate[0];
        }
        try {
            final CubiomesContext context = CubiomesContexts.get(
                prediction.mcVersion(),
                prediction.seed(),
                nativeDim,
                prediction.cubiomesFlags(session.dimension())
            );
            if (context == null) {
                return new StructureIndex.Candidate[0];
            }
            if (type.globalPlacement()) {
                final long[] positions = new long[128];
                final int count = context.strongholds(positions);
                return candidates(positions, new int[positions.length], count);
            }
            final long cells = ((long) maxRegionX - minRegionX + 1L)
                * ((long) maxRegionZ - minRegionZ + 1L);
            if (cells <= 0L || cells > 1_048_576L) {
                return new StructureIndex.Candidate[0];
            }
            final long[] positions = new long[(int) cells];
            final int[] variants = new int[(int) cells];
            final int count = context.viableStructures(
                type.nativeId(), minRegionX, minRegionZ, maxRegionX, maxRegionZ,
                positions, variants
            );
            return candidates(positions, variants, count);
        } catch (final Throwable fault) {
            NativeLib.disableForSession(fault);
            return new StructureIndex.Candidate[0];
        }
    }

    private Optional<StructureIndex.Candidate> nearestCandidate(
        final SessionGuard.Session session,
        final StructureIndex.StructureType type,
        final int blockX,
        final int blockZ,
        final int maxRadius
    ) {
        if (!structureSearchAllowed.getAsBoolean()
            || !type.supports(prediction.mcVersion(), session.dimension())
            || !prediction.structuresCubiomesBacked(session.dimension())) {
            return Optional.empty();
        }
        final int nativeDim = PredictionDimensions.nativeStructureDim(session.dimension());
        if (nativeDim == Integer.MIN_VALUE) {
            return Optional.empty();
        }
        try {
            final CubiomesContext context = CubiomesContexts.get(
                prediction.mcVersion(),
                prediction.seed(),
                nativeDim,
                prediction.cubiomesFlags(session.dimension())
            );
            if (context == null) {
                return Optional.empty();
            }
            final long[] position = new long[1];
            final int[] variant = new int[1];
            if (!context.nearestStructure(
                type.nativeId(), blockX, blockZ, maxRadius, position, variant
            )) {
                return Optional.empty();
            }
            return Optional.of(candidate(position[0], variant[0]));
        } catch (final Throwable fault) {
            NativeLib.disableForSession(fault);
            return Optional.empty();
        }
    }

    private static StructureIndex.Candidate[] candidates(
        final long[] positions,
        final int[] variants,
        final int count
    ) {
        if (count <= 0) {
            return new StructureIndex.Candidate[0];
        }
        final int size = Math.min(count, Math.min(positions.length, variants.length));
        final StructureIndex.Candidate[] candidates = new StructureIndex.Candidate[size];
        for (int index = 0; index < size; index++) {
            candidates[index] = candidate(positions[index], variants[index]);
        }
        return candidates;
    }

    private static StructureIndex.Candidate candidate(final long position, final int variant) {
        return new StructureIndex.Candidate(
            (int) (position >>> 32), (int) position, variant
        );
    }

    private static long[] positions(final StructureIndex.Candidate[] candidates) {
        final long[] positions = new long[candidates.length];
        for (int index = 0; index < candidates.length; index++) {
            final StructureIndex.Candidate candidate = candidates[index];
            positions[index] = ((long) candidate.blockX() << 32)
                | (candidate.blockZ() & 0xFFFF_FFFFL);
        }
        return positions;
    }
}
