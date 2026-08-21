package cn.net.rms.confluxmap.mc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.compat.Ids;
import java.util.Set;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

final class UiResourceThemeTest {
    private static final Identifier SETTINGS = Ids.of(
        "confluxmap", "textures/gui/map_settings.png"
    );

    @Test
    void xaeroWorldMapPackSuppliesOnlyTheVerifiedSemanticAtlasRegions() {
        final UiResourceTheme theme = theme(false, true, false, false, Set.of());

        assertXaeroRegion(theme, "group_waypoints.png", 213, 0, 16, 16);
        assertXaeroRegion(theme, "waypoint_manage.png", 213, 0, 16, 16);
        assertXaeroRegion(theme, "waypoint_local.png", 229, 48, 16, 16);
        assertXaeroRegion(theme, "waypoint_local_off.png", 213, 48, 16, 16);
        assertXaeroRegion(theme, "map_export.png", 133, 0, 16, 16);
        assertXaeroRegion(theme, "map_settings.png", 113, 0, 20, 20);
        assertXaeroRegion(theme, "world_profile.png", 197, 80, 16, 16);
    }

    @Test
    void projectNativeIconOverrideWinsOverXaeroCompatibility() {
        final UiResourceTheme theme = theme(false, true, false, false, Set.of(SETTINGS));

        assertEquals(UiTextureRegion.full(SETTINGS), theme.icon(SETTINGS));
    }

    @Test
    void unrelatedConfluxIconsAreNeverReplacedWithTheWrongXaeroSprite() {
        final Identifier annotation = Ids.of(
            "confluxmap", "textures/gui/annotation_drawing.png"
        );
        final UiResourceTheme theme = theme(false, true, false, false, Set.of());

        assertEquals(UiTextureRegion.full(annotation), theme.icon(annotation));
    }

    @Test
    void projectNativeFrameWinsAndUsesTheFullOverlayContract() {
        final UiResourceTheme theme = theme(true, false, true, false, Set.of());

        final UiResourceTheme.MinimapFrame frame = theme.minimapFrame(false).orElseThrow();
        assertEquals(UiResourceTheme.Layout.OVERLAY, frame.layout());
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
        assertEquals(UiTextureRegion.full(square.texture().texture()), square.texture());

        final UiResourceTheme.MinimapFrame circle = theme.minimapFrame(true).orElseThrow();
        assertEquals(UiResourceTheme.Layout.XAERO_CIRCLE, circle.layout());
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
    void missingStartupResourceManagerKeepsTheDefaultTheme() {
        final UiResourceTheme theme = new UiResourceTheme();

        theme.reload(null);

        assertTrue(theme.minimapFrame(false).isEmpty());
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

    private static void assertXaeroRegion(
        final UiResourceTheme theme,
        final String confluxFile,
        final int x,
        final int y,
        final int width,
        final int height
    ) {
        assertEquals(
            UiTextureRegion.atlas(
                Ids.of("xaeroworldmap", "gui/gui.png"),
                x, y, width, height, 256, 256
            ),
            theme.icon(Ids.of("confluxmap", "textures/gui/" + confluxFile)),
            confluxFile
        );
    }
}
