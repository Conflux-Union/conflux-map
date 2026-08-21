package cn.net.rms.confluxmap.gametest;

//#if MC>=12104
//$$ import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
//$$ import cn.net.rms.confluxmap.core.model.MapLayer;
//$$ import cn.net.rms.confluxmap.core.util.Argb;
//$$ import cn.net.rms.confluxmap.mc.color.BiomeTintResolver;
//$$ import cn.net.rms.confluxmap.mc.color.SpriteColorSampler;
//$$ import cn.net.rms.confluxmap.mc.snapshot.McChunkSnapshotFactory;
//$$ import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
//$$ import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
//$$ import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
//$$ import net.minecraft.block.CarpetBlock;
//$$ import net.minecraft.util.math.BlockPos;
//#endif

/** Reproduces Issue 47 against the real singleplayer client-world surface scanner. */
//#if MC>=12104
//$$ @SuppressWarnings("UnstableApiUsage")
//$$ public final class SurfaceCarpetClientGameTest implements FabricClientGameTest {
//$$     @Override
//$$     public void runTest(final ClientGameTestContext context) {
//$$         if (Boolean.getBoolean("confluxmap.xaero.oracle")) {
//$$             return;
//$$         }
//$$         try (TestSingleplayerContext world = context.worldBuilder().create()) {
//$$             world.getServer().runCommand(
//$$                 "execute at @p run setblock ~2 ~-1 ~ minecraft:stone"
//$$             );
//$$             world.getServer().runCommand(
//$$                 "execute at @p run setblock ~2 ~ ~ minecraft:white_carpet"
//$$             );
//$$             context.waitFor(client -> {
//$$                 if (client.player == null || client.world == null) {
//$$                     return false;
//$$                 }
//$$                 final BlockPos target = client.player.getBlockPos().add(2, 0, 0);
//$$                 return client.world.getBlockState(target).getBlock() instanceof CarpetBlock;
//$$             });
//$$             context.runOnClient(client -> {
//$$                 final BlockPos target = client.player.getBlockPos().add(2, 0, 0);
//$$                 final McChunkSnapshotFactory factory = new McChunkSnapshotFactory(
//$$                     client,
//$$                     new SpriteColorSampler(client),
//$$                     new BiomeTintResolver(client)
//$$                 );
//$$                 final ChunkSnapshot surface = factory.snapshot(
//$$                     target.getX() >> 4, target.getZ() >> 4, MapLayer.SURFACE, 0, 1L
//$$                 );
//$$                 final ChunkSnapshot cave = factory.snapshot(
//$$                     target.getX() >> 4, target.getZ() >> 4, MapLayer.CAVE_AUTO,
//$$                     target.getY() + 1, 1L
//$$                 );
//$$                 final int index = Math.floorMod(target.getZ(), 16) * 16
//$$                     + Math.floorMod(target.getX(), 16);
//$$                 if (surface == null || cave == null) {
//$$                     throw new AssertionError("target chunk was not available to the client scanner");
//$$                 }
//$$                 if (surface.surfaceY[index] != target.getY()
//$$                     || Argb.alpha(surface.baseArgb[index]) == 0) {
//$$                     throw new AssertionError(
//$$                         "surface scan did not promote white carpet: "
//$$                             + "surfaceY=" + surface.surfaceY[index]
//$$                             + ", carpetY=" + target.getY()
//$$                             + ", surfaceBase=" + Integer.toHexString(surface.baseArgb[index])
//$$                             + ", surfaceOverlay=" + Integer.toHexString(surface.overlayArgb[index])
//$$                             + ", caveBase=" + Integer.toHexString(cave.baseArgb[index])
//$$                             + ", caveOverlay=" + Integer.toHexString(cave.overlayArgb[index])
//$$                     );
//$$                 }
//$$             });
//$$         }
//$$     }
//$$ }
//#else
public final class SurfaceCarpetClientGameTest {
    private SurfaceCarpetClientGameTest() {}
}
//#endif
