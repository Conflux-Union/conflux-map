package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.core.net.ChunkLoadBand;
import cn.net.rms.confluxmap.gametest.GameTestCompat;
//#if MC>=12105
//$$ import net.fabricmc.fabric.api.gametest.v1.GameTest;
//#else
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
//#endif
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
//#if MC<12105
import net.minecraft.test.GameTest;
//#endif
import net.minecraft.test.TestContext;

/** Proves the companion reads the real server holder created by a forced-chunk ticket. */
//#if MC>=12105
//$$ public final class ChunkLoadStateGameTest {
//#else
public final class ChunkLoadStateGameTest implements FabricGameTest {
//#endif
    //#if MC>=12105
    //$$ @GameTest(maxTicks = 100)
    //#elseif MC>=12000
    //$$ @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 100)
    //#else
    @GameTest(structureName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 100)
    //#endif
    public void forcedChunkExposesItsEffectiveTicketLevel(final TestContext context) {
        final ServerWorld world = context.getWorld();
        final BlockPos origin = context.getAbsolutePos(new BlockPos(0, 0, 0));
        final ChunkPos forced = new ChunkPos((origin.getX() >> 4) + 64, (origin.getZ() >> 4) + 64);
        world.getChunkManager().setChunkForced(forced, true);
        context.runAtTick(20, () -> {
            try {
                final ChunkLoadStateAccess.State state = ChunkLoadStateAccess.read(world, forced).orElse(null);
                if (state == null) {
                    GameTestCompat.fail(context, "forced chunk did not expose a visible ChunkHolder");
                    return;
                }
                if (state.level() != 31 || state.band() != ChunkLoadBand.ENTITY_TICKING) {
                    GameTestCompat.fail(
                        context,
                        "forced chunk level was " + state.level() + " / " + state.band() + ", expected 31 / ENTITY_TICKING"
                    );
                    return;
                }
                context.complete();
            } finally {
                world.getChunkManager().setChunkForced(forced, false);
            }
        });
    }
}
