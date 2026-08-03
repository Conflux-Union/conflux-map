package cn.net.rms.confluxmap.gametest;

//#if MC>=12104
//$$ import java.util.Properties;
//$$ import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
//$$ import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
//$$ import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
//#endif

/** Proves that the production client and server companions can complete a real login. */
//#if MC>=12104
//$$ @SuppressWarnings("UnstableApiUsage")
//$$ public final class ClientConnectionGameTest implements FabricClientGameTest {
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
//$$     }
//$$ }
//#else
public final class ClientConnectionGameTest {
    private ClientConnectionGameTest() {}
}
//#endif
