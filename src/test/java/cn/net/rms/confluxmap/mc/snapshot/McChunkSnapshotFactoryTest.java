package cn.net.rms.confluxmap.mc.snapshot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class McChunkSnapshotFactoryTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }

    @Test
    void kelpCollapsesToWaterBeforeUnderwaterOverlaySelection() {
        assertKelpCollapsesToWater(Blocks.KELP.getDefaultState());
        assertKelpCollapsesToWater(Blocks.KELP_PLANT.getDefaultState());
    }

    @Test
    void otherUnderwaterPlantsRemainEligibleForTheSeafloorOverlay() {
        final BlockState seagrass = McChunkSnapshotFactory.collapse(Blocks.SEAGRASS.getDefaultState());

        assertTrue(seagrass.isOf(Blocks.SEAGRASS));
        assertTrue(McChunkSnapshotFactory.isSeafloorCapturable(seagrass));
    }

    private static void assertKelpCollapsesToWater(final BlockState kelp) {
        final BlockState collapsed = McChunkSnapshotFactory.collapse(kelp);

        assertTrue(collapsed.isOf(Blocks.WATER));
        assertFalse(McChunkSnapshotFactory.isSeafloorCapturable(collapsed));
    }
}
