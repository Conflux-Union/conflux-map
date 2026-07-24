package cn.net.rms.confluxmap.mc.snapshot;

import cn.net.rms.confluxmap.gametest.GameTestCompat;
import java.util.List;
//#if MC>=12105
//$$ import net.fabricmc.fabric.api.gametest.v1.GameTest;
//#else
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
//#endif
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
//#if MC<12105
import net.minecraft.test.GameTest;
//#endif
import net.minecraft.test.TestContext;

/** Minecraft-backed snapshot normalization checks that require a Fabric Loader runtime. */
//#if MC>=12105
//$$ public final class McChunkSnapshotFactoryGameTest {
//#else
public final class McChunkSnapshotFactoryGameTest implements FabricGameTest {
//#endif
    //#if MC>=12105
    //$$ @GameTest(maxTicks = 20)
    //#elseif MC>=12100
    //$$ @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    //#else
    @GameTest(structureName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    //#endif
    public void submergedVegetationCollapsesToWater(final TestContext context) {
        final List<BlockState> vegetation = List.of(
            Blocks.KELP.getDefaultState(),
            Blocks.KELP_PLANT.getDefaultState(),
            Blocks.SEAGRASS.getDefaultState(),
            Blocks.TALL_SEAGRASS.getDefaultState()
        );
        for (final BlockState state : vegetation) {
            final BlockState collapsed = McChunkSnapshotFactory.collapse(state);
            if (!collapsed.isOf(Blocks.WATER)) {
                GameTestCompat.fail(context,
                    "submerged vegetation did not collapse to water: " + state
                );
                return;
            }
            if (McChunkSnapshotFactory.isSeafloorCapturable(collapsed)) {
                GameTestCompat.fail(context,
                    "collapsed vegetation remained seafloor-capturable: " + state
                );
                return;
            }
        }
        context.complete();
    }
}
