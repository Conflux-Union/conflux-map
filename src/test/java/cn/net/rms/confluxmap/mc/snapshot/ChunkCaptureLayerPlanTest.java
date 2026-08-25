package cn.net.rms.confluxmap.mc.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.mc.world.LayerSelector;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChunkCaptureLayerPlanTest {
    @Test
    void belowRoofNetherCaptureAlsoStoresThePersistentRoofSurface() {
        assertEquals(
            List.of(
                new LayerSelector.Decision(MapLayer.NETHER_CURRENT, 64),
                new LayerSelector.Decision(MapLayer.NETHER_CEILING, 127)
            ),
            ChunkCaptureService.capturePlan(
                new LayerSelector.Decision(MapLayer.NETHER_CURRENT, 64), 128
            )
        );
    }

    @Test
    void anAlreadySelectedPersistentSurfaceIsCapturedOnlyOnce() {
        final LayerSelector.Decision surface = new LayerSelector.Decision(MapLayer.SURFACE, 0);
        assertEquals(List.of(surface), ChunkCaptureService.capturePlan(surface, 320));
    }
}
