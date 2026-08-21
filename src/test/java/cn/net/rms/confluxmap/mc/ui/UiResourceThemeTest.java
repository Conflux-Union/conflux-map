package cn.net.rms.confluxmap.mc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.compat.Ids;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

final class UiResourceThemeTest {
    private static final Identifier SETTINGS = Ids.of(
        "confluxmap", "textures/gui/map_settings.png"
    );

    @Test
    void xaeroWorldMapPackSuppliesOnlyAuditedSemanticAtlasRegions() {
        final UiResourceTheme theme = theme(false, true, false, false, Set.of());

        assertXaeroRegion(theme, "group_waypoints.png", 213, 0, 16, 16);
        assertXaeroRegion(theme, "waypoint_manage.png", 213, 0, 16, 16);
        assertXaeroRegion(theme, "map_export.png", 133, 0, 16, 16);
        assertXaeroRegion(theme, "map_settings.png", 113, 0, 20, 20);
    }

    @Test
    void projectNativeIconOverrideWinsOverXaeroCompatibility() {
        final UiResourceTheme theme = theme(false, true, false, false, Set.of(SETTINGS));

        assertEquals(UiIcon.monochrome(SETTINGS), theme.icon(SETTINGS));
    }

    @Test
    void everyIconWithoutAnExactXaeroControlRemainsProjectNative() {
        final UiResourceTheme theme = theme(false, true, false, false, Set.of());
        final List<String> nativeOnly = List.of(
            "annotation_circle.png",
            "annotation_collapse.png",
            "annotation_drawing.png",
            "annotation_eraser.png",
            "annotation_freehand.png",
            "annotation_label.png",
            "annotation_line.png",
            "annotation_persistence.png",
            "annotation_persistence_transient.png",
            "annotation_rectangle.png",
            "annotation_redo.png",
            "annotation_select.png",
            "annotation_undo.png",
            "chunk_load_state.png",
            "chunk_load_state_off.png",
            "group_actions.png",
            "group_view.png",
            "map_biome.png",
            "map_biome_off.png",
            "map_terrain.png",
            "structure_search.png",
            "structure_search_off.png",
            "waypoint_local.png",
            "waypoint_local_off.png",
            "waypoint_shared.png",
            "waypoint_shared_off.png",
            "world_profile.png"
        );

        for (final String file : nativeOnly) {
            final Identifier icon = Ids.of("confluxmap", "textures/gui/" + file);
            assertEquals(UiIcon.monochrome(icon), theme.icon(icon), file);
        }
        assertEquals(31, UiResourceTheme.auditedIconIds().size());
    }

    @Test
    void projectNativeFrameWinsAndUsesTheFullOverlayContract() {
        final UiResourceTheme theme = theme(true, false, true, false, Set.of());

        final UiResourceTheme.MinimapFrame frame = theme.minimapFrame(false).orElseThrow();
        assertEquals(UiResourceTheme.Layout.OVERLAY, frame.layout());
        assertEquals(0, frame.contentInset());
        assertEquals(
            "confluxmap:textures/gui/minimap_frame_square.png",
            frame.texture().texture().toString()
        );
        assertEquals(UiTextureRegion.full(frame.texture().texture()), frame.texture());
    }

    @Test
    void xaeroFramePackSelectsTheActualSquareAndCircleAtlasLayouts() {
        final UiResourceTheme theme = theme(true, false, false, false, Set.of());

        final UiResourceTheme.MinimapFrame square = theme.minimapFrame(false).orElseThrow();
        assertEquals(UiResourceTheme.Layout.XAERO_SQUARE, square.layout());
        assertEquals(4, square.contentInset());
        assertEquals(UiTextureRegion.full(square.texture().texture()), square.texture());

        final UiResourceTheme.MinimapFrame circle = theme.minimapFrame(true).orElseThrow();
        assertEquals(UiResourceTheme.Layout.XAERO_CIRCLE, circle.layout());
        assertEquals(0, circle.contentInset());
        assertEquals("xaerobetterpvp:gui/minimap_frame.png", circle.texture().texture().toString());
        assertEquals(0f, circle.texture().u0());
        assertEquals(210f / 256f, circle.texture().v0());
        assertEquals(137f / 256f, circle.texture().u1());
        assertEquals(214f / 256f, circle.texture().v1());
    }

    @Test
    void noPackKeepsTheExistingCodeDrawnFrame() {
        assertTrue(theme(false, false, false, false, Set.of()).minimapFrame(false).isEmpty());
    }

    @Test
    void noPackKeepsTheCodeDrawnPlayerMarker() {
        assertTrue(new UiResourceTheme().playerMarker().isEmpty());
    }

    @Test
    void resourcePackPlayerMarkerReplacesTheCodeDrawnMarker() {
        final UiResourceTheme theme = new UiResourceTheme();

        theme.reload(resourceManagerWithLayers(UiResourceTheme.playerMarkerResource(), 1));

        assertEquals(
            UiResourceTheme.PlayerMarkerTexture.fullColor(
                UiResourceTheme.playerMarkerResource()
            ),
            theme.playerMarker().orElseThrow()
        );
    }

    @Test
    void xaeroPlayerMarkerPackUsesTheAuditedAtlasRegion() {
        final UiResourceTheme theme = new UiResourceTheme();

        theme.reload(resourceManagerWithLayers(UiResourceTheme.xaeroPlayerMarkerResource(), 1));

        final UiResourceTheme.PlayerMarkerTexture marker = theme.playerMarker().orElseThrow();
        assertEquals(
            UiTextureRegion.atlas(
                UiResourceTheme.xaeroPlayerMarkerResource(),
                49, 0, 26, 28, 256, 256
            ),
            marker.texture()
        );
        assertEquals(9.75f, marker.width());
        assertEquals(10.5f, marker.height());
        assertEquals(-4.875f, marker.x());
        assertEquals(-2.25f, marker.y());
        assertEquals(180f, marker.rotationOffset());
        assertTrue(marker.tintWithFallbackColor());
        assertEquals(1f, marker.outlineOffsetY());
    }

    @Test
    void projectNativePlayerMarkerWinsOverXaeroCompatibility() {
        final UiResourceTheme theme = new UiResourceTheme();

        theme.reload(resourceManagerWithLayers(Map.of(
            UiResourceTheme.playerMarkerResource(), 1,
            UiResourceTheme.xaeroPlayerMarkerResource(), 1
        )));

        assertEquals(
            UiResourceTheme.PlayerMarkerTexture.fullColor(
                UiResourceTheme.playerMarkerResource()
            ),
            theme.playerMarker().orElseThrow()
        );
    }

    @Test
    void installedXaeroBaseAtlasIsNotMistakenForAResourcePackOverride() {
        assertFalse(UiResourceTheme.isResourcePackOverride(1, true));
        assertTrue(UiResourceTheme.isResourcePackOverride(2, true));
        assertTrue(UiResourceTheme.isResourcePackOverride(1, false));
    }

    @Test
    void removingTheResourcePackRestoresTheCodeDrawnPlayerMarker() {
        final UiResourceTheme theme = new UiResourceTheme();
        theme.reload(resourceManagerWithLayers(UiResourceTheme.playerMarkerResource(), 1));

        theme.reload(resourceManagerWithLayers(UiResourceTheme.playerMarkerResource(), 0));

        assertTrue(theme.playerMarker().isEmpty());
    }

    @Test
    void removingTheXaeroPackRestoresTheCodeDrawnPlayerMarker() {
        final UiResourceTheme theme = new UiResourceTheme();
        theme.reload(resourceManagerWithLayers(UiResourceTheme.xaeroPlayerMarkerResource(), 1));

        theme.reload(resourceManagerWithLayers(UiResourceTheme.xaeroPlayerMarkerResource(), 0));

        assertTrue(theme.playerMarker().isEmpty());
    }

    @Test
    void missingStartupResourceManagerKeepsTheDefaultTheme() {
        final UiResourceTheme theme = new UiResourceTheme();

        theme.reload(null);

        assertTrue(theme.minimapFrame(false).isEmpty());
        assertFalse(theme.useVanillaButtonStyle());
    }

    @Test
    void baseVanillaButtonResourceKeepsTheConfluxButtonStyle() {
        final UiResourceTheme theme = new UiResourceTheme();

        theme.reload(resourceManagerWithVanillaButtonLayers(1));

        assertFalse(theme.useVanillaButtonStyle());
    }

    @Test
    void anyPackOverridingTheVanillaButtonResourceActivatesItsStyle() {
        final UiResourceTheme theme = new UiResourceTheme();

        theme.reload(resourceManagerWithVanillaButtonLayers(2));

        assertTrue(theme.useVanillaButtonStyle());
    }

    private static UiResourceTheme theme(
        final boolean xaeroMinimap,
        final boolean xaeroWorldMap,
        final boolean confluxSquare,
        final boolean confluxCircle,
        final Set<Identifier> overriddenIcons
    ) {
        return new UiResourceTheme(
            xaeroMinimap, xaeroWorldMap, confluxSquare, confluxCircle, overriddenIcons
        );
    }

    private static ResourceManager resourceManagerWithVanillaButtonLayers(final int layers) {
        return resourceManagerWithLayers(UiResourceTheme.vanillaButtonResource(), layers);
    }

    private static ResourceManager resourceManagerWithLayers(
        final Identifier resource,
        final int layers
    ) {
        return resourceManagerWithLayers(Map.of(resource, layers));
    }

    private static ResourceManager resourceManagerWithLayers(
        final Map<Identifier, Integer> layers
    ) {
        return (ResourceManager) Proxy.newProxyInstance(
            ResourceManager.class.getClassLoader(),
            new Class<?>[] {ResourceManager.class},
            (proxy, method, arguments) -> {
                if (
                    method.getName().equals("getAllResources")
                        || method.getName().equals("getResourceStack")
                ) {
                    final Identifier requested = (Identifier) arguments[0];
                    final int count = layers.getOrDefault(requested, 0);
                    return Collections.nCopies(count, null);
                }
                throw new UnsupportedOperationException(method.getName());
            }
        );
    }

    private static void assertXaeroRegion(
        final UiResourceTheme theme,
        final String confluxFile,
        final int x,
        final int y,
        final int width,
        final int height
    ) {
        assertEquals(
            UiIcon.fullColor(
                UiTextureRegion.atlas(
                    Ids.of("xaeroworldmap", "gui/gui.png"),
                    x, y, width, height, 256, 256
                )
            ),
            theme.icon(Ids.of("confluxmap", "textures/gui/" + confluxFile)),
            confluxFile
        );
    }
}
