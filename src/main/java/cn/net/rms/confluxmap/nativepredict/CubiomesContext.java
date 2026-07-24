package cn.net.rms.confluxmap.nativepredict;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A live cubiomes generator handle for one (mcVersion, seed, dim) triple. Thread-confined by
 * contract: create it and call every method from a single thread only. cubiomes' Generator and
 * SurfaceNoise structs are plain mutable memory with no internal locking (confirmed by
 * inspection - see native/shim/confluxnative.c's header comment), so concurrent use of the
 * <em>same</em> context from two threads is undefined behavior; a second context on a second
 * thread is always safe on its own. {@link CubiomesContexts} is what actually enforces the
 * "one context per thread" half of that contract - prefer it over calling {@link #create}
 * directly.
 *
 * <p>{@link #close()} is idempotent - closing an already-closed context is a no-op, not an
 * error - and every query method throws {@link IllegalStateException} once closed.
 */
public final class CubiomesContext implements AutoCloseable {
    public static final int STATUS_FEATURE_PARTIAL = 7;

    private final long handle;
    private final AtomicBoolean closed = new AtomicBoolean();

    private CubiomesContext(final long handle) {
        this.handle = handle;
    }

    /**
     * Creates a context for {@code mcVersion} (a cubiomes {@code MCVersion} int, see {@link
     * McVersions}), {@code seed}, and {@code dim} ({@code 0} = Overworld, {@code 1} = End).
     * Returns {@code null} for any invalid input instead of throwing - callers must check.
     */
    static CubiomesContext create(final int mcVersion, final long seed, final int dim, final int flags) {
        final long handle = CubiomesNative.cfxCreate(mcVersion, seed, dim, flags);
        return handle == 0 ? null : new CubiomesContext(handle);
    }

    /** Fills {@code out} (length &ge; {@code w*h}) with biome ids for a {@code w*h} rectangle at {@code scale}. */
    public int biomes(final int scale, final int x, final int z, final int w, final int h, final int[] out) {
        requireOpen();
        requireCapacity(out, w, h);
        return CubiomesNative.cfxBiomes(handle, scale, x, z, w, h, out);
    }

    /** Strided biome grid; adjacent output cells are {@code stride} native coordinates apart. */
    public int biomesStrided(
        final int scale, final int x, final int z, final int w, final int h, final int stride, final int[] out
    ) {
        requireOpen();
        requireCapacity(out, w, h);
        return CubiomesNative.cfxBiomesStrided(handle, scale, x, z, w, h, stride, out);
    }

    /** Overworld-only: floored block heights (+ biome ids) for a {@code w*h} rectangle at 1:4 scale. */
    public int heights(final int x4, final int z4, final int w, final int h, final int[] outY, final int[] outIds) {
        requireOpen();
        requireCapacity(outY, w, h);
        requireCapacity(outIds, w, h);
        return CubiomesNative.cfxHeights(handle, x4, z4, w, h, outY, outIds);
    }

    /** Overworld-only block-scale solid/fluid/final base-surface columns. */
    public int surfaceColumns(
        final int blockX,
        final int blockZ,
        final int w,
        final int h,
        final int stride,
        final int[] outSolidY,
        final int[] outFluidY,
        final int[] outSurfaceY,
        final int[] outFlags
    ) {
        requireOpen();
        requireCapacity(outSolidY, w, h);
        requireCapacity(outFluidY, w, h);
        requireCapacity(outSurfaceY, w, h);
        requireCapacity(outFlags, w, h);
        return CubiomesNative.cfxSurfaceColumns(
            handle, blockX, blockZ, w, h, stride,
            outSolidY, outFluidY, outSurfaceY, outFlags
        );
    }

    /** Strided Overworld height grid at 1:4 native scale. */
    public int heightsStrided(
        final int x4, final int z4, final int w, final int h, final int stride, final int[] outY, final int[] outIds
    ) {
        requireOpen();
        requireCapacity(outY, w, h);
        requireCapacity(outIds, w, h);
        return CubiomesNative.cfxHeightsStrided(handle, x4, z4, w, h, stride, outY, outIds);
    }

    /** End-only: floored End surface heights for a {@code w*h} rectangle at 1:4 scale ({@code 0} = no surface). */
    public int endHeights(final int x4, final int z4, final int w, final int h, final int[] outY) {
        requireOpen();
        requireCapacity(outY, w, h);
        return CubiomesNative.cfxEndHeights(handle, x4, z4, w, h, outY);
    }

    /** Strided End height grid at 1:4 native scale. */
    public int endHeightsStrided(
        final int x4, final int z4, final int w, final int h, final int stride, final int[] outY
    ) {
        requireOpen();
        requireCapacity(outY, w, h);
        return CubiomesNative.cfxEndHeightsStrided(handle, x4, z4, w, h, stride, outY);
    }

    /**
     * Overworld-only natural tree candidates for one 1.17.1 chunk. All field arrays must have the
     * same capacity; {@code outCount[0]} receives the number of records on success.
     */
    public int treeCandidates(
        final int chunkX, final int chunkZ,
        final int[] outX, final int[] outY, final int[] outZ, final int[] outType,
        final int[] outBiome, final int[] outFlags, final int[] outCount
    ) {
        requireOpen();
        final int capacity = outX.length;
        requireCapacity(outY, capacity);
        requireCapacity(outZ, capacity);
        requireCapacity(outType, capacity);
        requireCapacity(outBiome, capacity);
        requireCapacity(outFlags, capacity);
        requireCapacity(outCount, 1);
        return CubiomesNative.cfxTreeCandidates(
            handle, chunkX, chunkZ, outX, outY, outZ, outType, outBiome, outFlags, outCount, capacity
        );
    }

    /**
     * Packs one structure-attempt block position per region in {@code [regX0,regX1] x
     * [regZ0,regZ1]} into {@code out}, up to {@code out.length} entries. Returns the number
     * written; see {@link CubiomesNative#cfxStructures} for the packing format and for why a
     * region can legitimately contribute nothing.
     */
    public int structures(final int structType, final int regX0, final int regZ0, final int regX1, final int regZ1, final long[] out) {
        requireOpen();
        return CubiomesNative.cfxStructures(handle, structType, regX0, regZ0, regX1, regZ1, out, out.length);
    }

    /** Biome-only viability check for a structure attempt position (see {@link CubiomesNative#cfxStructureViable}). */
    public boolean structureViable(final int structType, final int blockX, final int blockZ) {
        requireOpen();
        return CubiomesNative.cfxStructureViable(handle, structType, blockX, blockZ) != 0;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            CubiomesNative.cfxDestroy(handle);
        }
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("CubiomesContext already closed");
        }
    }

    private static void requireCapacity(final int[] array, final int w, final int h) {
        final long needed = (long) w * (long) h;
        if (needed < 0 || array.length < needed) {
            throw new IllegalArgumentException("array too small: need " + needed + ", got " + array.length);
        }
    }

    private static void requireCapacity(final int[] array, final int needed) {
        if (array.length < needed) {
            throw new IllegalArgumentException("array too small: need " + needed + ", got " + array.length);
        }
    }
}
