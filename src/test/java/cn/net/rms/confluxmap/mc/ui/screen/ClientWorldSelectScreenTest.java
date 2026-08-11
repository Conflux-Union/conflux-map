package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
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
}
