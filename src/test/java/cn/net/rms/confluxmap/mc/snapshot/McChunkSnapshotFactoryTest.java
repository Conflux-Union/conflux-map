package cn.net.rms.confluxmap.mc.snapshot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class McChunkSnapshotFactoryTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        //#if MC>=12100
        //$$ Assumptions.abort(
        //$$     "Yarn's named 1.21 test jar splits vanilla package-private registry access; "
        //$$         + "this Minecraft-backed behavior is verified under Fabric Loader by GameTest"
        //$$ );
        //#else
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
        //#endif
    }

    @Test
    void kelpCollapsesToWaterBeforeUnderwaterOverlaySelection() {
        assertCollapsesToWater(Blocks.KELP.getDefaultState());
        assertCollapsesToWater(Blocks.KELP_PLANT.getDefaultState());
    }

    @Test
    void submergedGreenVegetationDoesNotBecomeASeafloorOverlay() {
        assertCollapsesToWater(Blocks.SEAGRASS.getDefaultState());
        assertCollapsesToWater(Blocks.TALL_SEAGRASS.getDefaultState());
    }

    private static void assertCollapsesToWater(final BlockState state) {
        final BlockState collapsed = McChunkSnapshotFactory.collapse(state);

        assertTrue(collapsed.isOf(Blocks.WATER));
        assertFalse(McChunkSnapshotFactory.isSeafloorCapturable(collapsed));
    }
}
