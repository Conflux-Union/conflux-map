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
