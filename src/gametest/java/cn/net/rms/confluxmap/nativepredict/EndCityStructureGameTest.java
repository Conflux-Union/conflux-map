package cn.net.rms.confluxmap.nativepredict;

import cn.net.rms.confluxmap.gametest.GameTestCompat;
//#if MC>=12105
//$$ import net.fabricmc.fabric.api.gametest.v1.GameTest;
//#else
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
//#endif
//#if MC>=260100
//$$ import net.minecraft.core.registries.Registries;
//$$ import net.minecraft.gametest.framework.GameTestHelper;
//$$ import net.minecraft.server.level.ServerLevel;
//$$ import net.minecraft.world.level.ChunkPos;
//$$ import net.minecraft.world.level.Level;
//$$ import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
//$$ import net.minecraft.world.level.levelgen.structure.StructureStart;
//#endif

/** Checks native structure locations against the real Minecraft structure generator. */
//#if MC>=12105
//$$ public final class EndCityStructureGameTest {
//#else
public final class EndCityStructureGameTest implements FabricGameTest {
//#endif
    //#if MC>=260100
    //$$ @GameTest(maxTicks = 1_200)
    //$$ public void nativeEndCityLocationGeneratesARealStructureStart(final GameTestHelper context) {
    //$$     final ServerLevel endWorld = context.getLevel().getServer().getLevel(Level.END);
    //$$     if (endWorld == null) {
    //$$         GameTestCompat.fail(context, "server did not create the End dimension");
    //$$         return;
    //$$     }
    //$$     if (!NativeLib.available()) {
    //$$         GameTestCompat.fail(context, "native predictor was not initialized by the companion");
    //$$         return;
    //$$     }
    //$$
    //$$     final long[] nearest = new long[1];
    //$$     try (CubiomesContext nativeContext = CubiomesContext.create(
    //$$         McVersions.toCubiomes("26.1.2").orElseThrow(), endWorld.getSeed(), 1, 0
    //$$     )) {
    //$$         if (nativeContext == null
    //$$             || !nativeContext.nearestStructure(20, 0, 0, 100_000, nearest)) {
    //$$             GameTestCompat.fail(context, "native predictor did not find an End City");
    //$$             return;
    //$$         }
    //$$     }
    //$$
    //$$     final int blockX = (int) (nearest[0] >> 32);
    //$$     final int blockZ = (int) nearest[0];
    //$$     final var endCity = endWorld.registryAccess()
    //$$         .lookupOrThrow(Registries.STRUCTURE)
    //$$         .getOrThrow(BuiltinStructures.END_CITY);
    //$$     final var generator = endWorld.getChunkSource().getGenerator();
    //$$     final StructureStart generated = endCity.value().generate(
    //$$         endCity,
    //$$         Level.END,
    //$$         endWorld.registryAccess(),
    //$$         generator,
    //$$         generator.getBiomeSource(),
    //$$         endWorld.getChunkSource().randomState(),
    //$$         endWorld.getStructureManager(),
    //$$         endWorld.getSeed(),
    //$$         new ChunkPos(blockX >> 4, blockZ >> 4),
    //$$         0,
    //$$         endWorld,
    //$$         endCity.value().biomes()::contains
    //$$     );
    //$$     if (!generated.isValid()) {
    //$$         GameTestCompat.fail(
    //$$             context,
    //$$             "Vanilla rejected native End City coordinate " + blockX + "," + blockZ
    //$$         );
    //$$         return;
    //$$     }
    //$$     context.succeed();
    //$$ }
    //#endif
}
