package cn.net.rms.confluxmap.mc.ui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.mc.render.GuiShapes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class GuiDrawPainterOrderTest {
    //#if MC<12104
    @Test
    void itemIconsRestorePainterOrderAfterDepthfulRendering() throws IOException {
        final String source = Files.readString(projectRoot().resolve(
            "src/main/java/cn/net/rms/confluxmap/mc/ui/GuiDraw.java"
        ));
        //#if MC>=12000
        //$$ final int drawItem = source.indexOf("context.drawItem(stack, 0, 0);");
        //#else
        final int drawItem = source.indexOf("client.getItemRenderer().renderInGui(stack, 0, 0);");
        //#endif
        final int clearDepth = source.indexOf("GuiShapes.clearGuiDepth();", drawItem);

        assertTrue(drawItem >= 0, "the version's depthful item renderer must be present");
        assertTrue(
            clearDepth > drawItem,
            "item icons must release depth before later GUI components are drawn"
        );
    }
    //#endif

    @Test
    void depthBarrierExistsOnlyWhileVanillaItemsUseGuiDepth() {
        //#if MC<12104
        assertDoesNotThrow(() -> GuiShapes.class.getDeclaredMethod("clearGuiDepth"));
        //#else
        //$$ assertThrows(
        //$$     NoSuchMethodException.class,
        //$$     () -> GuiShapes.class.getDeclaredMethod("clearGuiDepth")
        //$$ );
        //#endif
    }

    @Test
    void guiComponentsDoNotManageTheDepthBarrierDirectly() throws IOException {
        final Path sourceRoot = projectRoot().resolve("src/main/java/cn/net/rms/confluxmap/mc/ui");
        try (var files = Files.walk(sourceRoot)) {
            assertEquals(
                1,
                files.filter(path -> path.toString().endsWith(".java"))
                    .map(GuiDrawPainterOrderTest::readString)
                    .filter(source -> source.contains("GuiShapes.clearGuiDepth();"))
                    .count(),
                "only GuiDraw may release depth between GUI components"
            );
        }
    }

    //#if MC<12106
    @Test
    void itemOverlayTextLiftsAboveTheItemDepthPlane() throws IOException {
        // Vanilla lifts its own slot count to z=200 because item models write their own GUI
        // depth through 1.21.5 (DrawContext.drawStackCount); a flat badge over an icon would
        // be hidden by that depth. The render-state pipeline (1.21.6+) needs no lift.
        final String source = readString(projectRoot().resolve(
            "src/main/java/cn/net/rms/confluxmap/mc/ui/GuiDraw.java"
        ));
        final int method = source.indexOf("public int drawTextOverIcons");
        assertTrue(method >= 0, "the item-overlay text helper must exist");
        final int boundary = source.indexOf("//#if MC>=12106", method);
        assertTrue(boundary > method, "the lift must be gated exactly at 1.21.6");
        final int lift = source.indexOf("translate(0, 0, 200)", boundary);
        assertTrue(
            lift < source.indexOf("//#endif", boundary),
            "versions below 1.21.6 must lift the text above the item depth plane"
        );
    }
    //#else
    //$$ @Test
    //$$ void itemOverlayTextDelegatesWhenItemsPaintInOrder() throws IOException {
    //$$     final String source = readString(projectRoot().resolve(
    //$$         "src/main/java/cn/net/rms/confluxmap/mc/ui/GuiDraw.java"
    //$$     ));
    //$$     final int method = source.indexOf("public int drawTextOverIcons");
    //$$     assertTrue(method >= 0, "the item-overlay text helper must exist");
    //$$     final int boundary = source.indexOf("//#if MC>=12106", method);
    //$$     assertTrue(boundary > method, "the delegate must be gated exactly at 1.21.6");
    //$$     final int delegate = source.indexOf(
    //$$         "return drawTextWithShadow(renderer, text, x, y, color);", boundary
    //$$     );
    //$$     assertTrue(
    //$$         delegate > boundary,
    //$$         "items paint in order here; the helper must delegate instead of lifting depth"
    //$$     );
    //$$ }
    //#endif

    private static String readString(final Path path) {
        try {
            return Files.readString(path);
        } catch (final IOException e) {
            throw new IllegalStateException("Could not read " + path, e);
        }
    }

    private static Path projectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("Could not locate the repository root");
        }
        return current;
    }
}
