package cn.net.rms.confluxmap.mc.ui.screen;

//#if MC>=260100
//$$ import cn.net.rms.confluxmap.ConfluxMapClient;
//$$ import cn.net.rms.confluxmap.compat.MinecraftAccess;
//$$ import cn.net.rms.confluxmap.core.waypoint.Waypoint;
//$$ import cn.net.rms.confluxmap.core.waypoint.library.*;
//$$ import cn.net.rms.confluxmap.core.model.DimensionId;
//$$ import java.awt.image.BufferedImage;
//$$ import java.nio.file.Files;
//$$ import java.nio.file.Path;
//$$ import javax.imageio.ImageIO;
//$$ import com.mojang.blaze3d.platform.InputConstants;
//$$ import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
//$$ import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
//$$
//$$ @SuppressWarnings("UnstableApiUsage")
//$$ public final class WaypointLibraryClientGameTest implements FabricClientGameTest {
//$$     @Override public void runTest(ClientGameTestContext context) {
//$$         try (var world = context.worldBuilder().create()) {
//$$             context.waitFor(client -> ConfluxMapClient.get().waypointLibraryService().world() != null, 200);
//$$             var app = ConfluxMapClient.get();
//$$             context.runOnClient(client -> {
//$$                 app.config().uiTransitionsEnabled = false;
//$$                 app.waypointListMemory().options().both = true;
//$$                 app.waypointListMemory().options().compact = false;
//$$                 app.waypointListMemory().options().quickPublish = true;
//$$                 for (int i = 0; i < 96; i++) app.waypointService().current().add(Waypoint.create(
//$$                     "Library test " + i, DimensionId.OVERWORLD, i < 18 ? 16 + i : 1000 + i, 64, 16, 0xFF33CC88, "", Waypoint.Type.NORMAL));
//$$             });
//$$             var waypoint = context.computeOnClient(client -> app.waypointService().current().list().get(0));
//$$             var key = new WaypointMediaService.Key(app.waypointLibraryService().world(), false, waypoint.id);
//$$             context.runOnClient(client -> {
//$$                 app.waypointLibraryService().library().setStarred(waypoint.id, false, true);
//$$                 app.waypointLibraryService().save();
//$$             });
//$$             try {
//$$                 Path image = Files.createTempFile("confluxmap-photo-smoke-", ".png");
//$$                 try {
//$$                     BufferedImage pixels = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
//$$                     for (int y = 0; y < 180; y++) for (int x = 0; x < 320; x++) pixels.setRGB(x, y, 0xFF000000 | (x * 255 / 319) << 16 | (y * 255 / 179) << 8 | 0x55);
//$$                     ImageIO.write(pixels, "png", image.toFile()); app.waypointMediaService().add(key, image).join(); app.waypointMediaService().add(key, image).join();
//$$                 } finally { Files.deleteIfExists(image); }
//$$             } catch (java.io.IOException error) { throw new AssertionError("Cannot prepare media fixture", error); }
//$$             context.runOnClient(client -> { client.options.guiScale().set(2); client.resizeGui(); });
//$$             context.getInput().resizeWindow(1280, 720);
//$$             context.setScreen(WaypointListScreen::openedFromHotkey);
//$$             context.waitForScreen(WaypointListScreen.class); context.waitTicks(8);
//$$             context.takeScreenshot("waypoint-native-layout");
//$$             context.clickScreenButton("confluxmap.enhancements.settings");
//$$             context.waitForScreen(ConfigScreen.class);
//$$             context.takeScreenshot("waypoint-native-settings");
//$$             clickToggle(context, "favorites", true);
//$$             context.clickScreenButton("confluxmap.screen.waypoint.done");
//$$             context.waitForScreen(WaypointListScreen.class);
//$$             context.runOnClient(client -> {
//$$                 if (MinecraftAccess.screen(client).children().stream().anyMatch(child -> child instanceof cn.net.rms.confluxmap.mc.ui.widget.WaypointStarButton)) throw new AssertionError("Favorite widget remains enabled");
//$$                 if (!app.waypointLibraryService().library().isStarred(waypoint.id, false)) throw new AssertionError("Favorite data was deleted");
//$$             });
//$$             context.takeScreenshot("waypoint-favorites-off");
//$$             context.clickScreenButton("confluxmap.enhancements.settings");
//$$             clickToggle(context, "favorites", false);
//$$             clickToggle(context, "details", true);
//$$             clickToggle(context, "images", true);
//$$             context.runOnClient(client -> {
//$$                 if (app.waypointListMemory().options().details || app.waypointListMemory().options().images) throw new AssertionError("Switch did not change behavior");
//$$                 if (WaypointDetailsText.matches(new WaypointMetadata("secret note", java.util.Set.of(), false, java.util.Set.of()), "secret")) throw new AssertionError("Enhanced search still enabled");
//$$             });
//$$             context.setScreen(() -> WaypointEditScreen.forEdit(null, waypoint));
//$$             context.runOnClient(client -> { if (field(MinecraftAccess.screen(client), "detailsButton") != null) throw new AssertionError("Disabled details/images entry remains"); });
//$$             if (app.waypointMediaService().list(key).join().size() != 2) throw new AssertionError("Image data was deleted");
//$$             context.runOnClient(client -> { app.waypointListMemory().options().details = true; app.waypointListMemory().options().images = true; });
//$$             context.setScreen(WaypointListScreen::openedFromHotkey);
//$$             int nativeScale = context.computeOnClient(client -> client.getWindow().getGuiScale());
//$$             context.getInput().setCursorPos(100 * nativeScale, 180 * nativeScale);
//$$             context.getInput().scroll(-2); context.waitTick();
//$$             int nativeScroll = context.computeOnClient(client -> (Integer) field(MinecraftAccess.screen(client), "scrollOffset"));
//$$             if (nativeScroll <= 0) throw new AssertionError("Native list did not scroll");
//$$             context.clickScreenButton("confluxmap.enhancements.settings");
//$$             clickToggle(context, "compact", false);
//$$             testCompactSizeControls(context, app.waypointListMemory().options());
//$$             context.clickScreenButton("confluxmap.screen.waypoint.done");
//$$             context.waitForScreen(CompactWaypointScreen.class); context.waitTicks(8);
//$$             int scale = context.computeOnClient(client -> client.getWindow().getGuiScale());
//$$             int[] first = context.computeOnClient(client -> {
//$$                 var layout = (WaypointGridLayout) field(MinecraftAccess.screen(client), "layout");
//$$                 if (layout.columns() < 6 || layout.cells().get(0).height() != 22) throw new AssertionError("Grid is not compact");
//$$                 var cell = layout.cells().get(0);
//$$                 return new int[]{cell.x() + cell.width() / 2, cell.y() + cell.height() / 2};
//$$             });
//$$             context.getInput().setCursorPos(first[0] * scale, first[1] * scale);
//$$             context.waitFor(client -> field(MinecraftAccess.screen(client), "hovered") != null, 100);
//$$             context.waitFor(client -> field(field(MinecraftAccess.screen(client), "preview"), "texture") != null, 200);
//$$             context.takeScreenshot("waypoint-compact");
//$$             var compact = context.computeOnClient(client -> (CompactWaypointScreen) MinecraftAccess.screen(client));
//$$             int[] editAction = context.computeOnClient(client -> {
//$$                 var layout = (WaypointGridLayout) field(compact, "layout");
//$$                 var cell = layout.cells().get(0);
//$$                 var panel = (CompactWaypointPanelLayout) field(compact, "panel");
//$$                 if (panel == null || panel.x() != cell.x() + cell.width() || panel.y() != cell.y()) {
//$$                     throw new AssertionError("Hover panel is detached from its cell");
//$$                 }
//$$                 var action = ((java.util.List<?>) field(compact, "actions")).get(0);
//$$                 return new int[]{(Integer) field(action, "x") + 5, (Integer) field(action, "y") + 4};
//$$             });
//$$             int midwayX = first[0] + (editAction[0] - first[0]) * 2 / 3;
//$$             int midwayY = first[1] + (editAction[1] - first[1]) * 2 / 3;
//$$             context.runOnClient(client -> {
//$$                 var panel = (CompactWaypointPanelLayout) field(compact, "panel");
//$$                 var cell = ((WaypointGridLayout) field(compact, "layout")).cells().get(0);
//$$                 if (!panel.onApproach(cell, first[0], first[1], midwayX, midwayY)) {
//$$                     throw new AssertionError("Path toward the edit action does not stay inside the hover corridor");
//$$                 }
//$$             });
//$$             context.getInput().setCursorPos(editAction[0] * scale, editAction[1] * scale);
//$$             context.waitTicks(8);
//$$             context.runOnClient(client -> {
//$$                 var target = (WaypointMediaService.Key) field(compact, "hovered");
//$$                 if (target == null || !target.waypoint().equals(waypoint.id)) throw new AssertionError("Hover panel changed target while moving onto a button");
//$$             });
//$$             context.getInput().pressMouse(cn.net.rms.confluxmap.compat.MouseButtons.LEFT);
//$$             context.waitForScreen(WaypointEditScreen.class);
//$$             context.setScreen(() -> compact);
//$$             context.getInput().setCursorPos(first[0] * scale, first[1] * scale);
//$$             context.waitFor(client -> field(field(MinecraftAccess.screen(client), "preview"), "texture") != null, 200);
//$$             context.runOnClient(client -> app.waypointListMemory().options().listPhotoPreview = false);
//$$             context.waitTicks(3);
//$$             context.runOnClient(client -> { if (field(field(MinecraftAccess.screen(client), "preview"), "texture") != null) throw new AssertionError("List preview remains enabled"); });
//$$             context.runOnClient(client -> { app.waypointListMemory().options().listPhotoPreview = true; app.waypointListMemory().options().hoverActions = false; });
//$$             context.waitTicks(3);
//$$             context.runOnClient(client -> { if (!((java.util.List<?>) field(MinecraftAccess.screen(client), "actions")).isEmpty()) throw new AssertionError("Hover actions remain enabled"); app.waypointListMemory().options().hoverActions = true; });
//$$             context.runOnClient(client -> {
//$$                 ((net.minecraft.client.gui.components.EditBox) field(MinecraftAccess.screen(client), "search")).setValue("Library");
//$$                 app.waypointListMemory().options().remember = false;
//$$             });
//$$             context.waitTick();
//$$             context.getInput().setCursorPos(first[0] * scale, first[1] * scale);
//$$             context.getInput().scroll(-2); context.waitTick();
//$$             int compactScroll = context.computeOnClient(client -> (Integer) field(MinecraftAccess.screen(client), "scroll"));
//$$             if (compactScroll <= 0) throw new AssertionError("Compact list did not scroll");
//$$             context.clickScreenButton("confluxmap.enhancements.settings");
//$$             clickToggle(context, "compact", true);
//$$             context.clickScreenButton("confluxmap.screen.waypoint.done");
//$$             context.waitForScreen(WaypointListScreen.class);
//$$             context.runOnClient(client -> {
//$$                 String search = ((net.minecraft.client.gui.components.EditBox) field(MinecraftAccess.screen(client), "searchField")).getValue();
//$$                 if (!"Library".equals(search)) throw new AssertionError("Returning from settings lost browsing state with memory disabled");
//$$                 var state = ((WaypointListScreen) MinecraftAccess.screen(client)).browsingState();
//$$                 if (state.nativeScroll() != nativeScroll || state.compactScroll() != compactScroll) throw new AssertionError("Layout switch lost per-layout scroll with memory disabled");
//$$             });
//$$             testProductEditing(context, app, waypoint);
//$$             context.setScreen(() -> WaypointEditScreen.forEdit(null, waypoint));
//$$             context.waitFor(client -> ((WaypointPhotoPreview) field(MinecraftAccess.screen(client), "imagePreview")).ready(), 200);
//$$             context.runOnClient(client -> {
//$$                 var rows = (WaypointEditLayout.Rows) field(MinecraftAccess.screen(client), "rowLayout");
//$$                 if (rows.imageY() <= rows.markerY() || rows.imageY() + 20 > WaypointEditLayout.footerY(client.getWindow().getGuiScaledHeight())) throw new AssertionError("Image entry overlaps the edit form");
//$$             });
//$$             context.takeScreenshot("waypoint-insert-image");
//$$             context.clickScreenButton("confluxmap.photos.insert");
//$$             context.waitForScreen(WaypointImagesScreen.class);
//$$             context.waitFor(client -> field(MinecraftAccess.screen(client), "texture") != null, 200);
//$$             context.takeScreenshot("waypoint-photos");
//$$             testCtrlZoom(context);
//$$             testPhotoGestures(context);
//$$             if (app.waypointMediaService().list(key).join().get(0).rotation() != 90) throw new AssertionError("Waypoint rotation did not persist");
//$$             context.setScreen(() -> new WaypointImagesScreen(null, key, () -> true));
//$$             context.waitFor(client -> field(MinecraftAccess.screen(client), "texture") != null, 200);
//$$             context.runOnClient(client -> {
//$$                 if (((PhotoViewerScreen) MinecraftAccess.screen(client)).rotation() != 90
//$$                     || (Double) field(MinecraftAccess.screen(client), "panX") != 0) {
//$$                     throw new AssertionError("Waypoint rotation or temporary pan did not restore correctly");
//$$                 }
//$$             });
//$$             context.runOnClient(client -> WaypointPhotoTexture.invalidateAll());
//$$             context.waitFor(client -> field(MinecraftAccess.screen(client), "texture") instanceof WaypointPhotoTexture photo && !photo.closed(), 200);
//$$             context.clickScreenButton("confluxmap.photos.later");
//$$             context.waitFor(client -> !((Boolean) field(MinecraftAccess.screen(client), "busy")), 200);
//$$             context.runOnClient(client -> {
//$$                 var gallery = MinecraftAccess.screen(client);
//$$                 if (!gallery.getTitle().getString().contains(waypoint.name) || (Integer) field(gallery, "index") != 1) {
//$$                     throw new AssertionError("The waypoint title or selected image was lost after reordering");
//$$                 }
//$$             });
//$$             try {
//$$                 Path extra = Files.createTempFile("confluxmap-photo-extra-", ".png");
//$$                 try {
//$$                     ImageIO.write(new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB), "png", extra.toFile());
//$$                     context.runOnClient(client -> MinecraftAccess.screen(client).onFilesDrop(java.util.List.of(extra)));
//$$                     context.waitFor(client -> (Integer) field(MinecraftAccess.screen(client), "index") == 2
//$$                         && field(MinecraftAccess.screen(client), "texture") != null, 200);
//$$                 } finally { Files.deleteIfExists(extra); }
//$$             } catch (java.io.IOException error) { throw new AssertionError("Cannot prepare third image", error); }
//$$             context.getInput().pressKey(InputConstants.KEY_RIGHT);
//$$             context.waitTick();
//$$             context.runOnClient(client -> {
//$$                 if ((Integer) field(MinecraftAccess.screen(client), "index") != 2) throw new AssertionError("Right arrow moved past the last image");
//$$                 ((net.minecraft.client.gui.components.EditBox) field(MinecraftAccess.screen(client), "caption"))
//$$                     .setValue("Saved before changing page");
//$$             });
//$$             context.getInput().pressKey(InputConstants.KEY_LEFT);
//$$             context.waitFor(client -> (Integer) field(MinecraftAccess.screen(client), "index") == 1, 100);
//$$             if (!app.waypointMediaService().list(key).join().get(2).caption().equals("Saved before changing page")) {
//$$                 throw new AssertionError("Changing page discarded the image caption");
//$$             }
//$$             context.getInput().pressKey(InputConstants.KEY_RIGHT);
//$$             context.waitFor(client -> (Integer) field(MinecraftAccess.screen(client), "index") == 2, 100);
//$$             context.setScreen(() -> new WaypointImagesScreen(null, key, () -> true));
//$$             context.waitFor(client -> field(MinecraftAccess.screen(client), "texture") != null, 200);
//$$             context.getInput().resizeWindow(640, 480); context.waitTicks(5);
//$$             context.takeScreenshot("waypoint-photos-small");
//$$             context.getInput().resizeWindow(1280, 720);
//$$             context.runOnClient(client -> {
//$$                 var library = app.waypointLibraryService().library();
//$$                 var extra = library.get(waypoint.id);
//$$                 library.put(waypoint.id, new WaypointMetadata("Map hover note", extra.tags(), extra.starred(),
//$$                     extra.collections(), extra.resourceItems(), extra.resourceAmounts()));
//$$             });
//$$             context.setScreen(() -> FullscreenMapScreen.focusedOnWaypoint(DimensionId.OVERWORLD, waypoint.x, waypoint.z));
//$$             context.waitTicks(10);
//$$             int[] center = context.computeOnClient(client -> new int[]{client.getWindow().getScreenWidth() / 2, client.getWindow().getScreenHeight() / 2});
//$$             context.getInput().setCursorPos(center[0], center[1]);
//$$             context.waitFor(client -> field(field(MinecraftAccess.screen(client), "waypointHover"), "camera") != null, 200);
//$$             int[] camera = context.computeOnClient(client -> {
//$$                 var map = MinecraftAccess.screen(client);
//$$                 var markers = (java.util.Map<?, ?>) field(field(map, "waypointPhotoOverlay"), "markers");
//$$                 if (!markers.isEmpty()) throw new AssertionError("Icon mode rendered a standalone waypoint camera");
//$$                 var hover = field(map, "waypointHover");
//$$                 if (((java.util.List<?>) field(hover, "lines")).size() < 4) throw new AssertionError("Waypoint card omitted note, tags or products");
//$$                 return pointerAt((MapPhotoMarkers.Bounds) field(hover, "camera"), client.getWindow().getGuiScale());
//$$             });
//$$             context.getInput().setCursorPos(camera[0], camera[1]);
//$$             context.waitFor(client -> hoverReady(MinecraftAccess.screen(client)), 200);
//$$             context.takeScreenshot("waypoint-camera-hover");
//$$             context.getInput().pressMouse(cn.net.rms.confluxmap.compat.MouseButtons.LEFT);
//$$             context.waitForScreen(WaypointImagesScreen.class);
//$$             context.clickScreenButton("gui.done");
//$$             context.waitForScreen(FullscreenMapScreen.class);
//$$             context.getInput().setCursorPos(center[0], center[1]);
//$$             context.waitFor(client -> field(MinecraftAccess.screen(client), "hoveredWaypoint") != null, 100);
//$$             context.getInput().pressMouse(cn.net.rms.confluxmap.compat.MouseButtons.LEFT);
//$$             context.waitForScreen(WaypointEditScreen.class);
//$$             context.runOnClient(client -> {
//$$                 var edit = MinecraftAccess.screen(client);
//$$                 var draft = (WaypointMetadata) field(edit, "metadataDraft");
//$$                 if (field(edit, "detailsButton") == null || field(edit, "imagePreviewButton") == null
//$$                     || !Integer.valueOf(11111).equals(draft.resourceAmounts().get("minecraft:iron_ingot"))) {
//$$                     throw new AssertionError("Map waypoint editor did not open the full live-world form");
//$$                 }
//$$             });
//$$             context.clickScreenButton("confluxmap.screen.waypoint.cancel");
//$$             context.waitForScreen(FullscreenMapScreen.class);
//$$             context.getInput().setCursorPos(center[0], center[1]);
//$$             context.waitFor(client -> field(field(MinecraftAccess.screen(client), "mapPhotoMarkers"), "hoveredIcon") == null, 100);
//$$             context.runOnClient(client -> app.waypointListMemory().options().waypointPhotoMode = WaypointListMemory.MapPhotoMode.THUMBNAIL);
//$$             context.waitFor(client -> thumbnailReady(MinecraftAccess.screen(client)), 200);
//$$             context.takeScreenshot("waypoint-map-preview");
//$$             testMapLayerShortcuts(context, app);
//$$             context.runOnClient(client -> app.waypointListMemory().options().waypointPhotoMode = WaypointListMemory.MapPhotoMode.OFF);
//$$             context.waitTicks(3);
//$$             context.runOnClient(client -> { if (!((java.util.Map<?, ?>) field(field(MinecraftAccess.screen(client), "waypointPhotoOverlay"), "markers")).isEmpty()) throw new AssertionError("Map preview remains enabled"); app.waypointListMemory().options().waypointPhotoMode = WaypointListMemory.MapPhotoMode.ICON; });
//$$             context.getInput().resizeWindow(640, 480);
//$$             context.runOnClient(client -> app.waypointListMemory().options().compact = false);
//$$             context.setScreen(WaypointListScreen::openedFromHotkey);
//$$             context.waitTicks(4); context.takeScreenshot("waypoint-native-small");
//$$             context.clickScreenButton("confluxmap.enhancements.settings");
//$$             context.waitForScreen(ConfigScreen.class);
//$$             context.setScreen(() -> null);
//$$             testScreenshotLocation(context, app);
//$$             testSettingsSearch(context, app);
//$$             testFullscreenZoomLimits(context, app);
//$$             testMapPositionControls(context, app);
//$$         }
//$$     }
//$$     private static void testMapLayerShortcuts(ClientGameTestContext context, ConfluxMapClient app) {
//$$         context.getInput().resizeWindow(1280, 480);
//$$         context.waitTicks(3);
//$$         clickMapButton(context, "waypointGroupButton");
//$$         context.waitFor(client -> field(MinecraftAccess.screen(client), "screenshotVisibilityButton") != null, 80);
//$$         context.runOnClient(client -> {
//$$             final var map = MinecraftAccess.screen(client);
//$$             for (String name : java.util.List.of("structureVisibilityButton", "screenshotVisibilityButton",
//$$                 "netherPortalVisibilityButton", "endGatewayVisibilityButton")) {
//$$                 final var button = (net.minecraft.client.gui.components.AbstractWidget) field(map, name);
//$$                 if (button.getY() + button.getHeight() > client.getWindow().getGuiScaledHeight()) {
//$$                     throw new AssertionError("Short map clipped " + name);
//$$                 }
//$$             }
//$$         });
//$$         var options = app.waypointListMemory().options();
//$$         boolean structures = app.config().predictionShowStructures;
//$$         clickMapButton(context, "structureVisibilityButton");
//$$         context.waitFor(client -> app.config().predictionShowStructures != structures, 80);
//$$         clickMapButton(context, "structureVisibilityButton");
//$$         context.waitFor(client -> app.config().predictionShowStructures == structures, 80);
//$$         boolean nether = app.config().fullscreenNetherPortalsVisible;
//$$         boolean gateway = app.config().fullscreenEndGatewaysVisible;
//$$         clickMapButton(context, "netherPortalVisibilityButton");
//$$         context.waitFor(client -> app.config().fullscreenNetherPortalsVisible != nether, 80);
//$$         if (app.config().fullscreenEndGatewaysVisible != gateway) throw new AssertionError("Nether toggle changed the gateway");
//$$         clickMapButton(context, "endGatewayVisibilityButton");
//$$         context.waitFor(client -> app.config().fullscreenEndGatewaysVisible != gateway, 80);
//$$         if (app.config().fullscreenNetherPortalsVisible == nether) throw new AssertionError("Gateway toggle changed the Nether portal");
//$$         clickMapButton(context, "netherPortalVisibilityButton");
//$$         clickMapButton(context, "endGatewayVisibilityButton");
//$$         context.runOnClient(client -> options.setScreenshotMode(WaypointListMemory.MapPhotoMode.THUMBNAIL));
//$$         clickMapButton(context, "screenshotVisibilityButton");
//$$         context.waitFor(client -> options.screenshotMode == WaypointListMemory.MapPhotoMode.OFF, 80);
//$$         context.getInput().resizeWindow(1280, 720);
//$$         context.waitTicks(3);
//$$         clickMapButton(context, "screenshotVisibilityButton");
//$$         context.waitFor(client -> options.screenshotMode == WaypointListMemory.MapPhotoMode.THUMBNAIL, 80);
//$$         clickMapButton(context, "screenshotVisibilityButton");
//$$         context.waitFor(client -> options.screenshotMode == WaypointListMemory.MapPhotoMode.OFF, 80);
//$$     }
//$$
//$$     private static void clickMapButton(ClientGameTestContext context, String name) {
//$$         int[] at = context.computeOnClient(client -> {
//$$             var widget = (net.minecraft.client.gui.components.AbstractWidget) field(MinecraftAccess.screen(client), name);
//$$             int scale = client.getWindow().getGuiScale();
//$$             return new int[]{(widget.getX() + widget.getWidth() / 2) * scale, (widget.getY() + widget.getHeight() / 2) * scale};
//$$         });
//$$         context.getInput().setCursorPos(at[0], at[1]);
//$$         context.waitTick();
//$$         context.getInput().pressMouse(cn.net.rms.confluxmap.compat.MouseButtons.LEFT);
//$$     }
//$$
//$$     private static void testSettingsSearch(ClientGameTestContext context, ConfluxMapClient app) {
//$$         context.setScreen(ConfigScreen::new);
//$$         var search = context.computeOnClient(client -> (net.minecraft.client.gui.components.EditBox)
//$$             field(MinecraftAccess.screen(client), "searchField"));
//$$         context.runOnClient(client -> search.setValue("控件风格"));
//$$         context.waitFor(client -> MinecraftAccess.screen(client).children().stream().anyMatch(child ->
//$$             child instanceof net.minecraft.client.gui.components.AbstractWidget widget
//$$                 && widget.getMessage().getString().contains("Control Style")), 100);
//$$         context.takeScreenshot("settings-search-button-style");
//$$         int[] at = context.computeOnClient(client -> {
//$$             var result = MinecraftAccess.screen(client).children().stream()
//$$                 .filter(child -> child instanceof net.minecraft.client.gui.components.AbstractWidget widget
//$$                     && widget.getMessage().getString().contains("Control Style"))
//$$                 .map(net.minecraft.client.gui.components.AbstractWidget.class::cast).findFirst().orElseThrow();
//$$             int scale = client.getWindow().getGuiScale();
//$$             return new int[]{(result.getX() + result.getWidth() / 2) * scale, (result.getY() + result.getHeight() / 2) * scale};
//$$         });
//$$         context.getInput().setCursorPos(at[0], at[1]);
//$$         context.waitTick();
//$$         context.getInput().pressMouse(cn.net.rms.confluxmap.compat.MouseButtons.LEFT);
//$$         context.waitFor(client -> app.uiResourceTheme().useVanillaButtonStyle(), 100);
//$$         context.takeScreenshot("settings-search-vanilla-style");
//$$         context.getInput().pressMouse(cn.net.rms.confluxmap.compat.MouseButtons.LEFT);
//$$         context.waitFor(client -> app.config().controlStyle
//$$             == cn.net.rms.confluxmap.core.config.ConfluxConfig.ControlStyle.AUTO, 100);
//$$         context.getInput().pressMouse(cn.net.rms.confluxmap.compat.MouseButtons.LEFT);
//$$         context.waitFor(client -> app.config().controlStyle
//$$             == cn.net.rms.confluxmap.core.config.ConfluxConfig.ControlStyle.CONFLUX, 100);
//$$         context.runOnClient(client -> search.setValue("nether portals"));
//$$         context.waitFor(client -> MinecraftAccess.screen(client).children().stream().anyMatch(child ->
//$$             child instanceof net.minecraft.client.gui.components.AbstractWidget widget
//$$                 && widget.getMessage().getString().contains("Nether portals")), 100);
//$$         context.runOnClient(client -> search.setValue("qzxv-test-no-settings"));
//$$         context.waitFor(client -> (Integer) field(MinecraftAccess.screen(client), "contentHeight") == 0, 100);
//$$         context.setScreen(() -> null);
//$$     }
//$$
//$$     private static void testMapPositionControls(
//$$         ClientGameTestContext context, ConfluxMapClient app
//$$     ) {
//$$         var player = context.computeOnClient(client -> app.gameBridge().player().orElseThrow());
//$$         context.setScreen(() -> FullscreenMapScreen.focusedOnWaypoint(DimensionId.OVERWORLD, player.x(), player.z()));
//$$         var map = context.computeOnClient(client -> (FullscreenMapScreen) MinecraftAccess.screen(client));
//$$         double initialScale = context.computeOnClient(client -> (double) field(map, "scale"));
//$$         context.runOnClient(client -> {
//$$             var jump = labeledWidget(map, "confluxmap.map.coordinate_jump");
//$$             var view = labeledWidget(map, "confluxmap.map.tools.view");
//$$             if (jump.getX() != view.getX() || jump.getY() >= view.getY()) {
//$$                 throw new AssertionError("Map toolbar buttons are not vertically aligned");
//$$             }
//$$         });
//$$         context.takeScreenshot("map-before-coordinate-jump");
//$$         clickLabeledWidget(context, "confluxmap.map.coordinate_jump");
//$$         context.waitForScreen(MapCoordinateJumpScreen.class);
//$$         context.runOnClient(client -> {
//$$             if (!(boolean) field(map, "openAnnounced")) {
//$$                 throw new AssertionError("Opening the coordinate dialog closed the world map");
//$$             }
//$$         });
//$$         context.runOnClient(client -> {
//$$             var jump = MinecraftAccess.screen(client);
//$$             var x = (net.minecraft.client.gui.components.EditBox) field(jump, "xField");
//$$             var z = (net.minecraft.client.gui.components.EditBox) field(jump, "zField");
//$$             x.setValue("30000001");
//$$             if ((boolean) field(jump, "valid")) throw new AssertionError("Out-of-range coordinate enabled jump");
//$$             x.setValue("-3456");
//$$             z.setValue("-789");
//$$         });
//$$         context.takeScreenshot("map-coordinate-jump");
//$$         context.getInput().pressKey(InputConstants.KEY_RETURN);
//$$         context.waitFor(client -> MinecraftAccess.screen(client) == map
//$$             && map.centerBlockX() == -3456 && map.centerBlockZ() == -789, 100);
//$$         context.runOnClient(client -> {
//$$             if ((double) field(map, "scale") != initialScale || !map.viewingLiveSession()
//$$                 || app.gameBridge().player().orElseThrow().blockX() != player.blockX()) {
//$$                 throw new AssertionError("Coordinate jump changed the scale, dimension, or player position");
//$$             }
//$$             app.waypointListMemory().options().screenshotMode = WaypointListMemory.MapPhotoMode.OFF;
//$$             app.waypointListMemory().options().waypointPhotoMode = WaypointListMemory.MapPhotoMode.OFF;
//$$             map.jumpToCoordinates(player.blockX(), player.blockZ());
//$$             map.setScaleForTest(1.0);
//$$         });
//$$         clickLabeledWidget(context, "confluxmap.map.tools.view");
//$$         boolean gridWasVisible = context.computeOnClient(client -> app.config().fullmapChunkGrid);
//$$         clickLabeledWidget(context, "confluxmap.map.chunk_grid");
//$$         context.waitFor(client -> app.config().fullmapChunkGrid != gridWasVisible, 20);
//$$         clickLabeledWidget(context, "confluxmap.map.chunk_grid");
//$$         context.waitFor(client -> app.config().fullmapChunkGrid == gridWasVisible, 20);
//$$         clickLabeledWidget(context, "confluxmap.map.loaded_range");
//$$         var overlay = context.computeOnClient(client -> (MapLoadedRangeOverlay) field(map, "loadedRange"));
//$$         context.waitFor(client -> field(overlay, "coverage") instanceof ChunkLoadCoverage coverage
//$$             && !coverage.loaded().isEmpty(), 100);
//$$         context.getInput().setCursorPos(525, 400);
//$$         context.waitTick();
//$$         context.takeScreenshot("map-loaded-range-dim");
//$$         context.runOnClient(client -> app.config().fullscreenLoadedRangeStyle =
//$$             cn.net.rms.confluxmap.core.config.ConfluxConfig.LoadedRangeStyle.DASHED);
//$$         context.takeScreenshot("map-loaded-range-dashed");
//$$         var world = app.gameBridge().session().world();
//$$         context.runOnClient(client -> map.selectViewTarget(
//$$             new cn.net.rms.confluxmap.mc.world.FullscreenMapBrowseService.Target(world, DimensionId.NETHER)));
//$$         context.waitFor(client -> field(overlay, "coverage") instanceof ChunkLoadCoverage coverage
//$$             && coverage.loaded().isEmpty(), 100);
//$$         context.runOnClient(client -> map.selectViewTarget(
//$$             new cn.net.rms.confluxmap.mc.world.FullscreenMapBrowseService.Target(world, DimensionId.OVERWORLD)));
//$$         context.waitFor(client -> field(overlay, "coverage") instanceof ChunkLoadCoverage coverage
//$$             && !coverage.loaded().isEmpty(), 100);
//$$         final int historicChunkX = -224;
//$$         final int historicChunkZ = -96;
//$$         context.runOnClient(client -> {
//$$             var session = app.gameBridge().session();
//$$             var layer = map.viewLayer();
//$$             if (client.level.getChunkSource().hasChunk(historicChunkX, historicChunkZ)) {
//$$                 throw new AssertionError("Historical test chunk lies in the live player cache");
//$$             }
//$$             final String[] biomes = new String[256];
//$$             final byte[] kinds = new byte[256];
//$$             final int[] colors = new int[256];
//$$             java.util.Arrays.fill(biomes, "minecraft:plains");
//$$             java.util.Arrays.fill(kinds, (byte) cn.net.rms.confluxmap.core.model.SurfaceKind.LAND.ordinal());
//$$             java.util.Arrays.fill(colors, 0xFF339955);
//$$             var snapshot = new cn.net.rms.confluxmap.core.model.ChunkSnapshot(
//$$                 historicChunkX, historicChunkZ, session.token(), 1L,
//$$                 new short[256], biomes, new byte[256],
//$$                 colors, new int[256], new int[256], kinds, new byte[256]
//$$             );
//$$             map.viewMapWorlds().current().put(layer, snapshot, cn.net.rms.confluxmap.core.model.SampleSource.REAL_CACHED);
//$$             map.viewTiles().markChunkStored(session.token(), session.dimension(), layer, historicChunkX, historicChunkZ);
//$$             map.jumpToCoordinates(historicChunkX * 16, historicChunkZ * 16);
//$$             map.setScaleForTest(2.0);
//$$         });
//$$         context.waitFor(client -> field(overlay, "coverage") instanceof ChunkLoadCoverage coverage
//$$             && coverage.containsChunk(historicChunkX, historicChunkZ)
//$$             && !coverage.containsChunk(historicChunkX + 1, historicChunkZ), 100);
//$$         context.waitTicks(15);
//$$         context.takeScreenshot("map-loaded-range-historical");
//$$         context.runOnClient(client -> map.setScaleForTest(80.0));
//$$         context.takeScreenshot("map-loaded-range-minimum-zoom");
//$$         int beforeDrag = context.computeOnClient(client -> map.centerBlockX());
//$$         context.getInput().setCursorPos(525, 400);
//$$         context.waitTick();
//$$         int left = cn.net.rms.confluxmap.compat.MouseButtons.LEFT;
//$$         context.getInput().holdMouse(left);
//$$         try {
//$$             context.waitTick();
//$$             context.getInput().setCursorPos(580, 400);
//$$             context.waitFor(client -> map.centerBlockX() < beforeDrag, 100);
//$$         } finally {
//$$             context.getInput().releaseMouse(left);
//$$         }
//$$         clickLabeledWidget(context, "confluxmap.map.tools.view");
//$$         clickLabeledWidget(context, "confluxmap.map.loaded_range");
//$$         context.waitFor(client -> !app.config().fullscreenLoadedRangeVisible
//$$             && field(overlay, "coverage") == null, 100);
//$$         context.setScreen(() -> null);
//$$         context.waitTicks(60);
//$$     }
//$$
//$$     private static void clickLabeledWidget(ClientGameTestContext context, String key) {
//$$         int[] position = context.computeOnClient(client -> {
//$$             var widget = labeledWidget(MinecraftAccess.screen(client), key);
//$$             int gui = client.getWindow().getGuiScale();
//$$             return new int[]{(widget.getX() + widget.getWidth() / 2) * gui,
//$$                 (widget.getY() + widget.getHeight() / 2) * gui};
//$$         });
//$$         context.getInput().setCursorPos(position[0], position[1]);
//$$         context.waitTick();
//$$         context.getInput().pressMouse(cn.net.rms.confluxmap.compat.MouseButtons.LEFT);
//$$     }
//$$
//$$     private static net.minecraft.client.gui.components.AbstractWidget labeledWidget(
//$$         net.minecraft.client.gui.screens.Screen screen, String key
//$$     ) {
//$$         String title = net.minecraft.network.chat.Component.translatable(key).getString();
//$$         return screen.children().stream()
//$$             .filter(child -> child instanceof net.minecraft.client.gui.components.AbstractWidget button
//$$                 && title.equals(button.getMessage().getString()))
//$$             .map(net.minecraft.client.gui.components.AbstractWidget.class::cast)
//$$             .findFirst().orElseThrow();
//$$     }
//$$
//$$     private static void testFullscreenZoomLimits(ClientGameTestContext context, ConfluxMapClient app) {
//$$         context.getInput().resizeWindow(1280, 720);
//$$         var player = context.computeOnClient(client -> app.gameBridge().player().orElseThrow());
//$$         context.setScreen(() -> FullscreenMapScreen.focusedOnWaypoint(DimensionId.OVERWORLD, player.x(), player.z()));
//$$         var map = context.computeOnClient(client -> (FullscreenMapScreen) MinecraftAccess.screen(client));
//$$         clickLabeledWidget(context, "confluxmap.screen.config.title");
//$$         context.waitForScreen(ConfigScreen.class);
//$$         var search = context.computeOnClient(client -> (net.minecraft.client.gui.components.EditBox)
//$$             field(MinecraftAccess.screen(client), "searchField"));
//$$         var minInput = findZoomLimitInput(context, search, "最小缩放倍率", "Minimum Zoom");
//$$         context.runOnClient(client -> minInput.setValue("20"));
//$$         context.waitFor(client -> app.config().fullscreenMinZoomPercent == 20, 100);
//$$         int[] at = context.computeOnClient(client -> {
//$$             int gui = client.getWindow().getGuiScale();
//$$             return new int[]{(minInput.getX() + minInput.getWidth() / 2) * gui,
//$$                 (minInput.getY() + minInput.getHeight() / 2) * gui};
//$$         });
//$$         context.getInput().setCursorPos(at[0], at[1]);
//$$         context.getInput().scroll(1);
//$$         context.waitFor(client -> app.config().fullscreenMinZoomPercent == 21, 100);
//$$         context.getInput().scroll(-1);
//$$         context.waitFor(client -> app.config().fullscreenMinZoomPercent == 20, 100);
//$$         context.takeScreenshot("fullscreen-zoom-limits-tooltip");
//$$         var maxInput = findZoomLimitInput(context, search, "最大缩放倍率", "Maximum Zoom");
//$$         context.runOnClient(client -> maxInput.setValue("200"));
//$$         context.waitFor(client -> app.config().fullscreenMaxZoomPercent == 200, 100);
//$$         var saved = app.configIo().load();
//$$         if (saved.fullscreenMinZoomPercent != 20 || saved.fullscreenMaxZoomPercent != 200) {
//$$             throw new AssertionError("Fullscreen zoom limits were not saved");
//$$         }
//$$         context.clickScreenButton("confluxmap.screen.waypoint.done");
//$$         context.waitForScreen(FullscreenMapScreen.class);
//$$         context.runOnClient(client -> map.setScaleForTest(70.0));
//$$         context.getInput().setCursorPos(525, 400);
//$$         context.getInput().scroll(-1);
//$$         context.waitFor(client -> Math.abs((double) field(map, "scale") - 80.0) < 1.0e-9, 100);
//$$         context.takeScreenshot("fullscreen-map-minimum-zoom");
//$$         int startX = context.computeOnClient(client -> map.centerBlockX());
//$$         int left = cn.net.rms.confluxmap.compat.MouseButtons.LEFT;
//$$         context.getInput().holdMouse(left);
//$$         try {
//$$             context.waitTick();
//$$             context.getInput().setCursorPos(580, 400);
//$$             context.waitFor(client -> map.centerBlockX() < startX, 100);
//$$         } finally {
//$$             context.getInput().releaseMouse(left);
//$$         }
//$$         context.runOnClient(client -> map.setScaleForTest(0.07));
//$$         context.getInput().scroll(1);
//$$         context.waitFor(client -> (double) field(map, "scale") == 0.0625, 100);
//$$         context.runOnClient(client -> app.config().fullscreenMaxZoomPercent = 100);
//$$         context.setScreen(() -> new ConfigScreen(map));
//$$         context.clickScreenButton("confluxmap.screen.waypoint.done");
//$$         context.waitFor(client -> MinecraftAccess.screen(client) == map && (double) field(map, "scale") == 0.125, 100);
//$$         context.setScreen(() -> null);
//$$     }
//$$
//$$     private static net.minecraft.client.gui.components.EditBox findZoomLimitInput(
//$$         ClientGameTestContext context, net.minecraft.client.gui.components.EditBox search, String query, String label
//$$     ) {
//$$         context.runOnClient(client -> search.setValue(query));
//$$         context.waitFor(client -> MinecraftAccess.screen(client).children().stream().anyMatch(child ->
//$$             child instanceof net.minecraft.client.gui.components.EditBox edit
//$$                 && edit.getMessage().getString().startsWith(label)), 100);
//$$         return context.computeOnClient(client -> MinecraftAccess.screen(client).children().stream()
//$$             .filter(child -> child instanceof net.minecraft.client.gui.components.EditBox edit
//$$                 && edit.getMessage().getString().startsWith(label))
//$$             .map(net.minecraft.client.gui.components.EditBox.class::cast).findFirst().orElseThrow());
//$$     }
//$$
//$$     private static void testScreenshotLocation(ClientGameTestContext context, ConfluxMapClient app) {
//$$         var session = context.computeOnClient(client -> app.gameBridge().session());
//$$         var scope = MapScreenshotIndex.Scope.of(session.world(), session.dimension());
//$$         var player = context.computeOnClient(client -> app.gameBridge().player().orElseThrow());
//$$         if (app.waypointListMemory().options().screenshotMode != WaypointListMemory.MapPhotoMode.OFF) throw new AssertionError("Screenshot markers must default off");
//$$         context.getInput().pressKey(com.mojang.blaze3d.platform.InputConstants.KEY_F2);
//$$         context.waitFor(client -> !app.mapScreenshotIndex().list(scope).isEmpty(), 200);
//$$         var shot = app.mapScreenshotIndex().list(scope).get(0);
//$$         if (Math.abs(shot.location().x() - player.x()) > 0.1 || Math.abs(shot.location().z() - player.z()) > 0.1
//$$             || !Files.isRegularFile(app.mapScreenshotIndex().file(shot))) throw new AssertionError("Screenshot was not associated with its capture location");
//$$         try {
//$$             var file = app.mapScreenshotIndex().file(shot);
//$$             var block = shot.location();
//$$             double otherX = Math.floor(block.x() / 16) * 16 + (Math.floorMod((int) Math.floor(block.x()), 16) == 0 ? 2 : 0);
//$$             for (int i = 1; i <= 2; i++) {
//$$                 var extra = Files.copy(file, file.resolveSibling("chunk-test-" + i + ".png"));
//$$                 app.mapScreenshotIndex().save(new MapScreenshotIndex.Location(scope, otherX, block.y(), block.z()), extra, shot.capturedAt() - i);
//$$             }
//$$         } catch (java.io.IOException failure) { throw new AssertionError(failure); }
//$$         context.waitFor(client -> app.mapScreenshotIndex().list(scope).size() == 3, 200);
//$$         context.getInput().resizeWindow(1280, 720);
//$$         context.setScreen(() -> FullscreenMapScreen.focusedOnWaypoint(session.dimension(), shot.location().x(), shot.location().z()));
//$$         context.waitTicks(5);
//$$         context.runOnClient(client -> {
//$$             var overlay = field(MinecraftAccess.screen(client), "screenshotOverlay");
//$$             if (!((java.util.List<?>) field(overlay, "markers")).isEmpty()) throw new AssertionError("Screenshot markers visible with option off");
//$$             app.waypointListMemory().options().screenshotMode = WaypointListMemory.MapPhotoMode.ICON;
//$$         });
//$$         int[] center = context.computeOnClient(client -> new int[]{client.getWindow().getScreenWidth() / 2, client.getWindow().getScreenHeight() / 2});
//$$         context.getInput().setCursorPos(center[0], center[1]);
//$$         context.waitFor(client -> !((java.util.List<?>) field(field(MinecraftAccess.screen(client), "screenshotOverlay"), "markers")).isEmpty(), 200);
//$$         context.runOnClient(client -> {
//$$             var markers = (java.util.List<?>) field(field(MinecraftAccess.screen(client), "screenshotOverlay"), "markers");
//$$             if (markers.size() != 1 || ((java.util.List<?>) field(markers.get(0), "shots")).size() != 3) throw new AssertionError("Expected one screenshot marker with three captures");
//$$         });
//$$         context.takeScreenshot("map-screenshot-icons");
//$$         int[] camera = context.computeOnClient(client -> {
//$$             var markers = (java.util.List<?>) field(field(MinecraftAccess.screen(client), "screenshotOverlay"), "markers");
//$$             return pointerAt((MapPhotoMarkers.Bounds) field(markers.get(0), "bounds"), client.getWindow().getGuiScale());
//$$         });
//$$         context.getInput().setCursorPos(camera[0], camera[1]);
//$$         context.waitFor(client -> hoverReady(MinecraftAccess.screen(client)), 200);
//$$         context.takeScreenshot("map-screenshot-camera-hover");
//$$         context.runOnClient(client -> app.waypointListMemory().options().screenshotMode = WaypointListMemory.MapPhotoMode.THUMBNAIL);
//$$         context.waitFor(client -> thumbnailReady(MinecraftAccess.screen(client)), 200);
//$$         context.takeScreenshot("map-screenshot-thumbnail");
//$$         int[] marker = context.computeOnClient(client -> {
//$$             var markers = (java.util.List<?>) field(field(MinecraftAccess.screen(client), "screenshotOverlay"), "markers");
//$$             var bounds = (MapPhotoMarkers.Bounds) field(markers.get(0), "bounds");
//$$             int scale = client.getWindow().getGuiScale();
//$$             return new int[]{(bounds.x() + bounds.w()/2) * scale, (bounds.y() + bounds.h()/2) * scale};
//$$         });
//$$         context.getInput().setCursorPos(marker[0], marker[1]);
//$$         context.getInput().pressMouse(cn.net.rms.confluxmap.compat.MouseButtons.LEFT);
//$$         context.waitFor(client -> !(MinecraftAccess.screen(client) instanceof FullscreenMapScreen), 100);
//$$         context.waitFor(client -> field(field(MinecraftAccess.screen(client), "image"), "texture") != null, 200);
//$$         context.takeScreenshot("map-screenshot-viewer");
//$$         testCtrlZoom(context);
//$$         testPhotoGestures(context);
//$$         if (app.mapScreenshotIndex().list(scope).get(0).rotation() != 90) throw new AssertionError("Screenshot rotation did not persist");
//$$         context.getInput().pressKey(InputConstants.KEY_RIGHT);
//$$         context.waitFor(client -> (Integer) field(MinecraftAccess.screen(client), "selected") == 1, 100);
//$$         context.getInput().pressKey(InputConstants.KEY_LEFT);
//$$         context.waitFor(client -> (Integer) field(MinecraftAccess.screen(client), "selected") == 0, 100);
//$$         context.runOnClient(client -> {
//$$             if (((PhotoViewerScreen) MinecraftAccess.screen(client)).rotation() != 90) throw new AssertionError("Screenshot rotation was lost after a page switch");
//$$         });
//$$         context.setScreen(() -> null);
//$$     }
//$$     private static void testProductEditing(ClientGameTestContext context, ConfluxMapClient app, Waypoint waypoint) {
//$$         String item = "minecraft:iron_ingot";
//$$         context.setScreen(() -> WaypointEditScreen.forEdit(null, waypoint));
//$$         int[] plus = context.computeOnClient(client -> {
//$$             var screen = MinecraftAccess.screen(client);
//$$             var layout = (WaypointEditLayout.DetailsRows) field(screen, "detailsLayout");
//$$             var form = (WaypointEditLayout.Rows) field(screen, "rowLayout");
//$$             if (layout.noteY() <= form.imageY() || layout.productY() <= layout.noteY()) throw new AssertionError("Detail rows are out of order");
//$$             for (var child : screen.children()) {
//$$                 if (child instanceof net.minecraft.client.gui.components.AbstractWidget button
//$$                     && button.visible && button.getMessage().getString().equals("+")) {
//$$                     int scale = client.getWindow().getGuiScale();
//$$                     return new int[]{(button.getX() + 5) * scale, (button.getY() + 5) * scale};
//$$                 }
//$$             }
//$$             throw new AssertionError("Missing product plus button");
//$$         });
//$$         context.getInput().setCursorPos(plus[0], plus[1]);
//$$         context.getInput().pressMouse(cn.net.rms.confluxmap.compat.MouseButtons.LEFT);
//$$         context.waitForScreen(WaypointIconPickerScreen.class);
//$$         context.runOnClient(client -> ((net.minecraft.client.gui.components.EditBox)
//$$             field(MinecraftAccess.screen(client), "searchField")).setValue("iron_ingot"));
//$$         context.waitTicks(3);
//$$         int[] choice = context.computeOnClient(client -> {
//$$             var buttons = (java.util.Map<?, ?>) field(MinecraftAccess.screen(client), "buttons");
//$$             for (var entry : buttons.entrySet()) {
//$$                 var button = (net.minecraft.client.gui.components.AbstractWidget) entry.getKey();
//$$                 var data = (WaypointIconSearch.Entry) field(entry.getValue(), "entry");
//$$                 if (button.visible && data.itemId().equals(item)) {
//$$                     int scale = client.getWindow().getGuiScale();
//$$                     return new int[]{(button.getX() + 5) * scale, (button.getY() + 5) * scale};
//$$                 }
//$$             }
//$$             throw new AssertionError("Product picker did not show the searched registry item");
//$$         });
//$$         context.getInput().setCursorPos(choice[0], choice[1]);
//$$         context.getInput().pressMouse(cn.net.rms.confluxmap.compat.MouseButtons.LEFT);
//$$         context.waitForScreen(WaypointEditScreen.class);
//$$         hoverProduct(context, item);
//$$         context.takeScreenshot("waypoint-product-actions");
//$$         clickProductAction(context, true);
//$$         context.waitForScreen(WaypointProductAmountScreen.class);
//$$         context.runOnClient(client -> ((net.minecraft.client.gui.components.EditBox)
//$$             field(MinecraftAccess.screen(client), "input")).setValue("11111"));
//$$         context.clickScreenButton("gui.done");
//$$         context.waitForScreen(WaypointEditScreen.class);
//$$         if (app.waypointLibraryService().library().get(waypoint.id).resourceItems().contains(item)) {
//$$             throw new AssertionError("Product was stored before the parent form was saved");
//$$         }
//$$         context.clickScreenButton("confluxmap.screen.waypoint.done");
//$$         context.waitFor(client -> MinecraftAccess.screen(client) == null, 100);
//$$         var saved = app.waypointLibraryService().library().get(waypoint.id);
//$$         if (!Integer.valueOf(11111).equals(saved.resourceAmounts().get(item)) || !saved.tags().contains("resource")) {
//$$             throw new AssertionError("Product amount or resource tag was not saved");
//$$         }
//$$         context.setScreen(() -> WaypointEditScreen.forEdit(null, waypoint));
//$$         hoverProduct(context, item);
//$$         context.takeScreenshot("waypoint-product-compact-amount");
//$$         clickProductAction(context, false);
//$$         context.waitFor(client -> ((java.util.List<?>) field(MinecraftAccess.screen(client), "productSlots")).isEmpty(), 100);
//$$         context.clickScreenButton("confluxmap.screen.waypoint.cancel");
//$$         context.waitFor(client -> MinecraftAccess.screen(client) == null, 100);
//$$         if (!saved.equals(app.waypointLibraryService().library().get(waypoint.id))) throw new AssertionError("Cancel committed product deletion");
//$$     }
//$$
//$$     private static void hoverProduct(ClientGameTestContext context, String item) {
//$$         int[] point = context.computeOnClient(client -> {
//$$             for (Object slot : (java.util.List<?>) field(MinecraftAccess.screen(client), "productSlots")) {
//$$                 if (!item.equals(field(slot, "item"))) continue;
//$$                 var cell = (WaypointGridLayout.Cell) field(slot, "cell");
//$$                 int scale = client.getWindow().getGuiScale();
//$$                 return new int[]{(cell.x() + 8) * scale, (cell.y() + 8) * scale};
//$$             }
//$$             throw new AssertionError("Product is missing in the edit form");
//$$         });
//$$         context.getInput().setCursorPos(point[0], point[1]);
//$$         context.waitFor(client -> field(MinecraftAccess.screen(client), "productPanel") != null, 100);
//$$     }
//$$
//$$     private static void clickProductAction(ClientGameTestContext context, boolean left) {
//$$         int[] point = context.computeOnClient(client -> {
//$$             var panel = (CompactWaypointPanelLayout) field(MinecraftAccess.screen(client), "productPanel");
//$$             int scale = client.getWindow().getGuiScale();
//$$             return new int[]{(panel.x() + (left ? 15 : panel.width() - 15)) * scale, (panel.y() + 34) * scale};
//$$         });
//$$         context.getInput().setCursorPos(point[0], point[1]);
//$$         context.waitTick();
//$$         context.getInput().pressMouse(cn.net.rms.confluxmap.compat.MouseButtons.LEFT);
//$$     }
//$$
//$$     private static void testPhotoGestures(ClientGameTestContext context) {
//$$         int[] view = context.computeOnClient(client -> {
//$$             var screen = MinecraftAccess.screen(client);
//$$             int scale = client.getWindow().getGuiScale();
//$$             return new int[] {
//$$                 ((Integer) field(screen, "x") + (Integer) field(screen, "w") / 2) * scale,
//$$                 ((Integer) field(screen, "y") + (Integer) field(screen, "h") / 2) * scale, scale
//$$             };
//$$         });
//$$         int cx = view[0], cy = view[1], scale = view[2];
//$$         var input = context.getInput();
//$$         int right = cn.net.rms.confluxmap.compat.MouseButtons.RIGHT;
//$$         int left = cn.net.rms.confluxmap.compat.MouseButtons.LEFT;
//$$         input.setCursorPos(cx + 60 * scale, cy);
//$$         context.waitTicks(2);
//$$         input.holdMouse(right);
//$$         try {
//$$             context.waitFor(client -> (Integer) field(MinecraftAccess.screen(client), "dragButton") == right, 80);
//$$             input.setCursorPos(cx + 2 * scale, cy + 60 * scale);
//$$             context.waitFor(client -> ((PhotoViewerScreen) MinecraftAccess.screen(client)).rotation() == 90, 80);
//$$             input.setCursorPos(cx - 4 * scale, cy + 60 * scale);
//$$             context.waitTicks(2);
//$$             context.runOnClient(client -> {
//$$                 if (((PhotoViewerScreen) MinecraftAccess.screen(client)).rotation() != 90) throw new AssertionError("Rotation jittered inside snap range");
//$$             });
//$$             input.setCursorPos(cx - 12 * scale, cy + 60 * scale);
//$$             context.waitFor(client -> ((PhotoViewerScreen) MinecraftAccess.screen(client)).rotation() > 98, 80);
//$$             input.setCursorPos(cx, cy + 60 * scale);
//$$             context.waitFor(client -> ((PhotoViewerScreen) MinecraftAccess.screen(client)).rotation() == 90, 80);
//$$         } finally { input.releaseMouse(right); }
//$$         input.setCursorPos(cx, cy);
//$$         context.waitTicks(2);
//$$         input.holdMouse(left);
//$$         try {
//$$             context.waitFor(client -> (Integer) field(MinecraftAccess.screen(client), "dragButton") == left, 80);
//$$             input.setCursorPos(cx + 80 * scale, cy - 40 * scale);
//$$             context.waitFor(client -> (Double) field(MinecraftAccess.screen(client), "panX") > 50, 80);
//$$         } finally { input.releaseMouse(left); }
//$$         context.takeScreenshot(context.computeOnClient(client -> MinecraftAccess.screen(client) instanceof WaypointImagesScreen)
//$$             ? "waypoint-photo-rotated" : "map-screenshot-rotated");
//$$         context.clickScreenButton("confluxmap.photos.save_rotation");
//$$         context.waitFor(client -> !((PhotoViewerScreen) MinecraftAccess.screen(client)).rotationChanged(), 200);
//$$     }
//$$
//$$     private static void testCompactSizeControls(ClientGameTestContext context, WaypointListMemory.Options options) {
//$$         var sizes = context.computeOnClient(client -> {
//$$             var screen = MinecraftAccess.screen(client);
//$$             var inputs = (java.util.List<?>) field(field(screen, "rows"), "sliderInputs");
//$$             return inputs.stream().filter(value -> (Boolean) field(value, "wheelEnabled"))
//$$                 .map(IntSliderInput.class::cast).toList();
//$$         });
//$$         if (sizes.size() != 2) throw new AssertionError("Missing independent compact size settings");
//$$         var width = sizes.get(0).input();
//$$         var height = sizes.get(1).input();
//$$         int[] wheel = context.computeOnClient(client -> {
//$$             width.setValue("5");
//$$             height.setValue("999");
//$$             MinecraftAccess.screen(client).setFocused(width);
//$$             int scale = client.getWindow().getGuiScale();
//$$             return new int[]{(width.getX() + 5) * scale, (width.getY() + 5) * scale,
//$$                 (Integer) field(MinecraftAccess.screen(client), "scrollOffset")};
//$$         });
//$$         if (options.compactWidthPercent() != 30 || options.compactHeightPercent() != 300) {
//$$             throw new AssertionError("Manual size input did not clamp independently");
//$$         }
//$$         context.takeScreenshot("waypoint-compact-size-settings");
//$$         context.getInput().setCursorPos(wheel[0], wheel[1]);
//$$         context.getInput().scroll(1);
//$$         context.waitFor(client -> options.compactWidthPercent() == 31, 80);
//$$         context.runOnClient(client -> {
//$$             if ((Integer) field(MinecraftAccess.screen(client), "scrollOffset") != wheel[2]
//$$                 || !width.getValue().equals("31") || options.compactHeightPercent() != 300) {
//$$                 throw new AssertionError("Wheel did not update just the size control");
//$$             }
//$$             width.setValue("100");
//$$             height.setValue("100");
//$$             MinecraftAccess.screen(client).setFocused(null);
//$$         });
//$$     }
//$$     private static void testCtrlZoom(ClientGameTestContext context) {
//$$         context.getInput().scroll(1);
//$$         context.waitTick();
//$$         context.runOnClient(client -> {
//$$             if ((Float) field(MinecraftAccess.screen(client), "zoom") != 1f) throw new AssertionError("Plain scroll changed image zoom");
//$$         });
//$$         context.getInput().holdControl();
//$$         try {
//$$             context.getInput().scroll(1);
//$$             context.waitFor(client -> (Float) field(MinecraftAccess.screen(client), "zoom") > 1f, 100);
//$$             context.getInput().scroll(-1);
//$$             context.waitFor(client -> (Float) field(MinecraftAccess.screen(client), "zoom") == 1f, 100);
//$$         } finally { context.getInput().releaseControl(); }
//$$     }
//$$     private static int[] pointerAt(MapPhotoMarkers.Bounds bounds, int scale) {
//$$         return new int[]{(bounds.x() + bounds.w() / 2) * scale, (bounds.y() + bounds.h() / 2) * scale};
//$$     }
//$$     private static boolean hoverReady(Object screen) {
//$$         return field(field(screen, "mapPhotoMarkers"), "hoveredIcon") != null && thumbnailReady(screen);
//$$     }
//$$     private static boolean thumbnailReady(Object screen) {
//$$         var canvas = field(screen, "mapPhotoMarkers");
//$$         var textures = (java.util.Map<?, ?>) field(canvas, "textures");
//$$         return textures.values().stream().anyMatch(entry -> field(entry, "texture") != null);
//$$     }
//$$     private static void clickToggle(ClientGameTestContext context, String name, boolean oldValue) {
//$$         int[] position = context.computeOnClient(client -> {
//$$             String label = ConfigValueLabels.boolLabel("confluxmap.enhancements." + name, oldValue).getString();
//$$             for (var child : MinecraftAccess.screen(client).children()) if (child instanceof net.minecraft.client.gui.components.AbstractWidget widget && widget.getMessage().getString().equals(label)) {
//$$                 int scale = client.getWindow().getGuiScale(); return new int[]{(widget.getX() + widget.getWidth() / 2) * scale, (widget.getY() + widget.getHeight() / 2) * scale};
//$$             }
//$$             throw new AssertionError("Missing toggle " + name);
//$$         });
//$$         context.getInput().setCursorPos(position[0], position[1]);
//$$         context.getInput().pressMouse(cn.net.rms.confluxmap.compat.MouseButtons.LEFT);
//$$         context.waitTick();
//$$     }
//$$     private static Object field(Object owner, String name) {
//$$         for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
//$$             try { var field = type.getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
//$$             catch (NoSuchFieldException ignored) { }
//$$             catch (IllegalAccessException error) { throw new AssertionError(error); }
//$$         }
//$$         throw new AssertionError("No field " + name + " in " + owner.getClass());
//$$     }
//$$ }
//#else
public final class WaypointLibraryClientGameTest { private WaypointLibraryClientGameTest() {} }
//#endif
