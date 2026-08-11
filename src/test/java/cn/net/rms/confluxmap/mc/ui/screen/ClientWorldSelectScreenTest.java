package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ClientWorldSelectScreenTest {
    @Test
    void narrowViewportHidesPreviewAndExpandsDetails() {
        final ClientWorldSelectScreen.PanelLayout layout =
            ClientWorldSelectScreen.panelLayout(1_024, 320);

        assertFalse(layout.previewVisible());
        assertEquals(layout.bottom() - 20, layout.functionActionsTop());
        assertEquals(layout.functionActionsTop() - 10, layout.infoBottom());
        assertEquals(layout.bottom(), layout.previewTop());
        assertTrue(ClientWorldSelectScreen.detailRowsPerColumn(layout) >= 12);
        assertEquals(1, ClientWorldSelectScreen.detailColumnCount(
            layout, 11, ClientWorldSelectScreen.detailRowsPerColumn(layout)
        ));
    }

    @Test
    void regularViewportKeepsPreviewVisible() {
        final ClientWorldSelectScreen.PanelLayout layout =
            ClientWorldSelectScreen.panelLayout(1_024, 480);

        assertTrue(layout.previewVisible());
        assertTrue(layout.previewBottom() - layout.previewTop() >= 96);
        assertEquals(layout.infoBottom() + 10, layout.functionActionsTop());
        assertEquals(layout.functionActionsTop() + 30, layout.previewTop());
    }

    @Test
    void narrowWindowKeepsEveryPanelWithinTheViewport() {
        final ClientWorldSelectScreen.PanelLayout layout = ClientWorldSelectScreen.panelLayout(240, 320);

        assertTrue(layout.listX() >= 0);
        assertTrue(layout.detailX() >= 0);
        assertTrue(layout.listWidth() > 0);
        assertTrue(layout.detailWidth() > 0);
        assertTrue(layout.listX() + layout.listWidth() <= 240);
        assertTrue(layout.detailX() + layout.detailWidth() <= 240);
        assertTrue(layout.footerX() >= 0);
        assertTrue(layout.footerX() + layout.footerWidth() <= 240);
    }

    @Test
    void dynamicCandidateReasonsUseParameterizedTranslations() {
        final ClientWorldSelectScreen.ReasonLabel visitContext =
            ClientWorldSelectScreen.reasonLabel("visit_context_2_of_5");
        final ClientWorldSelectScreen.ReasonLabel terrain =
            ClientWorldSelectScreen.reasonLabel("terrain_3_of_9");

        assertEquals("confluxmap.screen.client_world.reason.visit_context", visitContext.translationKey());
        assertArrayEquals(new Object[] { "2", "5" }, visitContext.arguments());
        assertEquals("confluxmap.screen.client_world.reason.terrain", terrain.translationKey());
        assertArrayEquals(new Object[] { "3", "9" }, terrain.arguments());
    }

    @Test
    void malformedOrUnknownReasonsRemainExplicitDiagnostics() {
        assertEquals(
            "confluxmap.screen.client_world.reason.visit_context_conflict",
            ClientWorldSelectScreen.reasonLabel("visit_context_conflict").translationKey()
        );
        assertEquals(
            "confluxmap.screen.client_world.reason.unknown",
            ClientWorldSelectScreen.reasonLabel("visit_context_unknown").translationKey()
        );
        assertEquals(
            "confluxmap.screen.client_world.reason.unknown",
            ClientWorldSelectScreen.reasonLabel("future_reason").translationKey()
        );
    }

    @Test
    void detailScrollKeepsEveryDiagnosticReachable() {
        assertEquals(0, ClientWorldSelectScreen.clampDetailScroll(-3, 24, 10));
        assertEquals(7, ClientWorldSelectScreen.clampDetailScroll(7, 24, 10));
        assertEquals(14, ClientWorldSelectScreen.clampDetailScroll(99, 24, 10));
        assertEquals(0, ClientWorldSelectScreen.clampDetailScroll(4, 8, 10));
    }

    @Test
    void keyboardAndPointerContractsKeepProfileDiagnosticsReachable() throws IOException {
        final String source = Files.readString(findProjectRoot().resolve(
            "src/main/java/cn/net/rms/confluxmap/mc/ui/screen/ClientWorldSelectScreen.java"
        )).replace("\r\n", "\n");

        final int childKeyHandling = source.indexOf("if (super.keyPressed(keyCode, scanCode, modifiers))");
        final int keyboardFallback = source.indexOf("switch (keyCode)", childKeyHandling);
        assertTrue(childKeyHandling >= 0 && keyboardFallback > childKeyHandling,
            "focused controls must receive keyboard input before screen-level selection shortcuts");
        assertTrue(source.contains("case GLFW.GLFW_KEY_UP:\n                moveSelection(-1);"));
        assertTrue(source.contains("case GLFW.GLFW_KEY_DOWN:\n                moveSelection(1);"));

        final int selectionUpdate = source.indexOf("selectedProfileId = profiles.get(index).id();");
        assertTrue(
            selectionUpdate >= 0 && source.indexOf("rebuild();", selectionUpdate) > selectionUpdate,
            "keyboard selection must rebuild the detail panel with the selected profile diagnostics"
        );
        assertTrue(source.contains(
            "mouseX >= layout.detailX() && mouseX <= layout.detailX() + layout.detailWidth()"
        ));
        assertTrue(source.contains(
            "mouseX >= layout.listX() && mouseX <= layout.listX() + layout.listWidth()"
        ));
        assertTrue(source.contains(
            "confluxmap.screen.client_world.section.diagnostics"
        ));
    }

    @Test
    void mapPreviewUsesDurableLayerForEachVanillaDimension() {
        assertEquals(
            MapLayer.SURFACE,
            ClientWorldMapPreview.layerForDimensionStorageId(DimensionId.OVERWORLD.fileName())
        );
        assertEquals(
            MapLayer.NETHER_CEILING,
            ClientWorldMapPreview.layerForDimensionStorageId(DimensionId.NETHER.fileName())
        );
        assertEquals(
            MapLayer.END_SURFACE,
            ClientWorldMapPreview.layerForDimensionStorageId(DimensionId.END.fileName())
        );
        assertEquals(
            MapLayer.SURFACE,
            ClientWorldMapPreview.layerForDimensionStorageId(DimensionId.of("example", "custom").fileName())
        );
    }

    private static Path findProjectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("common.gradle"))
                && Files.isDirectory(current.resolve("src/main/java"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate the Conflux Map project root");
    }
}
