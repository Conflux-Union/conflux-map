package cn.net.rms.confluxmap.mc.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.confluxmap.core.color.BiomeSampleWindow;
import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class BiomeIdentityCaptureTest {
    @Test
    void unloadedNetherNeighborCannotTurnBoundaryColumnsIntoPlains() {
        final short[] surfaceY = new short[ChunkSnapshot.COLUMNS];
        Arrays.fill(surfaceY, (short) 64);
        final String[] biomeId = new String[ChunkSnapshot.COLUMNS];

        BiomeIdentityCapture.capture(
            0,
            0,
            surfaceY,
            biomeId,
            BiomeSampleWindow.of(
                BiomeIdentityCapture.VORONOI_BORDER_INSET,
                false,
                false,
                false,
                false
            ),
            (blockX, blockY, blockZ) -> blockX < 2 || blockX >= 14
                || blockZ < 2 || blockZ >= 14
                ? "minecraft:plains"
                : "minecraft:nether_wastes"
        );

        for (final String captured : biomeId) {
            assertEquals("minecraft:nether_wastes", captured);
        }
    }
}
