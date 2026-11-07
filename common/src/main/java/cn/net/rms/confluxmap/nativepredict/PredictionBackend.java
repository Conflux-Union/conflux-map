package cn.net.rms.confluxmap.nativepredict;

/**
 * User-selectable engine for the seed-predicted underlay. {@link #GPU} maps to the native
 * engine's auto mode: it uses the OpenCL GPU executor when the machine has a working device
 * and silently serves the CPU reference otherwise, which is what "prefer GPU" means for a
 * user-facing toggle. {@link #CPU} maps to the engine's off mode and never touches OpenCL.
 * {@code force} is deliberately not exposed - it is a debug aid that reports loudly when the
 * GPU is unavailable, not a setting a user can act on.
 */
public enum PredictionBackend {
    CPU,
    GPU
}
