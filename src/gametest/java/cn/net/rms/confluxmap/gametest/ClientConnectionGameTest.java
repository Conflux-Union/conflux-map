package cn.net.rms.confluxmap.gametest;

//#if MC>=12104
//$$ import cn.net.rms.confluxmap.ConfluxMapClient;
//$$ import cn.net.rms.confluxmap.mc.ui.screen.FullscreenMapScreen;
//$$ import java.util.Properties;
//$$ import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
//$$ import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
//$$ import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
//$$ import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
//$$ import net.minecraft.entity.Entity;
//$$ import net.minecraft.entity.EntityType;
//$$ import net.minecraft.entity.LivingEntity;
//#if MC>=260200
//$$ import net.minecraft.world.entity.EntityTypes;
//#endif
//$$ import org.lwjgl.glfw.GLFW;
//#endif

/** Proves companion login, dynamic radar portraits, and non-blocking cold structure lookup. */
//#if MC>=12104
//$$ @SuppressWarnings("UnstableApiUsage")
//$$ public final class ClientConnectionGameTest implements FabricClientGameTest {
//$$     private static final long MAX_COLD_MAP_RENDER_MS = 500L;
//$$     private static final long MAX_CONTROL_MULTIPLIER = 4L;
//$$
//$$     @Override
//$$     public void runTest(final ClientGameTestContext context) {
//$$         if (Boolean.getBoolean("confluxmap.xaero.oracle")) {
//$$             return;
//$$         }
//$$         final Properties serverProperties = new Properties();
//$$         serverProperties.setProperty("max-tick-time", "0");
//$$         serverProperties.setProperty("server-port", "25566");
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
//$$             ConfluxMapClient.get().config().radarShowPassive = true;
//$$             context.waitTicks(5);
//$$             world.getServer().runCommand("kill @e[type=!minecraft:player]");
//$$             world.getServer().runCommand(
//$$                 "execute at @p run summon minecraft:creeper ~12 ~ ~ {NoAI:1b,Invulnerable:1b,Silent:1b}"
//$$             );
//$$             world.getServer().runCommand(
//$$                 "execute at @p run summon minecraft:villager ~-12 ~ ~ {Age:-24000,NoAI:1b,Invulnerable:1b,Silent:1b}"
//$$             );
//$$             context.waitFor(client -> {
//$$                 if (client.world == null) {
//$$                     return false;
//$$                 }
//$$                 boolean creeperReady = false;
//$$                 boolean babyVillagerReady = false;
//$$                 for (final Entity entity : client.world.getEntities()) {
//#if MC>=260200
//$$                     if (entity.getType() == EntityTypes.CREEPER
//#else
//$$                     if (entity.getType() == EntityType.CREEPER
//#endif
//$$                     ) {
//$$                         final LivingEntity living = (LivingEntity) entity;
//$$                         living.setBodyYaw(0f);
//$$                         living.setHeadYaw(70f);
//$$                         if (ConfluxMapClient.get().entityIconManager().iconFor(entity) != null) {
//$$                             creeperReady = true;
//$$                         }
//$$                     }
//#if MC>=260200
//$$                     if (entity.getType() == EntityTypes.VILLAGER
//#else
//$$                     if (entity.getType() == EntityType.VILLAGER
//#endif
//$$                         && ConfluxMapClient.get().entityIconManager().iconFor(entity) != null
//$$                     ) {
//$$                         babyVillagerReady = true;
//$$                     }
//$$                 }
//$$                 return creeperReady && babyVillagerReady;
//$$             }, 100);
//$$             context.waitFor(client -> ConfluxMapClient.get().radarScanner().snapshot().stream().anyMatch(entry -> {
//$$                 if (client.world == null) {
//$$                     return false;
//$$                 }
//$$                 final Entity entity = client.world.getEntityById(entry.entityId());
//#if MC>=260200
//$$                 return entity != null && entity.getType() == EntityTypes.CREEPER;
//#else
//$$                 return entity != null && entity.getType() == EntityType.CREEPER;
//#endif
//$$             }), 100);
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
