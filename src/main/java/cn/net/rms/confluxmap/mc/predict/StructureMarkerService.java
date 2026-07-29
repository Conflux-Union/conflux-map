package cn.net.rms.confluxmap.mc.predict;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.predict.PredictionDimensions;
import cn.net.rms.confluxmap.core.predict.PredictionState;
import cn.net.rms.confluxmap.core.predict.StructureIndex;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.nativepredict.CubiomesContext;
import cn.net.rms.confluxmap.nativepredict.CubiomesContexts;
import cn.net.rms.confluxmap.nativepredict.NativeLib;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
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
    private StructureIndex current;
    private DimensionId currentDimension;

    public StructureMarkerService(
        final Path cacheRoot,
        final PredictionState prediction,
        final BooleanSupplier structureSearchAllowed
    ) {
        this.cacheRoot = cacheRoot;
        this.prediction = prediction;
        this.structureSearchAllowed = structureSearchAllowed;
    }

    public synchronized void onSessionChanged(final SessionGuard.Session session) {
        flush();
        currentDimension = session.active() ? session.dimension() : null;
        current = session.active()
            ? new StructureIndex(
                cacheRoot,
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
                        return StructureMarkerService.this.candidates(
                            session, type, regionX, regionZ, regionX, regionZ
                        );
                    }

                    @Override
                    public long[] candidates(
                        final StructureIndex.StructureType type,
                        final int minRegionX,
                        final int minRegionZ,
                        final int maxRegionX,
                        final int maxRegionZ
                    ) {
                        return StructureMarkerService.this.candidates(
                            session, type, minRegionX, minRegionZ, maxRegionX, maxRegionZ
                        );
                    }

                    @Override
                    public OptionalLong nearest(
                        final StructureIndex.StructureType type,
                        final int blockX,
                        final int blockZ,
                        final int maxRadius
                    ) {
                        return StructureMarkerService.this.nearest(
                            session, type, blockX, blockZ, maxRadius
                        );
                    }
                }
            )
            : null;
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

    public synchronized void flush() {
        if (current != null) {
            current.save();
        }
    }

    private long[] candidates(
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
            return new long[0];
        }
        final int nativeDim = PredictionDimensions.nativeStructureDim(session.dimension());
        if (nativeDim == Integer.MIN_VALUE) {
            return new long[0];
        }
        try {
            final CubiomesContext context = CubiomesContexts.get(
                prediction.mcVersion(),
                prediction.seed(),
                nativeDim,
                prediction.cubiomesFlags(session.dimension())
            );
            if (context == null) {
                return new long[0];
            }
            final long[] positions;
            final int count;
            if (type.globalPlacement()) {
                positions = new long[128];
                count = context.strongholds(positions);
            } else {
                final long cells = ((long) maxRegionX - minRegionX + 1L)
                    * ((long) maxRegionZ - minRegionZ + 1L);
                if (cells <= 0L || cells > 1_048_576L) {
                    return new long[0];
                }
                positions = new long[(int) cells];
                count = context.viableStructures(
                    type.nativeId(), minRegionX, minRegionZ, maxRegionX, maxRegionZ, positions
                );
            }
            if (count <= 0) {
                return new long[0];
            }
            return Arrays.copyOf(positions, Math.min(count, positions.length));
        } catch (final Throwable fault) {
            NativeLib.disableForSession(fault);
            return new long[0];
        }
    }

    private OptionalLong nearest(
        final SessionGuard.Session session,
        final StructureIndex.StructureType type,
        final int blockX,
        final int blockZ,
        final int maxRadius
    ) {
        if (!structureSearchAllowed.getAsBoolean()
            || !type.supports(prediction.mcVersion(), session.dimension())
            || !prediction.structuresCubiomesBacked(session.dimension())) {
            return OptionalLong.empty();
        }
        final int nativeDim = PredictionDimensions.nativeStructureDim(session.dimension());
        if (nativeDim == Integer.MIN_VALUE) {
            return OptionalLong.empty();
        }
        try {
            final CubiomesContext context = CubiomesContexts.get(
                prediction.mcVersion(),
                prediction.seed(),
                nativeDim,
                prediction.cubiomesFlags(session.dimension())
            );
            final long[] result = new long[1];
            return context != null
                && context.nearestStructure(type.nativeId(), blockX, blockZ, maxRadius, result)
                ? OptionalLong.of(result[0])
                : OptionalLong.empty();
        } catch (final Throwable fault) {
            NativeLib.disableForSession(fault);
            return OptionalLong.empty();
        }
    }
}
