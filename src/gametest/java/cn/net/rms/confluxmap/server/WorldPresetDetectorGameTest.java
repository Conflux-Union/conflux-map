package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.core.predict.WorldPreset;
import cn.net.rms.confluxmap.gametest.GameTestCompat;
//#if MC>=12105
//$$ import net.fabricmc.fabric.api.gametest.v1.GameTest;
//#else
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
//#endif
import net.minecraft.server.world.ServerWorld;
//#if MC<12105
import net.minecraft.test.GameTest;
//#endif
import net.minecraft.test.TestContext;
import net.minecraft.world.World;

/** Locks vanilla dimension generators to the prediction presets advertised by the companion. */
//#if MC>=12105
//$$ public final class WorldPresetDetectorGameTest {
//#else
public final class WorldPresetDetectorGameTest implements FabricGameTest {
//#endif
    //#if MC>=12105
    //$$ @GameTest(maxTicks = 20)
    //#elseif MC>=12000
    //$$ @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    //#else
    @GameTest(structureName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    //#endif
    public void vanillaNetherUsesDefaultPredictionPreset(final TestContext context) {
        final ServerWorld nether = context.getWorld().getServer().getWorld(World.NETHER);
        if (nether == null) {
            GameTestCompat.fail(context, "server did not create the Nether dimension");
            return;
        }
        final WorldPreset preset = WorldPresetDetector.detect(nether);
        if (preset != WorldPreset.DEFAULT) {
            GameTestCompat.fail(
                context,
                "vanilla Nether prediction preset was " + preset + ", expected DEFAULT"
            );
            return;
        }
        context.complete();
    }
}
