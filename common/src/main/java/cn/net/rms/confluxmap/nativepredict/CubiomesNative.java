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
 *   <li>8 - malformed chunk NBT (only from {@link #cfxScanChunkNbt})
 *   <li>9 - engine programs still compiling (transient; only from the batch methods)
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

    /** GPU engine execution modes for {@link #cfxSetGpuMode}; mirror {@code GPU_MODE_*} in {@code native/cubiomes/gpu.h}. */
    static final int GPU_MODE_OFF = 0;
    /** Same value as the engine's own default (env {@code CUBIOMES_GPU} or auto when unset). */
    static final int GPU_MODE_AUTO = 1;
    static final int GPU_MODE_FORCE = 2;

    /**
     * Overrides the GPU execution mode of the 1.18+ Overworld surface dispatch. The engine
     * defaults to env-var driven behavior ({@code CUBIOMES_GPU}, auto when unset) and this
     * class never calls the setter itself, so a library that Java leaves alone behaves exactly
     * like the stock engine. Invalid values are ignored by the engine. A library predating
     * this export throws {@link UnsatisfiedLinkError}.
     */
    static native void cfxSetGpuMode(int mode);

    /**
     * Compiles the engine's OpenCL programs now instead of on the first dispatch, so a cold
     * driver JIT (minutes for this kernel size) overlaps the game's startup instead of stalling
     * the first prediction tile. Dispatches that arrive while the compile runs keep serving the
     * CPU reference - except under {@code CUBIOMES_GPU=force}, which waits the build out and
     * never serves the CPU for the transient window. Returns {@code -1} when the engine mode is
     * off, else bit 1 = climate runtime available, bit 2 = cfx program available. Idempotent per
     * process. A library predating this export throws {@link UnsatisfiedLinkError}.
     */
    static native int cfxGpuWarmup();

    /**
     * Debug snapshot of the GPU engine's process-global state. Fills {@code out} with
     * {@code [engineMode, successfulSurfaceBiomeDispatches, successfulHeightDispatches]}
     * ({@code GPU_MODE_*} values; counts are the engine's own {@code gpuDispatchCount()}/
     * {@code gpuHeightDispatchCount()}) and returns the probed OpenCL device name (empty before
     * first use or when no device is available). With the GPU engine not compiled into the
     * library, reports the off mode and zero counts with an empty device name. A library
     * predating this export throws {@link UnsatisfiedLinkError}; callers must treat that and a
     * {@code null} return as "no data".
     */
    static native String cfxGetGpuStats(long[] out);

    /**
     * Snapshot of the GPU engine's batch metrics into {@code metrics}, a fixed
     * 68-slot array whose layout mirrors the {@code GPU_METRICS_*} constants in
     * {@code native/cubiomes/gpu.h} (profiling flag, batch counters, host
     * walls, event timers, member/point histograms). Silently produces nothing
     * when the array is shorter. With the GPU engine not compiled into the
     * library, every slot reads zero. A library predating this export throws
     * {@link UnsatisfiedLinkError}; callers must treat that as "no data".
     */
    static native void cfxGetGpuMetrics(long[] metrics);

    /**
     * Packed per-context stage accounting for the debug backend annotation:
     * {@code (gpuServedStages << 32) | trackedStageCalls}. A tracked stage is one
     * {@code cfxOverviewHeights(/Coarse)} or {@code cfxSurfaceBiomes} call whose shape the GPU
     * path would attempt (the same min-cell gates that decide the dispatch); {@code
     * gpuServedStages} counts those where the GPU actually completed the call. Counters live on
     * the context (one per thread via {@link CubiomesContexts}) and only make sense as deltas
     * taken around one tile's sampling. A {@code 0} handle or a library predating this export
     * (which throws {@link UnsatisfiedLinkError}) leaves the caller with no data.
     */
    static native long cfxStageStats(long handle);

    /**
     * Windows-only CPU topology query used for worker-pool sizing; no other platform
     * reports data. Calling with a {@code null} array returns the total number of
     * logical processors (0 when unknown); calling with an array fills it with one
     * efficiency class per logical processor (0 = efficiency/E, higher = performance/P,
     * grouped by {@code GetLogicalProcessorInformationEx}) and returns the count written,
     * or 0 when the array is too small or the topology cannot be read. A library
     * predating this export throws {@link UnsatisfiedLinkError}; callers must treat
     * that and a 0 return as "no data".
     */
    static native int cfxCpuEfficiencyClasses(int[] outClasses);

    /**
     * Linux x86_64-only CPU topology query used for worker-pool sizing; no other
     * platform reports data. Same two-call protocol as {@link #cfxCpuEfficiencyClasses}:
     * calling with a {@code null} array returns the number of logical processors this
     * process may use (0 when the CPU reports no CPUID hybrid information, or anything
     * along the way fails); calling with an array fills it with one CPUID leaf 0x1A
     * core type per logical processor (0x40 = Core/P, 0x20 = Atom/E) and returns the
     * count written, or 0 when the array is too small or the topology cannot be read.
     * The shim moves the calling thread across the allowed CPUs to read each label
     * and restores its original affinity mask before returning. A library predating
     * this export throws {@link UnsatisfiedLinkError}; callers must treat that and
     * a 0 return as "no data".
     */
    static native int cfxCpuHybridCoreTypes(int[] outTypes);

    /** Selectively parses one uncompressed chunk NBT payload into coarse centered columns. */
    static native int cfxScanChunkNbt(
        byte[] nbt,
        int nbtLength,
        int lod,
        long[] outRevision,
        int[] outNumeric,
        String[] outStrings
    );

    /**
     * Creates a context for the given cubiomes {@code MCVersion} int, world seed and dimension
     * ({@code -1} = Nether, {@code 0} = Overworld, {@code 1} = End). Returns
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
     * Sparse strided variant for wide-area search: every output cell is one independent
     * biome-source query instead of the dense-row sweep {@link #cfxBiomesStrided} performs.
     * Only Overworld post-1.18 scale 16, Nether scale 4 and End scale 4/16 are supported;
     * other combinations return {@code CFX_ERR_BAD_ARGS} so callers fall back.
     */
    static native int cfxBiomesSparseStrided(
        long handle, int scale, int x, int z, int w, int h, int stride, int[] out
    );

    /**
     * Search-targeted variant of {@link #cfxBiomesSparseStrided}: cells that cannot be
     * {@code targetBiomeId} (Overworld post-1.18 scale 16, by climate-parameter envelope)
     * are rejected cheaply and reported as {@code -1}; every other cell carries its real
     * biome id. Same supported combinations as the sparse variant.
     */
    static native int cfxBiomesScanStrided(
        long handle, int scale, int x, int z, int w, int h, int stride, int targetBiomeId, int[] out
    );

    /** Nether-only strided biome grid sampled at the requested block Y. */
    static native int cfxBiomesAtYStrided(
        long handle, int blockY, int scale, int x, int z, int w, int h, int stride, int[] out
    );

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

    /** Version-owned cheap terrain overview, sampled once at every requested output position. */
    static native int cfxOverviewHeights(
        long handle,
        int blockX,
        int blockZ,
        int w,
        int h,
        int stride,
        int[] outTerrainY
    );

    /**
     * Coarse-lattice overview for the LOD2-4 anchor sweeps. On pre-1.18 worlds this entry may
     * skip depth-noise octaves shorter than half the sampling stride (the plain entry never
     * truncates); 1.18+ behavior matches {@link #cfxOverviewHeights}.
     */
    static native int cfxOverviewHeightsCoarse(
        long handle,
        int blockX,
        int blockZ,
        int w,
        int h,
        int stride,
        int[] outTerrainY
    );

    /** Overworld surface biomes using caller-supplied solid terrain heights. */
    static native int cfxSurfaceBiomes(
        long handle,
        int blockX,
        int blockZ,
        int w,
        int h,
        int stride,
        int[] terrainY,
        int[] outBiomeIds
    );

    /**
     * Sparse overview refinement: resolves {@code n} independent columns, where point i is the
     * same column {@link #cfxOverviewHeights}(blockX[i], blockZ[i], 1, 1, 4, ...) produces.
     * All-or-nothing: any per-point failure returns that status and leaves {@code outTerrainY}
     * untouched, so the caller repairs through the single entry.
     */
    static native int cfxOverviewHeightsSparse(
        long handle,
        int[] blockX,
        int[] blockZ,
        int n,
        int[] outTerrainY
    );

    /**
     * Sparse surface-biome refinement for uniformly-shaped cells: cell i covers an edge x edge
     * point grid spaced {@code stride} blocks apart with heights {@code terrainYPacked[i*edge*edge
     * ..]}, resolving the same values {@link #cfxSurfaceBiomes}(cellX[i], cellZ[i], edge, edge,
     * stride, heights_i, out_i) produces. All-or-nothing like {@link #cfxOverviewHeightsSparse}.
     */
    static native int cfxSurfaceBiomesSparse(
        long handle,
        int[] cellX,
        int[] cellZ,
        int edge,
        int stride,
        int[] terrainYPacked,
        int n,
        int[] outBiomeIdsPacked
    );

    /**
     * Batched coarse-lattice overview heights for a wave of tiles: the parallel
     * {@code blockX}/{@code blockZ}/{@code w}/{@code h}/{@code stride} arrays describe
     * {@code n} tiles, {@code outTerrainY} is caller-sized flat storage holding each tile's
     * {@code w*h} plane at its running-sum offset, and {@code failMask} is a length-1 array
     * receiving the per-tile failure bitmap (bit {@code i} = tile {@code i} left untouched
     * for the caller to repair through {@link #cfxOverviewHeightsCoarse}). Returns 0 when
     * the batch was attempted (check {@code failMask}); nonzero when the caller must
     * dispatch its tiles individually. Tiles past the engine's per-dispatch point cap are
     * chunked internally and the bitmap merged; {@code n} is capped at the engine's
     * 32-block batch limit.
     */
    static native int cfxOverviewHeightsCoarseBatch(
        long handle,
        int[] blockX,
        int[] blockZ,
        int[] w,
        int[] h,
        int[] stride,
        int n,
        int[] outTerrainY,
        int[] failMask
    );

    /**
     * Batched Overworld surface biomes: same flat-array contract as
     * {@link #cfxOverviewHeightsCoarseBatch}, with {@code terrainY} the caller-filled flat
     * input holding each tile's raw {@code w*h} height plane at the same running-sum
     * offsets (the shim clamps at 62 like the single entry). {@code sha} must be the
     * handle's own voronoi sha - the batch shares one packed engine state, so a probe from
     * a different generator refuses instead of borrowing it.
     */
    static native int cfxSurfaceBiomesBatch(
        long handle,
        long sha,
        int[] blockX,
        int[] blockZ,
        int[] w,
        int[] h,
        int[] stride,
        int[] terrainY,
        int n,
        int[] outBiomeIds,
        int[] failMask
    );

    /**
     * Reads the context's stored voronoi sha ({@code Generator.sha}, the value cubiomes'
     * {@code getVoronoiSHA} derives at setup) without recomputing it: the probe {@link
     * #cfxSurfaceBiomesBatch} validates its {@code sha} argument against, so a wave leader
     * reads it from its own context instead of re-deriving it in Java. A {@code 0} handle
     * returns 0; a library predating this export throws {@link UnsatisfiedLinkError} and
     * callers degrade to per-tile dispatch.
     */
    static native long cfxVoronoiSHA(long handle);

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

    /** Structure attempts from the region rectangle that also pass cubiomes' viability checks. */
    static native int cfxViableStructures(
        long handle, int structType, int regX0, int regZ0, int regX1, int regZ1,
        long[] out, int[] outVariants, int cap
    );

    /** All strongholds for this seed, ordered by Vanilla's ring iterator. */
    static native int cfxStrongholds(long handle, long[] out, int cap);

    /** Nearest viable structure of {@code structType} within {@code maxRadius}; writes one packed position. */
    static native int cfxNearestStructure(
        long handle, int structType, int blockX, int blockZ, int maxRadius,
        long[] out, int[] outVariant
    );

    /**
     * Generation viability check for a structure attempt at {@code (blockX, blockZ)} (as returned
     * by {@link #cfxStructures}), including the dedicated End City terrain check. Returns 0 or 1;
     * never throws for a bad handle or unknown structure type (returns 0 instead).
     */
    static native int cfxStructureViable(long handle, int structType, int blockX, int blockZ);
}
