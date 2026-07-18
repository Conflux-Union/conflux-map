package cn.net.rms.confluxmap.core.predict;

/**
 * Pure-Java {@link BaselineSampler} stub whose every returned value is a deterministic function
 * of the absolute native-scale coordinate requested (never of the requesting rectangle's shape
 * or origin) - exactly what {@link LodSamplingTest}'s margin-correctness check and {@link
 * PredictedTileComposerTest}'s determinism check need: two different tiles (or two calls for the
 * same tile) must see bit-identical values for the same absolute position.
 */
final class PositionBasedFakeSampler implements BaselineSampler {
    /** Void below this Z (in blockZ terms) when {@link #voidBeyond} is set - lets tests exercise the End's void branch. */
    private final Integer voidBeyondBlockZ;

    PositionBasedFakeSampler() {
        this.voidBeyondBlockZ = null;
    }

    PositionBasedFakeSampler(final int voidBeyondBlockZ) {
        this.voidBeyondBlockZ = voidBeyondBlockZ;
    }

    @Override
    public boolean biomes(final int scale, final int x, final int z, final int w, final int h, final int[] out) {
        for (int zz = 0; zz < h; zz++) {
            for (int xx = 0; xx < w; xx++) {
                out[zz * w + xx] = biomeAt((x + xx) * scale, (z + zz) * scale);
            }
        }
        return true;
    }

    @Override
    public boolean heights(final int x4, final int z4, final int w, final int h, final int[] outY) {
        fill(x4, z4, w, h, outY);
        return true;
    }

    @Override
    public boolean endHeights(final int x4, final int z4, final int w, final int h, final int[] outY) {
        fill(x4, z4, w, h, outY);
        return true;
    }

    private void fill(final int x4, final int z4, final int w, final int h, final int[] out) {
        for (int zz = 0; zz < h; zz++) {
            final int blockZ = (z4 + zz) * 4;
            for (int xx = 0; xx < w; xx++) {
                final int blockX = (x4 + xx) * 4;
                out[zz * w + xx] = (voidBeyondBlockZ != null && blockZ >= voidBeyondBlockZ) ? 0 : heightAt(blockX, blockZ);
            }
        }
    }

    /** A small, non-constant, always-a-known-BiomeTable-id function so downstream water/kind logic sees a plausible mix. */
    private static int biomeAt(final int worldX, final int worldZ) {
        final int[] ids = {1, 4, 21, 30, 35, 37}; // plains, forest, jungle, snowy_taiga, savanna, badlands
        final int n = Math.floorMod(worldX / 16 + worldZ / 16, ids.length);
        return ids[n];
    }

    private static int heightAt(final int worldX, final int worldZ) {
        return 64 + Math.floorMod(worldX * 13 + worldZ * 7, 33) - 16;
    }
}
