package cn.net.rms.confluxmap.mc.predict;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.predict.PredictionState;
import cn.net.rms.confluxmap.core.predict.StructureIndex;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StructureMarkerServicePolicyTest {
    @Test
    void serverPolicyRemovesStructureTypesFromThePublicService(@TempDir final Path cacheRoot) {
        final AtomicBoolean allowed = new AtomicBoolean(true);
        final PredictionState prediction = new PredictionState();
        prediction.setSeed(42L, 21);
        final StructureMarkerService service = new StructureMarkerService(
            cacheRoot, prediction, allowed::get
        );

        assertFalse(service.availableTypes(DimensionId.OVERWORLD).isEmpty());

        allowed.set(false);
        assertTrue(service.availableTypes(DimensionId.OVERWORLD).isEmpty());
        assertTrue(service.query(-1_000, 1_000, -1_000, 1_000).isEmpty());
        assertTrue(service.findNearest(
            StructureIndex.StructureType.VILLAGE, 0, 0, 100_000
        ).isEmpty());
    }
}
