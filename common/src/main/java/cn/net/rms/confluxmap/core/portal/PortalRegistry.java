package cn.net.rms.confluxmap.core.portal;

import cn.net.rms.confluxmap.core.model.DimensionId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory, main-thread-only marker set for one world. Markers are keyed by
 * (dimension, chunk, kind) so repeated scans of the same chunk are idempotent
 * and a rescan that finds nothing removes exactly that chunk's markers.
 */
public final class PortalRegistry {
    private record ChunkKey(int chunkX, int chunkZ, PortalKind kind) {
    }

    private final Map<DimensionId, LinkedHashMap<ChunkKey, PortalMarker>> markers =
        new LinkedHashMap<>();

    /** True when a new marker appeared or an existing anchor moved. */
    public boolean apply(final PortalMarker marker) {
        final Map<ChunkKey, PortalMarker> byChunk = byDimension(marker.dimension());
        final PortalMarker previous = byChunk.put(key(marker), marker);
        return previous == null || !previous.equals(marker);
    }

    /**
     * Reconciles one chunk with a fresh scan result: markers of other chunks
     * and dimensions are untouched, while this chunk's markers become exactly
     * {@code found} (an empty list removes them all). Returns true when
     * anything changed.
     */
    public boolean applyChunkScan(
        final DimensionId dimension,
        final int chunkX,
        final int chunkZ,
        final List<PortalMarker> found
    ) {
        boolean changed = false;
        final Map<ChunkKey, PortalMarker> existing = markers.get(dimension);
        if ((existing == null || existing.isEmpty()) && found.isEmpty()) {
            return false;
        }
        final Map<ChunkKey, PortalMarker> byChunk = byDimension(dimension);
        for (final ChunkKey key : chunkKeys(byChunk, chunkX, chunkZ)) {
            if (findByKind(found, key.kind()) == null) {
                byChunk.remove(key);
                changed = true;
            }
        }
        for (final PortalMarker marker : found) {
            final PortalMarker previous = byChunk.put(key(marker), marker);
            if (previous == null || !previous.equals(marker)) {
                changed = true;
            }
        }
        if (byChunk.isEmpty()) {
            markers.remove(dimension);
        }
        return changed;
    }

    /** True when a block update in this chunk is worth a full rescan. */
    public boolean hasMarkersInChunk(final DimensionId dimension, final int chunkX, final int chunkZ) {
        final Map<ChunkKey, PortalMarker> byChunk = markers.get(dimension);
        if (byChunk == null) {
            return false;
        }
        for (final ChunkKey key : byChunk.keySet()) {
            if (key.chunkX() == chunkX && key.chunkZ() == chunkZ) {
                return true;
            }
        }
        return false;
    }

    /** Immutable snapshot for one dimension, in insertion order. */
    public List<PortalMarker> list(final DimensionId dimension) {
        final Map<ChunkKey, PortalMarker> byChunk = markers.get(dimension);
        return byChunk == null ? List.of() : List.copyOf(byChunk.values());
    }

    /** Immutable snapshot of every dimension, for persistence. */
    public Map<DimensionId, List<PortalMarker>> snapshot() {
        final Map<DimensionId, List<PortalMarker>> result = new LinkedHashMap<>();
        for (final Map.Entry<DimensionId, LinkedHashMap<ChunkKey, PortalMarker>> entry
            : markers.entrySet()) {
            result.put(entry.getKey(), List.copyOf(entry.getValue().values()));
        }
        return result;
    }

    /** Replaces all state; used when a session loads its file. */
    public void load(final Map<DimensionId, List<PortalMarker>> loaded) {
        markers.clear();
        for (final Map.Entry<DimensionId, List<PortalMarker>> entry : loaded.entrySet()) {
            for (final PortalMarker marker : entry.getValue()) {
                byDimension(entry.getKey()).put(key(marker), marker);
            }
        }
    }

    public boolean isEmpty() {
        return markers.isEmpty();
    }

    private Map<ChunkKey, PortalMarker> byDimension(final DimensionId dimension) {
        return markers.computeIfAbsent(dimension, ignored -> new LinkedHashMap<>());
    }

    private static ChunkKey key(final PortalMarker marker) {
        return new ChunkKey(marker.chunkX(), marker.chunkZ(), marker.kind());
    }

    private static List<ChunkKey> chunkKeys(
        final Map<ChunkKey, PortalMarker> byChunk,
        final int chunkX,
        final int chunkZ
    ) {
        final List<ChunkKey> result = new ArrayList<>();
        for (final ChunkKey key : byChunk.keySet()) {
            if (key.chunkX() == chunkX && key.chunkZ() == chunkZ) {
                result.add(key);
            }
        }
        return result;
    }

    private static PortalMarker findByKind(final List<PortalMarker> found, final PortalKind kind) {
        for (final PortalMarker marker : found) {
            if (marker.kind() == kind) {
                return marker;
            }
        }
        return null;
    }
}
