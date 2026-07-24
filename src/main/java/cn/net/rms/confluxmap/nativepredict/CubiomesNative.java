package cn.net.rms.confluxmap.nativepredict;

/**
 * Raw {@code native} declarations mirroring {@code native/shim/confluxnative.c} one-to-one.
 * Every method here is batch-only and takes a {@code handle} previously returned by {@link
 * #cfxCreate} - nothing in this class validates arguments beyond what the JVM itself enforces
 * (non-null array references); the shim does its own bounds checking and returns a status code
 * rather than throwing. {@link CubiomesContext} is the safe wrapper application code should use
 * instead of calling these directly.
 *
 * <p>Status codes returned by the {@code cfx*} query methods (0 is always success):
 * <ul>
 *   <li>0 - ok
 *   <li>1 - bad handle (0/closed)
 *   <li>2 - bad size (w/h/cap out of range)
 *   <li>3 - bad argument (e.g. unsupported scale)
 *   <li>4 - native allocation failed
 *   <li>5 - cubiomes reported a generation failure
 *   <li>6 - wrong dimension for this query (e.g. {@link #cfxEndHeights} on an Overworld handle)
 *   <li>7 - requested terrain feature is only partially supported for this biome
 * </ul>
 *
 * <p>Nothing in this class loads the native library itself; that is {@link NativeLib}'s job.
 * Calling any method here before a successful {@link NativeLib} load throws {@link
 * UnsatisfiedLinkError}.
 */
final class CubiomesNative {
    private CubiomesNative() {
    }

    /** Returns the shim's {@code CFX_ABI} constant, so Java can detect a stale/mismatched native build. */
    static native int cfxAbi();

    /**
     * Creates a context for the given cubiomes {@code MCVersion} int, world seed and dimension
     * ({@code 0} = Overworld, {@code 1} = End - Nether is out of scope this milestone). Returns
     * {@code 0} for any invalid input (unknown version, unsupported dimension, allocation
     * failure) instead of throwing; callers must check for that.
     */
    static native long cfxCreate(int mcVersion, long seed, int dim, int flags);

    /** Frees a context. A no-op if {@code handle} is {@code 0}. Never call this twice on the same handle. */
    static native void cfxDestroy(long handle);

    /**
     * Fills {@code out} (length &ge; {@code w*h}) with cubiomes biome ids for a {@code w*h}
     * rectangle at the given cubiomes {@code Range} scale (1, 4, 16, 64 or 256), with {@code x}/
     * {@code z} already expressed in that scale's coordinate units (e.g. at scale 4, {@code x}
     * is {@code blockX / 4}). Indexed {@code out[row*w + col]}.
     */
    static native int cfxBiomes(long handle, int scale, int x, int z, int w, int h, int[] out);

    /** Strided variant: adjacent output cells are {@code stride} coordinates apart at {@code scale}. */
    static native int cfxBiomesStrided(long handle, int scale, int x, int z, int w, int h, int stride, int[] out);

    /**
     * Overworld-only: fills {@code outY} (floored block heights) and {@code outIds} (biome ids)
     * for a {@code w*h} rectangle at 1:4 scale, {@code x4}/{@code z4} being {@code blockX/4} and
     * {@code blockZ/4}. Both arrays must have length &ge; {@code w*h}. Returns status 6 if the
     * handle's dimension isn't Overworld.
     */
    static native int cfxHeights(long handle, int x4, int z4, int w, int h, int[] outY, int[] outIds);

    /**
     * Overworld-only block-scale base columns. Outputs are highest solid Y, highest base-fluid Y
     * ({@link Integer#MIN_VALUE} when absent), final visible base surface Y, and surface flags.
     */
    static native int cfxSurfaceColumns(
        long handle,
        int blockX,
        int blockZ,
        int w,
        int h,
        int stride,
        int[] outSolidY,
        int[] outFluidY,
        int[] outSurfaceY,
        int[] outFlags
    );

    /** Strided 1:4 Overworld heights; adjacent output cells are {@code stride} native cells apart. */
    static native int cfxHeightsStrided(long handle, int x4, int z4, int w, int h, int stride, int[] outY, int[] outIds);

    /**
     * End-only: fills {@code outY} with floored End surface heights for a {@code w*h} rectangle
     * at 1:4 scale (same coordinate convention as {@link #cfxHeights}). A height of {@code 0}
     * means no surface was found there (e.g. the void between islands). Returns status 6 if the
     * handle's dimension isn't End.
     */
    static native int cfxEndHeights(long handle, int x4, int z4, int w, int h, int[] outY);

    /** Strided 1:4 End heights; adjacent output cells are {@code stride} native cells apart. */
    static native int cfxEndHeightsStrided(long handle, int x4, int z4, int w, int h, int stride, int[] outY);

    /**
     * Overworld-only natural vegetation candidates for one supported-version chunk. Candidate
     * fields are written in parallel to the six output arrays and {@code outCount[0]} receives the
     * number written. Status 7 means cubiomes does not model that biome's decoration pipeline, so
     * callers should retain their existing synthetic fallback instead of treating the empty output
     * as exact.
     */
    static native int cfxTreeCandidates(
        long handle, int chunkX, int chunkZ,
        int[] outX, int[] outY, int[] outZ, int[] outType, int[] outBiome, int[] outFlags,
        int[] outCount, int cap
    );

    /**
     * Packs one structure-generation-attempt block position per region in
     * {@code [regX0, regX1] x [regZ0, regZ1]} (cubiomes {@code enum StructureType} ordinal in
     * {@code structType}) into {@code out} as {@code (blockX << 32) | (blockZ & 0xffffffffL)},
     * up to {@code cap} entries (also clamped to {@code out}'s actual length). Returns the number
     * of entries written - a region contributes nothing if that structure type has no valid
     * attempt there regardless of biomes (see cubiomes' {@code getStructurePos} docs). This is a
     * position only; it does not check biome viability.
     */
    static native int cfxStructures(long handle, int structType, int regX0, int regZ0, int regX1, int regZ1, long[] out, int cap);

    /**
     * Biome-only viability check for a structure-generation attempt at {@code (blockX, blockZ)}
     * (as returned by {@link #cfxStructures}). Returns 0 or 1; never throws for a bad handle or
     * unknown structure type (returns 0 instead).
     */
    static native int cfxStructureViable(long handle, int structType, int blockX, int blockZ);
}
