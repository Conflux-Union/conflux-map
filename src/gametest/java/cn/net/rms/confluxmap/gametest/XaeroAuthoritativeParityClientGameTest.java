package cn.net.rms.confluxmap.gametest;

//#if MC>=12104
//$$ import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
//$$ import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
//#endif

//#if MC>=12111 && MC<260100
//$$ import cn.net.rms.confluxmap.core.color.DaylightModel;
//$$ import cn.net.rms.confluxmap.core.color.MapColorStyle;
//$$ import cn.net.rms.confluxmap.core.config.ConfluxConfig;
//$$ import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
//$$ import cn.net.rms.confluxmap.core.model.DimensionId;
//$$ import cn.net.rms.confluxmap.core.model.MapLayer;
//$$ import cn.net.rms.confluxmap.core.model.SampleSource;
//$$ import cn.net.rms.confluxmap.core.model.TileKey;
//$$ import cn.net.rms.confluxmap.core.model.WorldIdentity;
//$$ import cn.net.rms.confluxmap.core.store.MapWorldService;
//$$ import cn.net.rms.confluxmap.core.task.MapExecutors;
//$$ import cn.net.rms.confluxmap.core.task.SessionGuard;
//$$ import cn.net.rms.confluxmap.core.tile.TileService;
//$$ import cn.net.rms.confluxmap.core.tile.TileUpdate;
//$$ import cn.net.rms.confluxmap.mc.color.BiomeTintResolver;
//$$ import cn.net.rms.confluxmap.mc.color.SpriteColorSampler;
//$$ import cn.net.rms.confluxmap.mc.snapshot.McChunkSnapshotFactory;
//$$ import java.lang.reflect.Constructor;
//$$ import java.lang.reflect.Method;
//$$ import java.nio.ByteBuffer;
//$$ import java.util.concurrent.atomic.AtomicReference;
//$$ import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
//$$ import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
//$$ import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
//$$ import net.fabricmc.loader.api.FabricLoader;
//$$ import net.minecraft.util.math.BlockPos;
//#endif

/**
 * Pixel oracle for the authoritative surface map. Run only with
 * {@code ./gradlew :1.21.11:runClientGameTest -PxaeroOracle}; the normal test lifecycle neither
 * resolves nor loads Xaero's all-rights-reserved jar.
 */
//#if MC>=12104
//$$ @SuppressWarnings("UnstableApiUsage")
//$$ public final class XaeroAuthoritativeParityClientGameTest implements FabricClientGameTest {
//#if MC>=12111 && MC<260100
//$$     private static final int SURFACE_LAYER = Integer.MAX_VALUE;
//$$     private static final int FIXTURE_Y = 200;
//#endif
//$$
//$$     @Override
//$$     public void runTest(final ClientGameTestContext context) {
//#if MC>=12111 && MC<260100
//$$         if (!Boolean.getBoolean("confluxmap.xaero.oracle")) {
//$$             return;
//$$         }
//$$         if (!FabricLoader.getInstance().isModLoaded("xaeroworldmap")) {
//$$             throw new AssertionError("-PxaeroOracle did not load Xaero's World Map");
//$$         }
//$$
//$$         try (TestSingleplayerContext world = context.worldBuilder().create()) {
//$$             final AtomicReference<BlockPos> playerPosition = new AtomicReference<>();
//$$             context.runOnClient(client -> playerPosition.set(client.player.getBlockPos()));
//$$             final BlockPos initial = playerPosition.get();
//$$             // Xaero computes slopes in 64x64 map-tile chunks. Keep the fixture one
//$$             // Minecraft chunk inside that boundary so this oracle measures the normal
//$$             // terrain renderer instead of its missing-neighbour boundary fallback.
//$$             final int chunkX = xaeroTileInteriorChunk(initial.getX() >> 4);
//$$             final int chunkZ = xaeroTileInteriorChunk(initial.getZ() >> 4);
//$$             final int minX = chunkX << 4;
//$$             final int minZ = chunkZ << 4;
//$$             final int maxX = minX + 15;
//$$             final int maxZ = minZ + 15;
//$$
//$$             world.getServer().runCommand("time set noon");
//$$             // Xaero's slope normal reads the north and north-west pixels. Give the target
//$$             // chunk a one-block halo and wait for those neighbour chunks below so this is
//$$             // a colour comparison, not a race against Xaero's asynchronous chunk scan.
//$$             world.getServer().runCommand(fill(minX - 1, FIXTURE_Y, minZ - 1, maxX, FIXTURE_Y + 6, maxZ, "air"));
//$$             world.getServer().runCommand(fill(minX - 1, FIXTURE_Y, minZ - 1, maxX, FIXTURE_Y, maxZ, "stone"));
//$$             world.getServer().runCommand(fill(minX, FIXTURE_Y + 1, minZ + 4, maxX, FIXTURE_Y + 1, minZ + 7, "dirt"));
//$$             world.getServer().runCommand(fill(minX, FIXTURE_Y + 1, minZ + 8, maxX, FIXTURE_Y + 2, minZ + 11, "oak_planks"));
//$$             world.getServer().runCommand(fill(minX, FIXTURE_Y + 1, minZ + 12, maxX, FIXTURE_Y + 3, maxZ, "white_concrete"));
//$$             world.getServer().runCommand(
//$$                 "tp @p " + (minX + 8) + " " + (FIXTURE_Y + 5) + " " + (minZ + 8)
//$$             );
//$$
//$$             context.waitFor(client -> {
//$$                 if (client.world == null || client.player == null) {
//$$                     return false;
//$$                 }
//$$                 return client.world.getBlockState(new BlockPos(minX + 8, FIXTURE_Y + 2, minZ + 9))
//$$                     .isOf(net.minecraft.block.Blocks.OAK_PLANKS);
//$$             }, 200);
//$$             context.waitFor(client -> xaeroTileMatchesFixture(chunkX, chunkZ), 400);
//$$             context.runOnClient(client -> comparePixels(client, chunkX, chunkZ));
//$$         }
//#endif
//$$     }
//$$
//#if MC>=12111 && MC<260100
//$$     private static String fill(
//$$         final int x1,
//$$         final int y1,
//$$         final int z1,
//$$         final int x2,
//$$         final int y2,
//$$         final int z2,
//$$         final String block
//$$     ) {
//$$         return "fill " + x1 + " " + y1 + " " + z1 + " "
//$$             + x2 + " " + y2 + " " + z2 + " minecraft:" + block;
//$$     }
//$$
//$$     private static int xaeroTileInteriorChunk(final int nearChunk) {
//$$         return nearChunk + Math.floorMod(1 - nearChunk, 4);
//$$     }
//$$
//$$     private static boolean xaeroTileMatchesFixture(final int chunkX, final int chunkZ) {
//$$         try {
//$$             final Object processor = xaeroProcessor();
//$$             if (processor == null) {
//$$                 return false;
//$$             }
//$$             return xaeroHeight(processor, chunkX, chunkZ, 8, 9) == FIXTURE_Y + 2
//$$                 && xaeroHeight(processor, chunkX - 1, chunkZ, 15, 8) == FIXTURE_Y
//$$                 && xaeroHeight(processor, chunkX, chunkZ - 1, 8, 15) == FIXTURE_Y
//$$                 && xaeroHeight(processor, chunkX - 1, chunkZ - 1, 15, 15) == FIXTURE_Y;
//$$         } catch (ReflectiveOperationException e) {
//$$             throw new AssertionError("could not inspect Xaero's authoritative map tile", e);
//$$         }
//$$     }
//$$
//$$     private static int xaeroHeight(
//$$         final Object processor,
//$$         final int chunkX,
//$$         final int chunkZ,
//$$         final int localX,
//$$         final int localZ
//$$     ) throws ReflectiveOperationException {
//$$         final Object tile = invoke(processor, "getMapTile", SURFACE_LAYER, chunkX, chunkZ);
//$$         if (tile == null || !(Boolean) invoke(tile, "isLoaded")) {
//$$             return Integer.MIN_VALUE;
//$$         }
//$$         final Object block = invoke(tile, "getBlock", localX, localZ);
//$$         return block == null ? Integer.MIN_VALUE : (Integer) invoke(block, "getHeight");
//$$     }
//$$
//$$     private static void comparePixels(
//$$         final net.minecraft.client.MinecraftClient client,
//$$         final int chunkX,
//$$         final int chunkZ
//$$     ) {
//$$         final int[] xaero = renderXaeroChunk(chunkX, chunkZ);
//$$         final int[] conflux = renderConfluxChunk(client, chunkX, chunkZ);
//$$         int mismatches = 0;
//$$         int firstIndex = -1;
//$$         final StringBuilder details = new StringBuilder();
//$$         for (int i = 0; i < conflux.length; i++) {
//$$             if (conflux[i] != xaero[i]) {
//$$                 mismatches++;
//$$                 if (firstIndex < 0) {
//$$                     firstIndex = i;
//$$                 }
//$$                 if (mismatches <= 24) {
//$$                     final int x = i & 15;
//$$                     final int z = i >> 4;
//$$                     details.append(' ').append(x).append(',').append(z)
//$$                         .append(':').append(hex(xaero[i])).append('/').append(hex(conflux[i]));
//$$                 }
//$$             }
//$$         }
//$$         if (mismatches != 0) {
//$$             final int x = firstIndex & 15;
//$$             final int z = firstIndex >> 4;
//$$             throw new AssertionError(
//$$                 "Xaero authoritative pixel mismatch: " + mismatches + "/256; first at "
//$$                     + (chunkX * 16 + x) + "," + (chunkZ * 16 + z)
//$$                     + " expected=" + hex(xaero[firstIndex])
//$$                     + " actual=" + hex(conflux[firstIndex])
//$$                     + "; local expected/actual:" + details
//$$             );
//$$         }
//$$     }
//$$
//$$     private static int[] renderXaeroChunk(final int chunkX, final int chunkZ) {
//$$         try {
//$$             final Object processor = xaeroProcessor();
//$$             final Object mapChunk = invoke(processor, "getMapChunk", SURFACE_LAYER, chunkX >> 2, chunkZ >> 2);
//$$             final Object config = construct("xaero.map.region.MapUpdateFastConfig", processor);
//$$             invoke(
//$$                 mapChunk,
//$$                 "updateBuffers",
//$$                 processor,
//$$                 invoke(processor, "getWorldBlockTintProvider"),
//$$                 invoke(processor, "getOverlayManager"),
//$$                 false,
//$$                 invoke(processor, "getBlockStateShortShapeCache"),
//$$                 config
//$$             );
//$$             final Object leafTexture = invoke(mapChunk, "getLeafTexture");
//$$             final ByteBuffer buffer = ((ByteBuffer) invoke(leafTexture, "getDirectColorBuffer")).duplicate();
//$$             final int[] result = new int[16 * 16];
//$$             final int startX = Math.floorMod(chunkX * 16, 64);
//$$             final int startZ = Math.floorMod(chunkZ * 16, 64);
//$$             for (int z = 0; z < 16; z++) {
//$$                 for (int x = 0; x < 16; x++) {
//$$                     final int offset = ((startZ + z) * 64 + startX + x) * 4;
//$$                     // Xaero writes one native-endian int as B,G,R,light. On x86 that is
//$$                     // laid out as light,R,G,B bytes; compare visible RGB and ignore the
//$$                     // alpha channel that its shader repurposes for per-pixel lighting.
//$$                     result[z * 16 + x] = 0xFF000000
//$$                         | (buffer.get(offset + 1) & 0xFF) << 16
//$$                         | (buffer.get(offset + 2) & 0xFF) << 8
//$$                         | (buffer.get(offset + 3) & 0xFF);
//$$                 }
//$$             }
//$$             return result;
//$$         } catch (ReflectiveOperationException e) {
//$$             throw new AssertionError("could not render Xaero's authoritative map tile", e);
//$$         }
//$$     }
//$$
//$$     private static int[] renderConfluxChunk(
//$$         final net.minecraft.client.MinecraftClient client,
//$$         final int chunkX,
//$$         final int chunkZ
//$$     ) {
//$$         final long token = 1L;
//$$         final WorldIdentity identity = new WorldIdentity("local", "xaero-oracle");
//$$         final SessionGuard.Session session = new SessionGuard.Session(token, identity, DimensionId.OVERWORLD);
//$$         final MapWorldService worlds = new MapWorldService();
//$$         worlds.switchSession(session);
//$$         final McChunkSnapshotFactory snapshots = new McChunkSnapshotFactory(
//$$             client, new SpriteColorSampler(client), new BiomeTintResolver(client)
//$$         );
//$$         for (int dz = -1; dz <= 1; dz++) {
//$$             for (int dx = -1; dx <= 1; dx++) {
//$$                 final ChunkSnapshot snapshot = snapshots.snapshot(
//$$                     chunkX + dx, chunkZ + dz, MapLayer.SURFACE, 0, token
//$$                 );
//$$                 if (snapshot == null) {
//$$                     throw new AssertionError("Conflux could not capture oracle neighbour " + dx + "," + dz);
//$$                 }
//$$                 worlds.current().put(MapLayer.SURFACE, snapshot, SampleSource.REAL_LIVE);
//$$             }
//$$         }
//$$
//$$         final ConfluxConfig config = new ConfluxConfig();
//$$         config.dynamicLighting = false;
//$$         config.mapColorStyle = MapColorStyle.XAERO;
//$$         final MapExecutors executors = new MapExecutors();
//$$         try {
//$$             final TileService tiles = new TileService(worlds, executors, config, new DaylightModel());
//$$             final TileKey key = TileKey.ofChunk(identity, DimensionId.OVERWORLD, MapLayer.SURFACE, chunkX, chunkZ);
//$$             tiles.requestTile(key);
//$$             final int[] tile = awaitTile(tiles, key);
//$$             final int startX = Math.floorMod(chunkX * 16, 256);
//$$             final int startZ = Math.floorMod(chunkZ * 16, 256);
//$$             final int[] result = new int[16 * 16];
//$$             for (int z = 0; z < 16; z++) {
//$$                 System.arraycopy(tile, (startZ + z) * 256 + startX, result, z * 16, 16);
//$$             }
//$$             return result;
//$$         } finally {
//$$             executors.shutdown(2_000L);
//$$         }
//$$     }
//$$
//$$     private static int[] awaitTile(final TileService tiles, final TileKey key) {
//$$         final long deadline = System.nanoTime() + 5_000_000_000L;
//$$         while (System.nanoTime() < deadline) {
//$$             for (final TileUpdate update : tiles.drainUploads(64)) {
//$$                 if (update.key().equals(key)) {
//$$                     return update.argbPixels();
//$$                 }
//$$             }
//$$             Thread.yield();
//$$         }
//$$         throw new AssertionError("Conflux did not compose the oracle tile");
//$$     }
//$$
//$$     private static Object xaeroProcessor() throws ReflectiveOperationException {
//$$         final Class<?> sessionClass = Class.forName("xaero.map.WorldMapSession");
//$$         final Object session = sessionClass.getMethod("getCurrentSession").invoke(null);
//$$         return session == null ? null : invoke(session, "getMapProcessor");
//$$     }
//$$
//$$     private static Object construct(final String className, final Object argument)
//$$         throws ReflectiveOperationException {
//$$         for (final Constructor<?> constructor : Class.forName(className).getConstructors()) {
//$$             if (constructor.getParameterCount() == 1) {
//$$                 return constructor.newInstance(argument);
//$$             }
//$$         }
//$$         throw new NoSuchMethodException(className + " constructor");
//$$     }
//$$
//$$     private static Object invoke(final Object target, final String name, final Object... arguments)
//$$         throws ReflectiveOperationException {
//$$         for (final Method method : target.getClass().getMethods()) {
//$$             if (method.getName().equals(name) && method.getParameterCount() == arguments.length) {
//$$                 return method.invoke(target, arguments);
//$$             }
//$$         }
//$$         throw new NoSuchMethodException(target.getClass().getName() + "." + name);
//$$     }
//$$
//$$     private static String hex(final int argb) {
//$$         return String.format("%08X", argb);
//$$     }
//#endif
//$$ }
//#else
public final class XaeroAuthoritativeParityClientGameTest {
    private XaeroAuthoritativeParityClientGameTest() {}
}
//#endif
