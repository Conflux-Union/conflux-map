package cn.net.rms.confluxmap.mc.predict;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.predict.PredictionDimensions;
import cn.net.rms.confluxmap.core.predict.PredictionState;
import cn.net.rms.confluxmap.core.predict.StructureIndex;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.nativepredict.CubiomesContext;
import cn.net.rms.confluxmap.nativepredict.CubiomesContexts;
import cn.net.rms.confluxmap.nativepredict.NativeLib;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Owns structure-marker session state, native candidate lookup, and persistence. UI code only
 * supplies a visible block rectangle and renders the returned markers.
 */
public final class StructureMarkerService {
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
            : current.query(minBlockX, maxBlockX, minBlockZ, maxBlockZ);
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
        return current.query(minBlockX, maxBlockX, minBlockZ, maxBlockZ, visible);
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
        return viewportQueries.request(new StructureViewportQuery.Request(
            generation,
            current,
            minBlockX,
            maxBlockX,
            minBlockZ,
            maxBlockZ,
            blocksPerPixel,
            visible
        ));
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
        return current == null || !structureSearchAllowed.getAsBoolean()
            ? Optional.empty()
            : current.findNearest(type, blockX, blockZ, maxRadius);
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
            : current.findNearestCandidates(type, blockX, blockZ, maxCandidates);
    }

    /** Returns candidates inside the requested radius without exceeding the index query budget. */
    public synchronized List<StructureIndex.Marker> findCandidates(
        final StructureIndex.StructureType type,
        final int blockX,
        final int blockZ,
        final int maxRadius,
        final int limit
    ) {
        return findCandidates(type, blockX, blockZ, maxRadius, limit, OptionalInt.empty());
    }

    public synchronized List<StructureIndex.Marker> findCandidates(
        final StructureIndex.StructureType type,
        final int blockX,
        final int blockZ,
        final int maxRadius,
        final int limit,
        final OptionalInt variant
    ) {
        return current == null || !structureSearchAllowed.getAsBoolean()
            ? List.of()
            : current.findCandidates(type, blockX, blockZ, maxRadius, limit, variant);
    }

    public synchronized void flush() {
        final StructureIndex snapshot = current;
        if (snapshot != null) {
            executors.io().execute(snapshot::save);
        }
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
