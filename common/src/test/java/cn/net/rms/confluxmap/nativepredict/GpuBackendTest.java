package cn.net.rms.confluxmap.nativepredict;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Pins the backend-to-engine-mode mapping and that configuration is safe before the library
 * exists (the mode push itself is exercised end to end by the OFF-mode golden test in
 * {@link CubiomesNativeTest}).
 */
class GpuBackendTest {
    @Test
    void engineModeMapsCpuToOffAndGpuToAuto() {
        assertEquals(CubiomesNative.GPU_MODE_OFF, GpuBackend.engineMode(PredictionBackend.CPU));
        assertEquals(CubiomesNative.GPU_MODE_AUTO, GpuBackend.engineMode(PredictionBackend.GPU));
    }

    @Test
    void configureAcceptsBothBackendsWithoutALoadedLibrary() {
        // No native assumption here: pre-load configuration only stores the choice and is
        // replayed by NativeLib's success hook.
        GpuBackend.configure(PredictionBackend.CPU);
        GpuBackend.configure(PredictionBackend.GPU);
    }
}
