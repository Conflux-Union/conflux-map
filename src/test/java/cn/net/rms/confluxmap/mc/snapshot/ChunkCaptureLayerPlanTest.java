package cn.net.rms.confluxmap.mc.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.mc.world.LayerSelector;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChunkCaptureLayerPlanTest {
    @Test
    void playerHeightChangeDoesNotReseedTheWholeServerViewDistance() {
        assertFalse(ChunkCaptureService.shouldReseedBackground(
            new LayerSelector.Decision(MapLayer.CAVE_AUTO, 32),
            new LayerSelector.Decision(MapLayer.CAVE_AUTO, 34)
        ));
        assertFalse(ChunkCaptureService.shouldReseedBackground(
            new LayerSelector.Decision(MapLayer.NETHER_CURRENT, 64),
            new LayerSelector.Decision(MapLayer.NETHER_CURRENT, 66)
        ));
    }

    @Test
    void changingLayerStillSeedsBackgroundCoverage() {
        assertTrue(ChunkCaptureService.shouldReseedBackground(
            new LayerSelector.Decision(MapLayer.SURFACE, 0),
            new LayerSelector.Decision(MapLayer.CAVE_AUTO, 32)
        ));
    }

    @Test
    void visibleNetherCaptureDoesNotSpendForegroundTimeOnTheRoof() {
        final LayerSelector.Decision selected = new LayerSelector.Decision(
            MapLayer.NETHER_CURRENT, 64
        );

        assertEquals(List.of(selected), ChunkCaptureService.visibleCapturePlan(selected));
    }

    @Test
    void visibleNetherChunkKeepsItsRoofCaptureAsBackgroundWork() {
        assertEquals(
            List.of(new LayerSelector.Decision(MapLayer.NETHER_CEILING, 127)),
            ChunkCaptureService.backgroundCapturePlan(
                new LayerSelector.Decision(MapLayer.NETHER_CURRENT, 64), 128, true
            )
        );
    }

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
