package cn.net.rms.confluxmap.gametest;

//#if MC>=12104
//$$ import cn.net.rms.confluxmap.ConfluxMapClient;
//$$ import cn.net.rms.confluxmap.core.config.ConfluxConfig;
//$$ import cn.net.rms.confluxmap.core.config.ScoreboardHudAvoidance;
//$$ import cn.net.rms.confluxmap.mc.ui.hud.ScoreboardHudBounds;
//$$ import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
//$$ import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
//$$ import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
//#endif

/** Reproduces the combined scoreboard and status-effect HUD layout reported in Issue 27. */
//#if MC>=12104
//$$ @SuppressWarnings("UnstableApiUsage")
//$$ public final class HudAvoidanceClientGameTest implements FabricClientGameTest {
//$$     @Override
//$$     public void runTest(final ClientGameTestContext context) {
//$$         try (TestSingleplayerContext world = context.worldBuilder().create()) {
//$$             final ConfluxConfig config = ConfluxMapClient.get().config();
//$$             config.minimapEnabled = true;
//$$             config.minimapHudAvoidance = true;
//$$             config.minimapShape = ConfluxConfig.Shape.SQUARE;
//$$             config.minimapSize = 128;
//$$             config.minimapPositionX = 1.0;
//$$             config.minimapPositionY = 0.0;
//$$             config.minimapRotate = false;
//$$             config.showCoordinates = false;
//$$             config.showBiome = false;
//$$             config.showLayerIndicator = false;
//$$             config.radarEnabled = false;
//$$
//$$             world.getServer().runCommand("scoreboard objectives add conflux_hud dummy");
//$$             world.getServer().runCommand(
//$$                 "scoreboard objectives modify conflux_hud displayname "
//$$                     + "{\"text\":\"HUD\"}"
//$$             );
//$$             world.getServer().runCommand("scoreboard objectives setdisplay sidebar conflux_hud");
//$$             world.getServer().runCommand("effect give @p minecraft:speed 600 0 false");
//$$             world.getServer().runCommand("effect give @p minecraft:poison 600 0 false");
//$$
//$$             context.waitFor(client -> {
//$$                 if (client.player == null) {
//$$                     return false;
//$$                 }
//#if MC>=260100
//$$                 return client.player.getActiveEffects().size() >= 2;
//#else
//$$                 return client.player.getStatusEffects().size() >= 2;
//#endif
//$$             });
//$$             context.waitFor(client -> {
//#if MC>=260100
//$$                 final int screenWidth = client.getWindow().getGuiScaledWidth();
//$$                 final int screenHeight = client.getWindow().getGuiScaledHeight();
//#else
//$$                 final int screenWidth = client.getWindow().getScaledWidth();
//$$                 final int screenHeight = client.getWindow().getScaledHeight();
//#endif
//$$                 final ScoreboardHudAvoidance.Transform transform =
//$$                     ScoreboardHudBounds.previousAppliedTransform(screenWidth, screenHeight);
//$$                 return ScoreboardHudBounds.previousFrame(screenWidth, screenHeight) != null
//$$                     && transform.translateY() > 0f
//$$                     && transform.scale() == 1f;
//$$             }, 100);
//$$         }
//$$     }
//$$ }
//#else
public final class HudAvoidanceClientGameTest {
    private HudAvoidanceClientGameTest() {}
}
//#endif
