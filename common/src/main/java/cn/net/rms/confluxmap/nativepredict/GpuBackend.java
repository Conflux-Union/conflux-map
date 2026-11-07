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

    private GpuBackend() {
    }

    /** Engine mode for a backend: {@code GPU_MODE_OFF} for CPU, {@code GPU_MODE_AUTO} for GPU. */
    public static int engineMode(final PredictionBackend backend) {
        return backend == PredictionBackend.GPU
            ? CubiomesNative.GPU_MODE_AUTO
            : CubiomesNative.GPU_MODE_OFF;
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
