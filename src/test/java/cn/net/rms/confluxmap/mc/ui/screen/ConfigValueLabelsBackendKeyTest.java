package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.nativepredict.GpuBackend;
import cn.net.rms.confluxmap.nativepredict.PredictionBackend;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the backend row's AUTO label key: it follows the resolved verdict (GPU while
 * undecided) so the row reads AUTO (GPU)/AUTO (CPU) instead of an opaque AUTO. Explicit
 * CPU/GPU choices keep their existing keys.
 */
class ConfigValueLabelsBackendKeyTest {
    @AfterEach
    void resetVerdict() {
        GpuBackend.applyAutoVerdict(null);
        GpuBackend.configure(PredictionBackend.AUTO);
    }

    @Test
    void autoKeyFollowsResolvedVerdict() {
        GpuBackend.applyAutoVerdict(null);
        assertEquals(
            "confluxmap.config.prediction.backend.auto.gpu",
            ConfigValueLabels.predictionBackendKey(PredictionBackend.AUTO),
            "undecided AUTO serves GPU and must label it so");

        GpuBackend.applyAutoVerdict(PredictionBackend.CPU);
        assertEquals(
            "confluxmap.config.prediction.backend.auto.cpu",
            ConfigValueLabels.predictionBackendKey(PredictionBackend.AUTO));

        GpuBackend.applyAutoVerdict(PredictionBackend.GPU);
        assertEquals(
            "confluxmap.config.prediction.backend.auto.gpu",
            ConfigValueLabels.predictionBackendKey(PredictionBackend.AUTO));
    }

    @Test
    void explicitChoicesKeepTheirKeys() {
        assertEquals(
            "confluxmap.config.prediction.backend.cpu",
            ConfigValueLabels.predictionBackendKey(PredictionBackend.CPU));
        assertEquals(
            "confluxmap.config.prediction.backend.gpu",
            ConfigValueLabels.predictionBackendKey(PredictionBackend.GPU));
    }
}
