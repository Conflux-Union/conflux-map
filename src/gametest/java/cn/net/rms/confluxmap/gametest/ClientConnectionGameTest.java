package cn.net.rms.confluxmap.gametest;

//#if MC>=12104
//$$ import cn.net.rms.confluxmap.mc.ui.screen.FullscreenMapScreen;
//$$ import java.util.Properties;
//$$ import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
//$$ import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
//$$ import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
//$$ import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
//$$ import org.lwjgl.glfw.GLFW;
//#endif

/** Proves real companion login and keeps a cold structure lookup off the client render thread. */
//#if MC>=12104
//$$ @SuppressWarnings("UnstableApiUsage")
//$$ public final class ClientConnectionGameTest implements FabricClientGameTest {
//$$     private static final long MAX_COLD_MAP_RENDER_MS = 500L;
//$$     private static final long MAX_CONTROL_MULTIPLIER = 4L;
//$$
//$$     @Override
//$$     public void runTest(final ClientGameTestContext context) {
//$$         final Properties serverProperties = new Properties();
//$$         serverProperties.setProperty("max-tick-time", "0");
//$$         serverProperties.setProperty("view-distance", "2");
//$$
//$$         try (
//$$             TestDedicatedServerContext server = context.worldBuilder().createServer(serverProperties);
//$$             var connection = server.connect()
//$$         ) {
//$$             context.waitTick();
//$$         }
//$$
//$$         try (TestSingleplayerContext world = context.worldBuilder().setUseConsistentSettings(false).create()) {
//$$             context.waitTicks(5);
//$$             final long controlStartedAt = System.nanoTime();
//$$             context.getInput().pressKey(GLFW.GLFW_KEY_F8);
//$$             final long controlElapsedMs = (System.nanoTime() - controlStartedAt) / 1_000_000L;
//$$             final long startedAt = System.nanoTime();
//$$             context.getInput().pressKey(GLFW.GLFW_KEY_M);
//$$             final long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
//$$             context.waitForScreen(FullscreenMapScreen.class);
//$$             final long limitMs = Math.max(
//$$                 MAX_COLD_MAP_RENDER_MS,
//$$                 controlElapsedMs * MAX_CONTROL_MULTIPLIER
//$$             );
//$$             if (elapsedMs > limitMs) {
//$$                 throw new AssertionError(
//$$                     "cold fullscreen map render blocked for " + elapsedMs + " ms (limit "
//$$                         + limitMs + " ms; control tick " + controlElapsedMs + " ms)"
//$$                 );
//$$             }
//$$         }
//$$     }
//$$ }
//#else
public final class ClientConnectionGameTest {
    private ClientConnectionGameTest() {}
}
//#endif
