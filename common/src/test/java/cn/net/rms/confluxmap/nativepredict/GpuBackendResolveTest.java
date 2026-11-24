package cn.net.rms.confluxmap.nativepredict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the AUTO resolution slot: explicit CPU/GPU choices pass through untouched, AUTO
 * serves the stored verdict with GPU as the undecided default. The slot is process-global
 * (like everything in {@link GpuBackend}), so every test restores undecided on cleanup.
 */
class GpuBackendResolveTest {
    @AfterEach
    void resetVerdict() {
        GpuBackend.applyAutoVerdict(null);
    }

    @Test
    void explicitChoicesPassThroughUnresolved() {
        assertEquals(PredictionBackend.CPU, GpuBackend.resolved(PredictionBackend.CPU));
        assertEquals(PredictionBackend.GPU, GpuBackend.resolved(PredictionBackend.GPU));
    }

    @Test
    void autoServesGpuWhileUndecided() {
        assertEquals(PredictionBackend.GPU, GpuBackend.resolved(PredictionBackend.AUTO));
        assertNull(GpuBackend.autoVerdict());
    }

    @Test
    void autoServesTheStoredVerdict() {
        GpuBackend.applyAutoVerdict(PredictionBackend.CPU);
        assertEquals(PredictionBackend.CPU, GpuBackend.resolved(PredictionBackend.AUTO));
        assertEquals(PredictionBackend.CPU, GpuBackend.autoVerdict());

        GpuBackend.applyAutoVerdict(PredictionBackend.GPU);
        assertEquals(PredictionBackend.GPU, GpuBackend.resolved(PredictionBackend.AUTO));
    }

    @Test
    void verdictNeverOverridesExplicitChoices() {
        GpuBackend.applyAutoVerdict(PredictionBackend.CPU);
        assertEquals(PredictionBackend.GPU, GpuBackend.resolved(PredictionBackend.GPU));
    }
}
