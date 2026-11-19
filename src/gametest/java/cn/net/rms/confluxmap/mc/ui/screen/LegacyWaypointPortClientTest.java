package cn.net.rms.confluxmap.mc.ui.screen;

//#if MC<12104
import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.compat.Keys;
import cn.net.rms.confluxmap.compat.MouseButtons;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import cn.net.rms.confluxmap.core.waypoint.library.MapScreenshotIndex;
import cn.net.rms.confluxmap.core.waypoint.library.WaypointListMemory;
import cn.net.rms.confluxmap.core.waypoint.library.WaypointMediaService;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import javax.imageio.ImageIO;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.ScreenshotRecorder;

/** Runs the waypoint UI in a live client on releases without Fabric Client GameTest. */
public final class LegacyWaypointPortClientTest implements ClientModInitializer {
    private int ticks;
    private int stage;
    private boolean done;
    private WaypointMediaService.Key mediaKey;
    private FullscreenMapScreen map;
    private MapScreenshotIndex.Scope scope;
    private String screenshotFile;
    private double screenshotX;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(MinecraftClient client) {
        if (done) return;
        try {
            check(++ticks < 1_200, "Timed out at waypoint check " + stage);
            var app = ConfluxMapClient.get();
            if (client.player != null && app.waypointLibraryService().world() != null && advance(client, app)) {
                stage++;
                ticks = 0;
            }
        } catch (IOException | RuntimeException | AssertionError error) {
            ConfluxMapMod.LOGGER.error("Legacy waypoint gameplay test failed at step " + stage, error);
            done = true;
            client.scheduleStop();
        }
    }

    private boolean advance(MinecraftClient client, ConfluxMapClient app) throws IOException {
        switch (stage) {
            case 0 -> {
                final var player = app.gameBridge().player().orElseThrow();
                final var waypoint = Waypoint.create("Port test", DimensionId.OVERWORLD,
                    player.blockX() + 2, player.blockY(), player.blockZ(), 0xFF33CC88, "", Waypoint.Type.NORMAL);
                app.config().uiTransitionsEnabled = false;
                app.config().fullscreenLoadedRangeVisible = false;
                app.waypointService().current().add(waypoint);
                app.waypointLibraryService().library().setStarred(waypoint.id, false, true);
                app.waypointListMemory().options().compact = true;
                mediaKey = new WaypointMediaService.Key(app.waypointLibraryService().world(), false, waypoint.id);
                createPhotos(app);
                client.setScreen(WaypointListScreen.openedFromHotkey());
            }
            case 1 -> {
                check(client.currentScreen instanceof CompactWaypointScreen, "Compact waypoint view did not open");
                client.setScreen(new WaypointImagesScreen(null, mediaKey, () -> true));
            }
            case 2 -> {
                var viewer = (WaypointImagesScreen) client.currentScreen;
                if (!viewer.imageReady()) return false;
                Widgets.setText((TextFieldWidget) field(viewer, "caption"), "Port caption");
                viewer.keyPressed(Keys.RIGHT, 0, 0);
            }
            case 3 -> {
                var viewer = (WaypointImagesScreen) client.currentScreen;
                if ((boolean) field(viewer, "busy") || !viewer.imageReady()) return false;
                check((int) field(viewer, "index") == 1, "Right arrow did not select the second image");
                check("Port caption".equals(app.waypointMediaService().list(mediaKey).join().get(0).caption()),
                    "Changing page lost the image caption");
                int x = (int) field(viewer, "x") + (int) field(viewer, "w") / 2;
                int y = (int) field(viewer, "y") + (int) field(viewer, "h") / 2;
                if (!viewer.mouseClicked(x + 30, y, MouseButtons.RIGHT)) return false;
                viewer.mouseDragged(x, y + 30, MouseButtons.RIGHT, -30, 30);
                viewer.mouseReleased(x, y + 30, MouseButtons.RIGHT);
                check(viewer.rotation() == 90.0, "Right drag did not reach the 90-degree snap point");
                click(viewer, "confluxmap.photos.save_rotation");
            }
            case 4 -> {
                if ((boolean) field(client.currentScreen, "busy")) return false;
                check(app.waypointMediaService().list(mediaKey).join().stream().anyMatch(photo -> photo.rotation() == 90.0),
                    "Image rotation was not saved");
                client.setScreen(null);
            }
            case 5 -> {
                final var session = app.gameBridge().session();
                scope = MapScreenshotIndex.Scope.of(session.world(), session.dimension());
                screenshotX = app.gameBridge().player().orElseThrow().x();
                screenshotFile = "waypoint-review-" + java.util.UUID.randomUUID() + ".png";
                ScreenshotRecorder.saveScreenshot(client.runDirectory, screenshotFile, client.getFramebuffer(), ignored -> {});
            }
            case 6 -> {
                final var shot = app.mapScreenshotIndex().list(scope).stream()
                    .filter(capture -> screenshotFile.equals(capture.file())).findFirst().orElse(null);
                if (shot == null) return false;
                check(Files.isRegularFile(app.mapScreenshotIndex().file(shot)), "Screenshot file is missing");
                check(Math.abs(shot.location().x() - screenshotX) < 0.1, "Screenshot lost its capture coordinates");
                app.waypointListMemory().options().setScreenshotMode(WaypointListMemory.MapPhotoMode.ICON);
                map = FullscreenMapScreen.focusedOnWaypoint(DimensionId.OVERWORLD, shot.location().x(), shot.location().z());
                client.setScreen(map);
            }
            case 7 -> {
                if (((List<?>) field(field(map, "screenshotOverlay"), "markers")).isEmpty()) return false;
                click(map, "confluxmap.map.loaded_range");
                app.config().fullscreenLoadedRangeStyle = ConfluxConfig.LoadedRangeStyle.DASHED;
            }
            case 8 -> {
                if (!(field(field(map, "loadedRange"), "coverage") instanceof ChunkLoadCoverage coverage)
                    || coverage.loaded().isEmpty()) return false;
                click(map, "confluxmap.map.coordinate_jump");
                check(client.currentScreen instanceof MapCoordinateJumpScreen, "Coordinate form did not open");
                Widgets.setText((TextFieldWidget) field(client.currentScreen, "xField"), "-3200");
                Widgets.setText((TextFieldWidget) field(client.currentScreen, "zField"), "1800");
                client.currentScreen.keyPressed(Keys.ENTER, 0, 0);
            }
            case 9 -> {
                check(client.currentScreen == map && map.centerBlockX() == -3200 && map.centerBlockZ() == 1800,
                    "Coordinate jump did not move the map");
                ConfluxMapMod.LOGGER.info("Legacy waypoint gameplay test passed");
                done = true;
                client.scheduleStop();
            }
            default -> throw new AssertionError("Unexpected test stage " + stage);
        }
        return true;
    }

    private void createPhotos(ConfluxMapClient app) throws IOException {
        final var image = Files.createTempFile("waypoint-port", ".png");
        try {
            final var pixels = new BufferedImage(160, 90, BufferedImage.TYPE_INT_RGB);
            pixels.setRGB(30, 30, 0x33CC88);
            ImageIO.write(pixels, "png", image.toFile());
            app.waypointMediaService().add(mediaKey, image).join();
            app.waypointMediaService().add(mediaKey, image).join();
        } finally {
            Files.deleteIfExists(image);
        }
    }

    private static void click(Screen screen, String key) {
        String label = Texts.translatable(key).getString();
        var widget = screen.children().stream().filter(ClickableWidget.class::isInstance)
            .map(ClickableWidget.class::cast).filter(w -> w.getMessage().getString().equals(label))
            .findFirst().orElseThrow(() -> new AssertionError("Missing control " + key));
        check(screen.mouseClicked(Widgets.x(widget) + 2, Widgets.y(widget) + 2, MouseButtons.LEFT),
            "Unable to click " + key);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static Object field(Object instance, String name) {
        for (Class<?> type = instance.getClass(); type != null; type = type.getSuperclass()) {
            try {
                var member = type.getDeclaredField(name);
                member.setAccessible(true);
                return member.get(instance);
            } catch (NoSuchFieldException ignored) {
            } catch (IllegalAccessException error) {
                throw new AssertionError(error);
            }
        }
        throw new AssertionError("Unknown field " + name);
    }
}
//#endif
