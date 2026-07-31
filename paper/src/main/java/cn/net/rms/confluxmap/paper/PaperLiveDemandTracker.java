package cn.net.rms.confluxmap.paper;

import cn.net.rms.confluxmap.core.net.MapRegionSyncSubscribeC2S;
import cn.net.rms.confluxmap.core.net.MapRegionViewReqC2S;
import cn.net.rms.confluxmap.core.net.MapSyncSubscribeC2S;
import cn.net.rms.confluxmap.core.net.MapViewReqC2S;
import cn.net.rms.confluxmap.core.util.ChunkRegionSlice;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Tracks the same short-lived request and persistent viewport demand as Fabric live summaries. */
final class PaperLiveDemandTracker {
    private static final long REQUEST_TTL_NANOS = 2_000_000_000L;

    private record Demand(
        int dimensionIndex,
        int minChunkX,
        int minChunkZ,
        int maxChunkX,
        int maxChunkZ,
        long expiresAtNanos
    ) {
        boolean contains(
            final int dimension,
            final int chunkX,
            final int chunkZ,
            final long nowNanos
        ) {
            return dimensionIndex == dimension && nowNanos < expiresAtNanos
                && chunkX >= minChunkX && chunkX <= maxChunkX
                && chunkZ >= minChunkZ && chunkZ <= maxChunkZ;
        }
    }

    private final List<Demand> requests = new ArrayList<>();
    private final Map<UUID, Demand> watched = new HashMap<>();

    void nominate(final MapViewReqC2S request, final long nowNanos) {
        final long chunksPerTile = 16L << request.lod();
        final long expiresAt = nowNanos + REQUEST_TTL_NANOS;
        for (final MapViewReqC2S.TileReq tile : request.tiles()) {
            final long minX = (long) tile.tileX() * chunksPerTile;
            final long minZ = (long) tile.tileZ() * chunksPerTile;
            final long maxX = minX + chunksPerTile - 1L;
            final long maxZ = minZ + chunksPerTile - 1L;
            if (!insideInt(minX, maxX) || !insideInt(minZ, maxZ)) {
                continue;
            }
            requests.add(new Demand(
                request.dimIndex(), (int) minX, (int) minZ, (int) maxX, (int) maxZ,
                expiresAt
            ));
        }
    }

    void nominate(final MapRegionViewReqC2S request, final long nowNanos) {
        final long expiresAt = nowNanos + REQUEST_TTL_NANOS;
        for (final MapRegionViewReqC2S.RegionReq region : request.regions()) {
            final ChunkRegionSlice slice = region.slice();
            requests.add(new Demand(
                request.dimIndex(),
                slice.minChunkX(),
                slice.minChunkZ(),
                slice.minChunkX() + slice.width() - 1,
                slice.minChunkZ() + slice.height() - 1,
                expiresAt
            ));
        }
    }

    boolean watch(final UUID playerId, final MapSyncSubscribeC2S request) {
        if (!request.active()) {
            watched.remove(playerId);
            return true;
        }
        final long chunksPerTile = 16L << request.lod();
        final long minX = (long) request.minTileX() * chunksPerTile;
        final long minZ = (long) request.minTileZ() * chunksPerTile;
        final long maxX = ((long) request.maxTileX() + 1L) * chunksPerTile - 1L;
        final long maxZ = ((long) request.maxTileZ() + 1L) * chunksPerTile - 1L;
        if (!insideInt(minX, maxX) || !insideInt(minZ, maxZ)) {
            return false;
        }
        watched.put(playerId, new Demand(
            request.dimIndex(), (int) minX, (int) minZ, (int) maxX, (int) maxZ,
            Long.MAX_VALUE
        ));
        return true;
    }

    boolean watch(final UUID playerId, final MapRegionSyncSubscribeC2S request) {
        if (!request.active()) {
            watched.remove(playerId);
            return true;
        }
        watched.put(playerId, new Demand(
            request.dimIndex(),
            request.minChunkX(),
            request.minChunkZ(),
            request.maxChunkX(),
            request.maxChunkZ(),
            Long.MAX_VALUE
        ));
        return true;
    }

    boolean contains(
        final int dimension,
        final int chunkX,
        final int chunkZ,
        final long nowNanos
    ) {
        requests.removeIf(demand -> nowNanos >= demand.expiresAtNanos());
        for (final Demand demand : requests) {
            if (demand.contains(dimension, chunkX, chunkZ, nowNanos)) {
                return true;
            }
        }
        for (final Demand demand : watched.values()) {
            if (demand.contains(dimension, chunkX, chunkZ, nowNanos)) {
                return true;
            }
        }
        return false;
    }

    void tick(final long nowNanos) {
        requests.removeIf(demand -> nowNanos >= demand.expiresAtNanos());
    }

    int pendingRequests() {
        return requests.size();
    }

    void remove(final UUID playerId) {
        watched.remove(playerId);
    }

    void clear() {
        requests.clear();
        watched.clear();
    }

    private static boolean insideInt(final long minimum, final long maximum) {
        return minimum >= Integer.MIN_VALUE && maximum <= Integer.MAX_VALUE;
    }
}
