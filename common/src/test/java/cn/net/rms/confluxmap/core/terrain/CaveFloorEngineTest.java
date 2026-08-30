package cn.net.rms.confluxmap.core.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

final class CaveFloorEngineTest {
    @Test
    void scansDownFromOpenPivotAndKeepsOverlayAboveFloor() throws Exception {
        final int minY = -16;
        final int[] states = filled(32, 0);
        setLayer(states, minY, -5, 2);
        setLayer(states, minY, -4, 3);
        final CaveFloorEngine engine = new CaveFloorEngine();

        final CaveChunkResult result = engine.select(
            new ChunkVolume(7, -3, 11L, minY, 32, states),
            8,
            Map.of(
                0, new MaterialDescriptor(true, false),
                2, new MaterialDescriptor(false, false),
                3, new MaterialDescriptor(true, true)
            )
        );

        assertEquals(-5, result.surfaceY()[0]);
        assertEquals(2, result.floorStateId()[0]);
        assertEquals(3, result.overlayStateId()[0]);
        assertEquals(false, result.crossSection()[0]);
    }

    @Test
    void scansUpAtMostTenBlocksAndReturnsCrossSectionWhenStillSolid() throws Exception {
        final int[] states = filled(32, 9);
        final CaveFloorEngine engine = new CaveFloorEngine();

        final CaveChunkResult result = engine.select(
            new ChunkVolume(0, 0, 3L, 0, 32, states),
            4,
            Map.of(9, new MaterialDescriptor(false, false))
        );

        assertEquals(4, result.surfaceY()[255]);
        assertEquals(9, result.floorStateId()[255]);
        assertEquals(true, result.crossSection()[255]);
    }

    private static int[] filled(final int height, final int value) {
        final int[] states = new int[height * 256];
        java.util.Arrays.fill(states, value);
        return states;
    }

    private static void setLayer(
        final int[] states, final int minY, final int y, final int state
    ) {
        java.util.Arrays.fill(states, (y - minY) * 256, (y - minY + 1) * 256, state);
    }
}
