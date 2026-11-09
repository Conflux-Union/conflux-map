package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.model.TileKey;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;

/**
 * Bounded, exact native baselines independent of server corrections and their validation times.
 * Only directly sampled planes enter this cache; reduced child coverage must remain in the mip
 * cache, whose correction invalidation and freshness rules are deliberately separate.
 */
final class PredictionBaselineCache {
    static final int MAX_TILES = 64;

    record Key(
        TileKey tile, long token, long generation, long seed, int version,
        WorldPreset preset, FlatBaseline flat, QuadrantLayout quadrants, int netherY
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
    private int limit;

    PredictionBaselineCache(final int limit) {
        setLimit(limit);
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
        limit = Math.max(1, Math.min(MAX_TILES, requestedLimit));
        trim();
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
        final ByteArrayOutputStream out = new ByteArrayOutputStream(input.length);
        int i = 0;
        while (i < input.length) {
            final byte b = input[i];
            int run = 1;
            while (i + run < input.length && input[i + run] == b && run < 255) {
                run++;
            }
            out.write((byte) run);
            out.write(b);
            if (out.size() >= input.length) {
                return new GuardedRle(input.clone(), true);
            }
            i += run;
        }
        final byte[] compressed = out.toByteArray();
        if (compressed.length >= input.length) {
            return new GuardedRle(input.clone(), true);
        }
        return new GuardedRle(compressed, false);
    }

    static void decompressBiomesZeroAlloc(final GuardedRle rle, final int[] outBiomes) {
        if (rle.isRaw()) {
            for (int z = 0; z < BaselineGrid.PIXELS; z++) {
                for (int x = 0; x < BaselineGrid.PIXELS; x++) {
                    outBiomes[BaselineGrid.index(x, z)] = rle.data()[z * BaselineGrid.PIXELS + x] & 0xFF;
                }
            }
            return;
        }
        final byte[] d = rle.data();
        int pos = 0;
        final int len = d.length;
        for (int i = 0; i < len; i += 2) {
            final int run = d[i] & 0xFF;
            final int val = d[i + 1] & 0xFF;
            for (int k = 0; k < run; k++) {
                final int p = pos++;
                final int x = p & 255;
                final int z = p >>> 8;
                outBiomes[BaselineGrid.index(x, z)] = val;
            }
        }
    }

    static void decompressFluidZeroAlloc(final GuardedRle rle, final int[] outFluid) {
        if (rle.isRaw()) {
            for (int i = 0; i < PackedSurfacePlane.CELLS; i++) {
                outFluid[i] = rle.data()[i] & 0xFF;
            }
            return;
        }
        final byte[] d = rle.data();
        int pos = 0;
        final int len = d.length;
        for (int i = 0; i < len; i += 2) {
            final int run = d[i] & 0xFF;
            final int val = d[i + 1] & 0xFF;
            for (int k = 0; k < run; k++) {
                outFluid[pos++] = val;
            }
        }
    }
}
