package cn.net.rms.confluxmap.mc.ui.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
//#if MC>=12000 && MC<12104
//$$ import static org.junit.jupiter.api.Assertions.assertTrue;
//#endif

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import cn.net.rms.confluxmap.core.waypoint.WaypointRenderEntry;
//#if MC>=12000 && MC<12104
//$$ import java.net.URISyntaxException;
//$$ import java.nio.file.Files;
//$$ import java.nio.file.Path;
//#endif
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class WaypointItemHudRendererTest {
    @Test
    void publishesAnImmutableFrameSnapshot() {
        final WaypointItemHudRenderer renderer = new WaypointItemHudRenderer(null, null);
        final List<WaypointItemHudRenderer.Label> source = new ArrayList<>();
        source.add(label());

        renderer.publish(source);
        source.clear();

        assertEquals(1, renderer.snapshot().size());
        assertThrows(
            UnsupportedOperationException.class,
            () -> renderer.snapshot().add(label())
        );
    }

    @Test
    void publishingANewFrameReplacesTheOldOne() {
        final WaypointItemHudRenderer renderer = new WaypointItemHudRenderer(null, null);
        renderer.publish(List.of(label()));

        renderer.publish(List.of());

        assertEquals(List.of(), renderer.snapshot());
    }

    @Test
    void playerLabelsCarryThePortraitIdentity() {
        final UUID playerId = UUID.randomUUID();
        final WaypointItemHudRenderer.Label base = label();

        final WaypointItemHudRenderer.Label player = new WaypointItemHudRenderer.Label(
            base.waypoint(),
            base.distance3d(),
            base.projectionDistance(),
            base.animationProgress(),
            base.visibilityAlpha(),
            base.selected(),
            playerId
        );

        assertEquals(playerId, player.playerId());
    }

    //#if MC>=12000 && MC<12104
    //$$ @Test
    //$$ void itemIconsReleaseGuiDepthBeforeLaterOverlays() throws Exception {
    //$$     final String source = Files.readString(guiDrawSource());
    //$$     final int drawItem = source.indexOf("context.drawItem(stack, 0, 0);");
    //$$     final int clearDepth = source.indexOf("RenderUtil.clearGuiDepth();", drawItem);
    //$$
    //$$     assertTrue(drawItem >= 0, "the version must use DrawContext item rendering");
    //$$     assertTrue(
    //$$         clearDepth > drawItem,
    //$$         "item icons must clear their depth before player markers, pointers, or the crosshair"
    //$$     );
    //$$ }
    //#endif

    private static WaypointItemHudRenderer.Label label() {
        return new WaypointItemHudRenderer.Label(
            new WaypointRenderEntry(
                UUID.randomUUID(), "diamond", DimensionId.OVERWORLD,
                0.0, 64.0, 0.0, 0xFFFFFFFF,
                "minecraft:diamond", "", Waypoint.Type.NORMAL,
                WaypointRenderEntry.Source.LOCAL, false
            ),
            10.0,
            100.0,
            0f,
            1f,
            false
        );
    }

    //#if MC>=12000 && MC<12104
    //$$ private static Path guiDrawSource() throws URISyntaxException {
    //$$     Path current = Path.of(
    //$$         WaypointItemHudRendererTest.class.getProtectionDomain()
    //$$             .getCodeSource().getLocation().toURI()
    //$$     );
    //$$     while (current != null && !"build".equals(current.getFileName().toString())) {
    //$$         current = current.getParent();
    //$$     }
    //$$     if (current == null) {
    //$$         throw new IllegalStateException("Could not locate the version build directory");
    //$$     }
    //$$     final Path preprocessed = current.resolve(
    //$$         "preprocessed/main/java/cn/net/rms/confluxmap/mc/ui/GuiDraw.java"
    //$$     );
    //$$     if (Files.exists(preprocessed)) {
    //$$         return preprocessed;
    //$$     }
    //$$     return current.getParent().getParent().getParent().resolve(
    //$$         "src/main/java/cn/net/rms/confluxmap/mc/ui/GuiDraw.java"
    //$$     );
    //$$ }
    //#endif
}
