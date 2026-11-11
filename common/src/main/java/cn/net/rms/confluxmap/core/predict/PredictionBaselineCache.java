package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.model.TileKey;
import java.util.Arrays;
import java.util.LinkedHashMap;

/**
 * Bounded, exact native baselines independent of server corrections and their validation times.
 * Only directly sampled planes enter this cache; reduced child coverage must remain in the mip
 * cache, whose correction invalidation and freshness rules are deliberately separate.
 */
final class PredictionBaselineCache {
    static final int DEFAULT_MIN_TILES = 32;
    static final int MAX_TILES = 256;

    /** Effective serving identities a cache entry can be keyed under; {@code 0} is "no data". */
    static final int ENGINE_NONE = 0;
    static final int ENGINE_CPU = 1;
    static final int ENGINE_GPU = 2;

    /**
     * The {@code servingEngine} dimension keeps CPU-reference and FP32-GPU planes apart: the two
     * backends agree only within the recalibrated drift bounds near waterlines, so a hit must
     * reconstruct planes the requesting session's own engine produced, never the other one's.
     */
    record Key(
        TileKey tile, long token, long generation, long seed, int version,
        WorldPreset preset, FlatBaseline flat, QuadrantLayout quadrants, int netherY,
        int servingEngine
    ) {
    }

    record GuardedRle(byte[] data, boolean isRaw) {
    }

    /**
     * Compact baseline planes: preserves surface heights and kinds as raw shorts for zero-overhead
     * hillshading, while storing fluid depths and biomes in guarded run-length encoding.
     */
    record Planes(short[] surfaceKind, GuardedRle fluidRle, GuardedRle biomeRle) {
        void reconstruct(final BaselineGrid grid, final DerivedGrid derived) {
            PackedSurfacePlane.unpackPlane(surfaceKind, derived.surfaceY, derived.kind);
            decompressFluidZeroAlloc(fluidRle, derived.fluidDepth);
            decompressBiomesZeroAlloc(biomeRle, grid.biomeId);
        }
    }

    private final LinkedHashMap<Key, Planes> entries = new LinkedHashMap<>(16, 0.75f, true);
    private int configuredLimit;
    private int limit;

    PredictionBaselineCache(final int maxCapacity) {
        this.configuredLimit = Math.max(1, Math.min(MAX_TILES, maxCapacity));
        this.limit = Math.min(DEFAULT_MIN_TILES, this.configuredLimit);
    }

    synchronized int limit() {
        return limit;
    }

    synchronized int configuredLimit() {
        return configuredLimit;
    }

    synchronized Planes get(final Key key) {
        return entries.get(key);
    }

    synchronized void put(final Key key, final PredictionMipCache.Tile tile) {
        if (tile != null) {
            final GuardedRle fluidRle = compressWithGuard(tile.fluidDepth());
            final GuardedRle biomeRle = compressWithGuard(tile.biomes());
            entries.put(key, new Planes(tile.packedSurfaceKind(), fluidRle, biomeRle));
            trim();
        }
    }

    synchronized void setLimit(final int requestedLimit) {
        configuredLimit = Math.max(1, Math.min(MAX_TILES, requestedLimit));
        limit = Math.min(limit, configuredLimit);
        trim();
    }

    /**
     * Dynamically adapts the active baseline cache capacity to the visible viewport plus a buffer
     * ring (ceil(viewportTiles * 1.5)), bounded by [DEFAULT_MIN_TILES, configuredLimit].
     * Expansion is instantaneous to avoid LRU thrashing under small GUI scales or 4K screens;
     * shrinkage is deferred to avoid cache churn during zooming.
     */
    synchronized void adaptToViewport(final int activeViewportTiles) {
        if (activeViewportTiles <= 0) {
            return;
        }
        final int needed = (int) Math.ceil(activeViewportTiles * 1.5);
        final int target = Math.min(configuredLimit, Math.max(DEFAULT_MIN_TILES, needed));
        if (target > limit) {
            limit = target;
        }
    }

    synchronized void clear() {
        entries.clear();
    }

    synchronized int size() {
        return entries.size();
    }

    private void trim() {
        while (entries.size() > limit) {
            entries.remove(entries.keySet().iterator().next());
        }
    }

    static GuardedRle compressWithGuard(final byte[] input) {
        if (input == null || input.length == 0) {
            return new GuardedRle(new byte[0], false);
        }
        final int inputLen = input.length;
        final byte[] buf = new byte[inputLen];
        int outPos = 0;
        int i = 0;
        while (i < inputLen) {
            final byte b = input[i];
            int run = 1;
            while (i + run < inputLen && input[i + run] == b && run < 255) {
                run++;
            }
            if (outPos + 2 >= inputLen) {
                return new GuardedRle(input.clone(), true);
            }
            buf[outPos++] = (byte) run;
            buf[outPos++] = b;
            i += run;
        }
        return new GuardedRle(Arrays.copyOf(buf, outPos), false);
    }

    static void decompressBiomesZeroAlloc(final GuardedRle rle, final int[] outBiomes) {
        if (rle.isRaw()) {
            final int copyLen = Math.min(rle.data().length, BaselineGrid.PIXELS * BaselineGrid.PIXELS);
            for (int z = 0; z < BaselineGrid.PIXELS; z++) {
                final int rowOffset = z * BaselineGrid.PIXELS;
                for (int x = 0; x < BaselineGrid.PIXELS; x++) {
                    final int idx = rowOffset + x;
                    if (idx < copyLen) {
                        outBiomes[BaselineGrid.index(x, z)] = rle.data()[idx] & 0xFF;
                    }
                }
            }
            return;
        }
        final byte[] d = rle.data();
        int pos = 0;
        final int len = d.length & ~1; // enforce even length
        final int maxPixels = BaselineGrid.PIXELS * BaselineGrid.PIXELS;
        for (int i = 0; i < len && pos < maxPixels; i += 2) {
            final int run = d[i] & 0xFF;
            final int val = d[i + 1] & 0xFF;
            final int limit = Math.min(pos + run, maxPixels);
            while (pos < limit) {
                final int p = pos++;
                final int x = p & 255;
                final int z = p >>> 8;
                outBiomes[BaselineGrid.index(x, z)] = val;
            }
        }
    }

    static void decompressFluidZeroAlloc(final GuardedRle rle, final int[] outFluid) {
        if (rle.isRaw()) {
            final int copyLen = Math.min(rle.data().length, outFluid.length);
            for (int i = 0; i < copyLen; i++) {
                outFluid[i] = rle.data()[i] & 0xFF;
            }
            return;
        }
        final byte[] d = rle.data();
        int pos = 0;
        final int len = d.length & ~1;
        final int maxCells = outFluid.length;
        for (int i = 0; i < len && pos < maxCells; i += 2) {
            final int run = d[i] & 0xFF;
            final int val = d[i + 1] & 0xFF;
            final int limit = Math.min(pos + run, maxCells);
            while (pos < limit) {
                outFluid[pos++] = val;
            }
        }
    }
}
