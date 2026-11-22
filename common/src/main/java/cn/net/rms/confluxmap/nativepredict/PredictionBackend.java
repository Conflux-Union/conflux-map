package cn.net.rms.confluxmap.nativepredict;

/**
 * User-selectable engine for the seed-predicted underlay. {@link #GPU} maps to the native
 * engine's auto mode: it uses the OpenCL GPU executor when the machine has a working device
 * and silently serves the CPU reference otherwise, which is what "prefer GPU" means for a
 * user-facing toggle. {@link #CPU} maps to the engine's off mode and never touches OpenCL.
 * {@link #AUTO} delegates the choice to the one-time hardware benchmark (see {@code
 * GpuBenchmark}): the faster of this machine's CPU pool and GPU serves until the hardware
 * identity changes, with GPU as the undecided default. {@code force} is deliberately not
 * exposed - it is a debug aid that reports loudly when the GPU is unavailable, not a
 * setting a user can act on.
 */
public enum PredictionBackend {
    AUTO,
    CPU,
    GPU
}
