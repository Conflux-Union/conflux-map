package cn.net.rms.confluxmap.mc.ui.screen;

//#if MC>=12104 && MC<260100
//$$ import cn.net.rms.confluxmap.ConfluxMapClient;
//$$ import cn.net.rms.confluxmap.core.model.DimensionId;
//$$ import cn.net.rms.confluxmap.core.waypoint.Waypoint;
//$$ import cn.net.rms.confluxmap.core.waypoint.library.MapScreenshotIndex;
//$$ import cn.net.rms.confluxmap.core.waypoint.library.WaypointListMemory;
//$$ import cn.net.rms.confluxmap.core.waypoint.library.WaypointMediaService;
//$$ import java.awt.image.BufferedImage;
//$$ import java.nio.file.Files;
//$$ import javax.imageio.ImageIO;
//$$ import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
//$$ import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
//$$ import net.minecraft.client.gui.widget.ClickableWidget;
//$$ import net.minecraft.client.gui.widget.TextFieldWidget;
//$$ import net.minecraft.text.Text;
//$$ import org.lwjgl.glfw.GLFW;
//$$
//$$ @SuppressWarnings("UnstableApiUsage")
//$$ public final class WaypointPortClientGameTest implements FabricClientGameTest {
//$$     @Override
//$$     public void runTest(final ClientGameTestContext context) {
//$$         try (var world = context.worldBuilder().create()) {
//$$             final var app = ConfluxMapClient.get();
//$$             context.waitFor(client -> app.waypointLibraryService().world() != null, 200);
//$$             final var player = context.computeOnClient(client -> app.gameBridge().player().orElseThrow());
//$$             final Waypoint waypoint = Waypoint.create("Port test", DimensionId.OVERWORLD,
//$$                 player.blockX() + 2, player.blockY(), player.blockZ(), 0xFF33CC88, "", Waypoint.Type.NORMAL);
//$$             context.runOnClient(client -> {
//$$                 app.config().uiTransitionsEnabled = false;
//$$                 app.waypointService().current().add(waypoint);
//$$                 app.waypointLibraryService().library().setStarred(waypoint.id, false, true);
//$$                 app.waypointListMemory().options().compact = true;
//$$                 client.options.getGuiScale().setValue(2);
//$$                 client.onResolutionChanged();
//$$             });
//$$             context.getInput().resizeWindow(1280, 720);
//$$             context.setScreen(WaypointListScreen::openedFromHotkey);
//$$             context.waitForScreen(CompactWaypointScreen.class);
//$$             context.takeScreenshot("waypoint-compact");
//$$
//$$             final var mediaKey = new WaypointMediaService.Key(app.waypointLibraryService().world(), false, waypoint.id);
//$$             final var image = Files.createTempFile("waypoint-port", ".png");
//$$             try {
//$$                 final BufferedImage pixels = new BufferedImage(160, 90, BufferedImage.TYPE_INT_RGB);
//$$                 pixels.setRGB(30, 30, 0x33CC88);
//$$                 ImageIO.write(pixels, "png", image.toFile());
//$$                 app.waypointMediaService().add(mediaKey, image).join();
//$$                 app.waypointMediaService().add(mediaKey, image).join();
//$$             } finally {
//$$                 Files.deleteIfExists(image);
//$$             }
//$$             context.setScreen(() -> new WaypointImagesScreen(null, mediaKey, () -> true));
//$$             context.waitForScreen(WaypointImagesScreen.class);
//$$             context.waitFor(client -> {
//$$                 var viewer = (WaypointImagesScreen) client.currentScreen;
//$$                 return viewer.imageReady();
//$$             }, 200);
//$$             context.getInput().pressKey(GLFW.GLFW_KEY_RIGHT);
//$$             context.waitFor(client -> (int) field(client.currentScreen, "index") == 1, 100);
//$$             final int[] photoCenter = context.computeOnClient(client -> {
//$$                 final int scale = (int) client.getWindow().getScaleFactor();
//$$                 return new int[]{
//$$                     ((int) field(client.currentScreen, "x") + (int) field(client.currentScreen, "w") / 2) * scale,
//$$                     ((int) field(client.currentScreen, "y") + (int) field(client.currentScreen, "h") / 2) * scale,
//$$                     scale
//$$                 };
//$$             });
//$$             final var input = context.getInput();
//$$             input.setCursorPos(photoCenter[0] + 50 * photoCenter[2], photoCenter[1]);
//$$             context.waitTick();
//$$             input.holdMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
//$$             try {
//$$                 context.waitTick();
//$$                 input.setCursorPos(photoCenter[0], photoCenter[1] + 50 * photoCenter[2]);
//$$                 context.waitFor(client -> Math.abs(((PhotoViewerScreen) client.currentScreen).rotation() - 90) < 1, 100);
//$$             } finally {
//$$                 input.releaseMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
//$$             }
//$$             click(context, "confluxmap.photos.save_rotation");
//$$             context.waitFor(client -> (boolean) field(client.currentScreen, "busy") == false, 100);
//$$             if (app.waypointMediaService().list(mediaKey).join().stream().noneMatch(photo -> photo.rotation() == 90.0)) {
//$$                 throw new AssertionError("Image rotation was not saved");
//$$             }
//$$             context.takeScreenshot("waypoint-photo-rotated");
//$$
//$$             context.setScreen(() -> null);
//$$             final var session = app.gameBridge().session();
//$$             final var scope = MapScreenshotIndex.Scope.of(session.world(), session.dimension());
//$$             input.pressKey(GLFW.GLFW_KEY_F2);
//$$             context.waitFor(client -> !app.mapScreenshotIndex().list(scope).isEmpty(), 200);
//$$             var screenshot = app.mapScreenshotIndex().list(scope).get(0);
//$$             if (!Files.isRegularFile(app.mapScreenshotIndex().file(screenshot))
//$$                 || Math.abs(screenshot.location().x() - player.x()) > 0.1) {
//$$                 throw new AssertionError("F2 screenshot did not retain the world coordinates");
//$$             }
//$$             context.runOnClient(client -> app.waypointListMemory().options().setScreenshotMode(WaypointListMemory.MapPhotoMode.ICON));
//$$             context.setScreen(() -> FullscreenMapScreen.focusedOnWaypoint(DimensionId.OVERWORLD, player.x(), player.z()));
//$$             context.waitForScreen(FullscreenMapScreen.class);
//$$             final var map = context.computeOnClient(client -> (FullscreenMapScreen) client.currentScreen);
//$$             context.waitFor(client -> !((java.util.List<?>) field(field(map, "screenshotOverlay"), "markers")).isEmpty(), 200);
//$$             context.takeScreenshot("map-screenshot-camera-hover");
//$$             click(context, "confluxmap.map.loaded_range");
//$$             context.runOnClient(client -> app.config().fullscreenLoadedRangeStyle =
//$$                 cn.net.rms.confluxmap.core.config.ConfluxConfig.LoadedRangeStyle.DASHED);
//$$             context.waitFor(client -> field(field(map, "loadedRange"), "coverage") instanceof ChunkLoadCoverage coverage
//$$                 && !coverage.loaded().isEmpty(), 100);
//$$             context.takeScreenshot("map-loaded-range-dashed");
//$$             click(context, "confluxmap.map.coordinate_jump");
//$$             context.waitForScreen(MapCoordinateJumpScreen.class);
//$$             context.runOnClient(client -> {
//$$                 ((TextFieldWidget) field(client.currentScreen, "xField")).setText("-3200");
//$$                 ((TextFieldWidget) field(client.currentScreen, "zField")).setText("1800");
//$$             });
//$$             input.pressKey(GLFW.GLFW_KEY_ENTER);
//$$             context.waitFor(client -> client.currentScreen == map && map.centerBlockX() == -3200 && map.centerBlockZ() == 1800, 100);
//$$             context.setScreen(() -> null);
//$$         } catch (java.io.IOException error) {
//$$             throw new AssertionError("Unable to prepare waypoint image", error);
//$$         }
//$$     }
//$$
//$$     private static void click(final ClientGameTestContext context, final String key) {
//$$         final int[] coordinates = context.computeOnClient(client -> {
//$$             final Text label = Text.translatable(key);
//$$             final ClickableWidget widget = client.currentScreen.children().stream()
//$$                 .filter(ClickableWidget.class::isInstance).map(ClickableWidget.class::cast)
//$$                 .filter(w -> w.getMessage().getString().equals(label.getString())).findFirst().orElseThrow();
//$$             final int gui = (int) client.getWindow().getScaleFactor();
//$$             return new int[]{(widget.getX() + widget.getWidth() / 2) * gui, (widget.getY() + widget.getHeight() / 2) * gui};
//$$         });
//$$         context.getInput().setCursorPos(coordinates[0], coordinates[1]);
//$$         context.waitTick();
//$$         context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
//$$     }
//$$
//$$     private static Object field(Object instance, String name) {
//$$         for (Class<?> type = instance.getClass(); type != null; type = type.getSuperclass()) {
//$$             try {
//$$                 var member = type.getDeclaredField(name);
//$$                 member.setAccessible(true);
//$$                 return member.get(instance);
//$$             } catch (NoSuchFieldException ignored) {
//$$             } catch (IllegalAccessException error) {
//$$                 throw new AssertionError(error);
//$$             }
//$$         }
//$$         throw new AssertionError("Unknown field " + name);
//$$     }
//$$ }
//#else
public final class WaypointPortClientGameTest {
    private WaypointPortClientGameTest() {
    }
}
//#endif
