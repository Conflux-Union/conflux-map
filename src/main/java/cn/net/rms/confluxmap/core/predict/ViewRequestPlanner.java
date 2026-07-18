package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.net.MapViewReqC2S;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Pure nearest-first visible-tile planner for the companion sync loop. */
public final class ViewRequestPlanner {
    public record Tile(int tileX, int tileZ, long sinceRevision, long lastRequestNanos, boolean emptyPresence) {
    }

    public record Viewport(int minTileX, int maxTileX, int minTileZ, int maxTileZ, int centerTileX, int centerTileZ) {
    }

    private ViewRequestPlanner() {
    }

    public static List<MapViewReqC2S.TileReq> plan(
        final Viewport viewport,
        final List<Tile> candidates,
        final int maxTiles,
        final long nowNanos,
        final long normalCooldownNanos,
        final long emptyCooldownNanos
    ) {
        final List<Tile> eligible = new ArrayList<>();
        for (final Tile candidate : candidates) {
            if (candidate.tileX() < viewport.minTileX() || candidate.tileX() > viewport.maxTileX()
                || candidate.tileZ() < viewport.minTileZ() || candidate.tileZ() > viewport.maxTileZ()) {
                continue;
            }
            final long cooldown = candidate.emptyPresence() ? emptyCooldownNanos : normalCooldownNanos;
            if (candidate.lastRequestNanos() != Long.MIN_VALUE && nowNanos - candidate.lastRequestNanos() < cooldown) {
                continue;
            }
            eligible.add(candidate);
        }
        eligible.sort(Comparator.comparingLong(tile -> distanceSquared(tile, viewport)));
        final List<MapViewReqC2S.TileReq> result = new ArrayList<>(Math.min(maxTiles, eligible.size()));
        for (int i = 0; i < eligible.size() && i < Math.max(0, maxTiles); i++) {
            final Tile tile = eligible.get(i);
            result.add(new MapViewReqC2S.TileReq(tile.tileX(), tile.tileZ(), tile.sinceRevision()));
        }
        return result;
    }

    public static List<MapViewReqC2S.TileReq> plan(
        final Viewport viewport, final Map<Long, Long> cachedRevisions, final int maxTiles
    ) {
        final List<Tile> candidates = new ArrayList<>();
        for (final Map.Entry<Long, Long> entry : cachedRevisions.entrySet()) {
            final int x = (int) (entry.getKey() >>> 32);
            final int z = (int) (long) entry.getKey();
            candidates.add(new Tile(x, z, entry.getValue(), Long.MIN_VALUE, false));
        }
        return plan(viewport, candidates, maxTiles, 0L, 0L, 0L);
    }

    private static long distanceSquared(final Tile tile, final Viewport viewport) {
        final long dx = tile.tileX() - viewport.centerTileX();
        final long dz = tile.tileZ() - viewport.centerTileZ();
        return dx * dx + dz * dz;
    }

    public static long key(final int tileX, final int tileZ) {
        return ((long) tileX << 32) ^ (tileZ & 0xFFFFFFFFL);
    }
}
