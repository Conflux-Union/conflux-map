package cn.net.rms.confluxmap.nativepredict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins the backend-to-engine-mode mapping and that configuration is safe before the library
 * exists (the mode push itself is exercised end to end by the OFF-mode golden test in
 * {@link CubiomesNativeTest}). The wave-batch gate is pinned as a pure decision over a stats
 * snapshot, so no native state and no latch ordering across tests is involved.
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

    @Test
    void batchGateRejectsOffModeMissingSnapshotAndRefusal() {
        final GpuBackend.GpuStats auto =
            new GpuBackend.GpuStats(CubiomesNative.GPU_MODE_AUTO, "device", 1, 1);
        final GpuBackend.GpuStats off =
            new GpuBackend.GpuStats(CubiomesNative.GPU_MODE_OFF, "", 0, 0);
        assertTrue(GpuBackend.batchDispatchAvailable(auto, false), "a live auto engine must admit batches");
        assertFalse(GpuBackend.batchDispatchAvailable(off, false), "CPU mode must admit waves without the seam");
        assertFalse(GpuBackend.batchDispatchAvailable(null, false), "no engine snapshot must gate batches off");
        assertFalse(GpuBackend.batchDispatchAvailable(auto, true), "a latched refusal must gate batches off");
    }
}
