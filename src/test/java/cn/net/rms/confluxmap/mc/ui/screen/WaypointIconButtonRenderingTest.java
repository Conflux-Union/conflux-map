package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class WaypointIconButtonRenderingTest {
    @Test
    void itemChoiceButtonsRespectTheRetainedRenderingBoundary() {
        //#if MC>=260200
        //$$ assertEquals(
        //$$     "Diamond",
        //$$     WaypointIconPickerScreen.itemChoiceButtonMessage("Diamond").getString(),
        //$$     "26.2 can retain the accessible item name because its item layer fully covers it"
        //$$ );
        //#else
        assertEquals(
            "",
            WaypointIconPickerScreen.itemChoiceButtonMessage("Diamond").getString(),
            "1.17.1 through 26.1.2 must not draw fallback text behind the item model"
        );
        //#endif
    }

    @Test
    void selectedItemButtonUsesOnlyTheItemModelAsVisibleContent() throws Exception {
        final String source = Files.readString(source("WaypointEditScreen.java"));

        assertTrue(
            source.contains("iconButtonMessage()"),
            "the item selector must use an empty visible button message"
        );
        assertTrue(
            source.contains("drawSelectedItemIcon(draw)"),
            "the item selector must draw its selected item model after the widget"
        );
    }

    @Test
    void resizeCapturesTheMarkerFormDraftBeforeReinitializing() throws Exception {
        final String source = Files.readString(source("WaypointEditScreen.java"));

        assertTrue(
            source.contains("public void resize(final int width, final int height)")
                && source.contains("public void resize(final MinecraftClient client, final int width, final int height)")
                && source.contains("captureDraft();")
                && source.contains("super.resize(width, height);")
                && source.contains("super.resize(client, width, height);"),
            "resizing the marker form must not discard the current mode inputs"
        );
    }

    private static Path source(final String fileName) {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("settings.gradle"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IllegalStateException("Could not locate the repository root");
        }
        return root.resolve(
            "src/main/java/cn/net/rms/confluxmap/mc/ui/screen/" + fileName
        );
    }
}
