package cn.net.rms.confluxmap.nativepredict;

import java.util.Objects;

/**
 * Owner of the GPU execution mode. The engine defaults to env-var driven behavior ({@code
 * CUBIOMES_GPU}, auto when unset); this class pushes the user's {@link PredictionBackend}
 * choice into it at settings load and at every change, always gated on {@link NativeLib}
 * state so the native call only happens once the library is loaded ({@link #onLibraryReady}
 * replays a configuration that was made before the load). Nothing here forces a mode at JVM
 * start: without a stored choice the library keeps its stock behavior. {@link #GPU} maps to
 * the engine's auto mode, which silently serves the exact CPU reference when no working
 * OpenCL device is present - capability never needs to be queried from Java.
 */
public final class GpuBackend {
    private static final Object LOCK = new Object();

    private static volatile PredictionBackend configured = PredictionBackend.GPU;
    private static volatile boolean libraryReady;
    private static volatile boolean batchRefused;

    private GpuBackend() {
    }

    /** One read of the engine's process-global GPU state, for the debug backend snapshot log. */
    public record GpuStats(int engineMode, String device, long dispatches, long heightDispatches) {
    }

    /**
     * Current engine mode, probed OpenCL device name and successful dispatch counts, or
     * {@code null} when there is no data (library not loaded, or it predates the stats export -
     * see {@link CubiomesNative#cfxGetGpuStats}). Read-only and cheap; safe from any thread.
     */
    public static GpuStats stats() {
        try {
            final long[] out = new long[3];
            final String device = CubiomesNative.cfxGetGpuStats(out);
            if (device == null) {
                return null;
            }
            return new GpuStats((int) out[0], device, out[1], out[2]);
        } catch (final UnsatisfiedLinkError staleLibrary) {
            return null;
        }
    }

    /** Debug label for an engine mode value; see {@code GPU_MODE_*} in {@code native/cubiomes/gpu.h}. */
    public static String engineModeLabel(final int engineMode) {
        if (engineMode == CubiomesNative.GPU_MODE_OFF) {
            return "off";
        }
        if (engineMode == CubiomesNative.GPU_MODE_AUTO) {
            return "auto";
        }
        if (engineMode == CubiomesNative.GPU_MODE_FORCE) {
            return "force";
        }
        return "unknown(" + engineMode + ")";
    }

    /** Engine mode for a backend: {@code GPU_MODE_OFF} for CPU, {@code GPU_MODE_AUTO} for GPU. */
    public static int engineMode(final PredictionBackend backend) {
        return backend == PredictionBackend.GPU
            ? CubiomesNative.GPU_MODE_AUTO
            : CubiomesNative.GPU_MODE_OFF;
    }

    /**
     * Latches wave batch dispatch off for the rest of this process. The engine latches dead,
     * device-less, or cfx-unbuilt process-wide, so re-attempting would serialize the same
     * refusal onto each wave's leader thread. Nothing resets it, mirroring the native latches.
     */
    public static void noteBatchRefused() {
        batchRefused = true;
    }

    /**
     * Whether a wave may admit with a live batch seam: no refusal latched and the resolved
     * engine mode is not OFF. A missing snapshot means the library predates the batch entries,
     * so per-tile dispatch is the honest answer there too.
     */
    public static boolean batchDispatchAvailable() {
        return batchDispatchAvailable(stats(), batchRefused);
    }

    static boolean batchDispatchAvailable(final GpuStats snapshot, final boolean refused) {
        return !refused && snapshot != null && snapshot.engineMode() != CubiomesNative.GPU_MODE_OFF;
    }

    /**
     * Applies the user's backend choice. Safe to call before or without the native library:
     * the choice is stored and replayed by {@link #onLibraryReady} once the load succeeds.
     */
    public static void configure(final PredictionBackend backend) {
        Objects.requireNonNull(backend, "backend");
        synchronized (LOCK) {
            configured = backend;
            if (libraryReady) {
                CubiomesNative.cfxSetGpuMode(engineMode(backend));
            }
        }
    }

    /** {@code NativeLib.init} success hook: pushes the pending configuration into the engine. */
    static void onLibraryReady() {
        synchronized (LOCK) {
            libraryReady = true;
            CubiomesNative.cfxSetGpuMode(engineMode(configured));
        }
    }
}
