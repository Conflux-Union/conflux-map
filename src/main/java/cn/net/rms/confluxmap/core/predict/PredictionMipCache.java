package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.model.TileKey;
import cn.net.rms.confluxmap.core.util.Argb;
import cn.net.rms.confluxmap.core.util.TileMath;
import java.util.Iterator;
import java.util.LinkedHashMap;

/** Bounded CPU mip cache for already-composed prediction tiles. */
final class PredictionMipCache {
    static final long MISSING = Long.MIN_VALUE;

    record Tile(
        int[] pixels,
        byte[] biomes,
        int[] surfaces,
        PredictionViewMode mode,
        boolean hasServerState,
        long freshnessValidatedAtMillis,
        long serverCoverageValidatedAtMillis
    ) {
        Tile {
            final int expected = BaselineGrid.PIXELS * BaselineGrid.PIXELS;
            if (pixels == null || pixels.length != expected
                || biomes == null || biomes.length != expected
                || surfaces == null || surfaces.length != expected
                || mode == null) {
                throw new IllegalArgumentException("invalid prediction mip tile");
            }
        }

        boolean isVisuallyReusableAt(final long nowMillis, final long ttlMillis) {
            return !hasServerState || isFresh(freshnessValidatedAtMillis, nowMillis, ttlMillis);
        }

        private static boolean isFresh(
            final long validatedAtMillis,
            final long nowMillis,
            final long ttlMillis
        ) {
            return validatedAtMillis > 0L
                && nowMillis >= validatedAtMillis
                && nowMillis - validatedAtMillis <= ttlMillis;
        }
    }

    private final int limit;
    private final LinkedHashMap<TileKey, Tile> tiles = new LinkedHashMap<>(32, 0.75f, true);

    PredictionMipCache(final int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("prediction mip cache limit must be positive");
        }
        this.limit = limit;
    }

    synchronized void clear() {
        tiles.clear();
    }

    synchronized void put(final TileKey key, final Tile tile) {
        tiles.put(key, tile);
        trim();
    }

    synchronized void remove(final TileKey key) {
        tiles.remove(key);
    }

    synchronized void removeCoverage(final TileKey area) {
        tiles.keySet().removeIf(key -> overlaps(area, key));
    }

    synchronized Tile lowerTile(final TileKey parent, final PredictionViewMode mode) {
        if (parent.lod() <= 0) {
            return null;
        }
        final Tile[] children = children(parent, mode);
        return children == null ? null : aggregate(children, mode);
    }

    synchronized long lowerCoverageValidatedAt(
        final TileKey parent,
        final PredictionViewMode mode
    ) {
        if (parent.lod() <= 0) {
            return MISSING;
        }
        long minimum = Long.MAX_VALUE;
        for (int childZ = 0; childZ < 2; childZ++) {
            for (int childX = 0; childX < 2; childX++) {
                final long childStamp = coverageValidatedAt(childKey(parent, childX, childZ), mode);
                if (childStamp == MISSING) {
                    return MISSING;
                }
                minimum = Math.min(minimum, childStamp);
            }
        }
        return minimum;
    }

    private Tile[] children(final TileKey parent, final PredictionViewMode mode) {
        final Tile[] children = new Tile[4];
        for (int childZ = 0; childZ < 2; childZ++) {
            for (int childX = 0; childX < 2; childX++) {
                final Tile child = cachedOrAggregate(childKey(parent, childX, childZ), mode);
                if (child == null) {
                    return null;
                }
                children[childZ * 2 + childX] = child;
            }
        }
        return children;
    }

    private Tile cachedOrAggregate(final TileKey key, final PredictionViewMode mode) {
        final Tile exact = tiles.get(key);
        if (exact != null && exact.mode() == mode) {
            return exact;
        }
        if (key.lod() <= 0) {
            return null;
        }
        final Tile[] children = children(key, mode);
        if (children == null) {
            return null;
        }
        final Tile aggregate = aggregate(children, mode);
        tiles.put(key, aggregate);
        trim();
        return aggregate;
    }

    private long coverageValidatedAt(final TileKey key, final PredictionViewMode mode) {
        final Tile exact = tiles.get(key);
        if (exact != null && exact.mode() == mode) {
            return exact.serverCoverageValidatedAtMillis();
        }
        return lowerCoverageValidatedAt(key, mode);
    }

    private static TileKey childKey(final TileKey parent, final int childX, final int childZ) {
        return new TileKey(
            parent.world(), parent.dimension(), parent.layerId(), parent.lod() - 1,
            parent.tileX() * 2 + childX, parent.tileZ() * 2 + childZ
        );
    }

    private static boolean overlaps(final TileKey first, final TileKey second) {
        if (!first.world().equals(second.world())
            || !first.dimension().equals(second.dimension())
            || !first.layerId().equals(second.layerId())) {
            return false;
        }
        return TileMath.overlaps(
            first.lod(), first.tileX(), first.tileZ(),
            second.lod(), second.tileX(), second.tileZ()
        );
    }

    private static Tile aggregate(final Tile[] children, final PredictionViewMode mode) {
        final int[] pixels = new int[BaselineGrid.PIXELS * BaselineGrid.PIXELS];
        final byte[] biomes = new byte[pixels.length];
        final int[] surfaces = new int[pixels.length];
        for (int z = 0; z < BaselineGrid.PIXELS; z++) {
            for (int x = 0; x < BaselineGrid.PIXELS; x++) {
                final int sourceX = x * 2;
                final int sourceZ = z * 2;
                final Tile child = children[(sourceZ >>> 8) * 2 + (sourceX >>> 8)];
                final int localX = sourceX & 255;
                final int localZ = sourceZ & 255;
                final int i0 = localZ * 256 + localX;
                final int i1 = i0 + 1;
                final int i2 = i0 + 256;
                final int i3 = i2 + 1;
                final int out = z * 256 + x;
                pixels[out] = Argb.average4Weighted(
                    child.pixels()[i0], child.pixels()[i1],
                    child.pixels()[i2], child.pixels()[i3]
                );
                biomes[out] = majorityBiome(
                    child.biomes()[i0], child.biomes()[i1], child.biomes()[i2], child.biomes()[i3]
                );
                surfaces[out] = averageSurface(
                    child.surfaces()[i0], child.surfaces()[i1],
                    child.surfaces()[i2], child.surfaces()[i3]
                );
            }
        }
        boolean hasServerState = false;
        long freshnessValidatedAt = Long.MAX_VALUE;
        long serverCoverageValidatedAt = Long.MAX_VALUE;
        for (final Tile child : children) {
            if (child.hasServerState()) {
                hasServerState = true;
                freshnessValidatedAt = Math.min(
                    freshnessValidatedAt, child.freshnessValidatedAtMillis()
                );
            }
            serverCoverageValidatedAt = Math.min(
                serverCoverageValidatedAt, child.serverCoverageValidatedAtMillis()
            );
        }
        return new Tile(
            pixels,
            biomes,
            surfaces,
            mode,
            hasServerState,
            freshnessValidatedAt == Long.MAX_VALUE ? 0L : freshnessValidatedAt,
            serverCoverageValidatedAt == Long.MAX_VALUE ? 0L : serverCoverageValidatedAt
        );
    }

    /** Deterministic four-value mode; ties retain the north-west sample. */
    private static byte majorityBiome(final byte b0, final byte b1, final byte b2, final byte b3) {
        final byte[] values = {b0, b1, b2, b3};
        byte best = b0;
        int bestCount = 0;
        for (int i = 0; i < values.length; i++) {
            int count = 0;
            for (final byte value : values) {
                if (value == values[i]) {
                    count++;
                }
            }
            if (count > bestCount) {
                best = values[i];
                bestCount = count;
            }
        }
        return best;
    }

    private static int averageSurface(final int s0, final int s1, final int s2, final int s3) {
        final int[] values = {s0, s1, s2, s3};
        long sum = 0L;
        int count = 0;
        for (final int value : values) {
            if (value != BaselineGrid.NO_SURFACE) {
                sum += value;
                count++;
            }
        }
        return count == 0 ? BaselineGrid.NO_SURFACE : (int) Math.floorDiv(sum, count);
    }

    private void trim() {
        final Iterator<TileKey> keys = tiles.keySet().iterator();
        while (tiles.size() > limit && keys.hasNext()) {
            keys.next();
            keys.remove();
        }
    }
}
