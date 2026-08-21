package cn.net.rms.confluxmap.mc.ui;

import cn.net.rms.confluxmap.compat.Ids;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;

/**
 * Resolves resource-pack UI overrides without making the renderers know pack provenance or
 * third-party atlas coordinates.
 *
 * <p>Project-native resources win over Xaero compatibility resources. A Xaero atlas is used when
 * it is supplied by a resource pack, but merely installing Xaero does not silently reskin this
 * mod. This keeps the normal Conflux appearance stable while allowing an existing Xaero pack to
 * survive a migration unchanged.
 */
public final class UiResourceTheme {
    private static final int XAERO_ATLAS_SIZE = 256;
    //#if MC>=12002
    //$$ private static final Identifier VANILLA_BUTTON_RESOURCE = Ids.of(
    //$$     "minecraft", "textures/gui/sprites/widget/button.png"
    //$$ );
    //#else
    private static final Identifier VANILLA_BUTTON_RESOURCE = Ids.of(
        "minecraft", "textures/gui/widgets.png"
    );
    //#endif
    private static final Identifier XAERO_MINIMAP_FRAME = Ids.of(
        "xaerobetterpvp", "gui/minimap_frame.png"
    );
    private static final Identifier XAERO_WORLD_MAP_GUI = Ids.of(
        "xaeroworldmap", "gui/gui.png"
    );
    private static final Identifier CONFLUX_SQUARE_FRAME = Ids.of(
        "confluxmap", "textures/gui/minimap_frame_square.png"
    );
    private static final Identifier CONFLUX_CIRCLE_FRAME = Ids.of(
        "confluxmap", "textures/gui/minimap_frame_circle.png"
    );
    private static final Identifier CONFLUX_PLAYER_MARKER = Ids.of(
        "confluxmap", "textures/gui/player_marker.png"
    );
    private static final Map<Identifier, UiIcon> XAERO_WORLD_MAP_ICONS = xaeroIcons();

    private boolean xaeroMinimapFrame;
    private boolean xaeroWorldMapGui;
    private boolean confluxSquareFrame;
    private boolean confluxCircleFrame;
    private boolean confluxPlayerMarker;
    private boolean vanillaButtonStyle;
    private Set<Identifier> overriddenConfluxIcons = Set.of();

    public UiResourceTheme() {
    }

    UiResourceTheme(
        final boolean xaeroMinimapFrame,
        final boolean xaeroWorldMapGui,
        final boolean confluxSquareFrame,
        final boolean confluxCircleFrame,
        final Set<Identifier> overriddenConfluxIcons
    ) {
        this.xaeroMinimapFrame = xaeroMinimapFrame;
        this.xaeroWorldMapGui = xaeroWorldMapGui;
        this.confluxSquareFrame = confluxSquareFrame;
        this.confluxCircleFrame = confluxCircleFrame;
        this.overriddenConfluxIcons = Set.copyOf(overriddenConfluxIcons);
    }

    public void reload(final ResourceManager manager) {
        // The client entrypoint runs before Minecraft assigns its resource manager. The registered
        // reload listener supplies the real manager during the initial resource load that follows.
        if (manager == null) {
            return;
        }
        xaeroMinimapFrame = suppliedByResourcePack(
            manager, XAERO_MINIMAP_FRAME, FabricLoader.getInstance().isModLoaded("xaerominimap")
        );
        xaeroWorldMapGui = suppliedByResourcePack(
            manager, XAERO_WORLD_MAP_GUI, FabricLoader.getInstance().isModLoaded("xaeroworldmap")
        );
        confluxSquareFrame = resourceCount(manager, CONFLUX_SQUARE_FRAME) > 0;
        confluxCircleFrame = resourceCount(manager, CONFLUX_CIRCLE_FRAME) > 0;
        confluxPlayerMarker = resourceCount(manager, CONFLUX_PLAYER_MARKER) > 0;
        vanillaButtonStyle = resourceCount(manager, VANILLA_BUTTON_RESOURCE) > 1;

        final Set<Identifier> overridden = new HashSet<>();
        for (final Identifier icon : XAERO_WORLD_MAP_ICONS.keySet()) {
            if (resourceCount(manager, icon) > 1) {
                overridden.add(icon);
            }
        }
        overriddenConfluxIcons = Set.copyOf(overridden);
    }

    public UiIcon icon(final Identifier confluxIcon) {
        if (!xaeroWorldMapGui || overriddenConfluxIcons.contains(confluxIcon)) {
            return UiIcon.monochrome(confluxIcon);
        }
        return XAERO_WORLD_MAP_ICONS.getOrDefault(confluxIcon, UiIcon.monochrome(confluxIcon));
    }

    /** Uses the effective Minecraft button skin only when another resource layer replaces it. */
    public boolean useVanillaButtonStyle() {
        return vanillaButtonStyle;
    }

    /** Optional full-color player marker supplied only by an enabled resource pack. */
    public Optional<UiTextureRegion> playerMarker() {
        return confluxPlayerMarker
            ? Optional.of(UiTextureRegion.full(CONFLUX_PLAYER_MARKER))
            : Optional.empty();
    }

    static Identifier playerMarkerResource() {
        return CONFLUX_PLAYER_MARKER;
    }

    static Identifier vanillaButtonResource() {
        return VANILLA_BUTTON_RESOURCE;
    }

    public Optional<MinimapFrame> minimapFrame(final boolean circle) {
        if (circle && confluxCircleFrame) {
            return Optional.of(MinimapFrame.overlay(CONFLUX_CIRCLE_FRAME));
        }
        if (!circle && confluxSquareFrame) {
            return Optional.of(MinimapFrame.overlay(CONFLUX_SQUARE_FRAME));
        }
        if (xaeroMinimapFrame) {
            if (circle) {
                return Optional.of(new MinimapFrame(
                    UiTextureRegion.atlas(
                        XAERO_MINIMAP_FRAME, 0, 210, 137, 4,
                        XAERO_ATLAS_SIZE, XAERO_ATLAS_SIZE
                    ),
                    Layout.XAERO_CIRCLE
                ));
            }
            return Optional.of(new MinimapFrame(
                UiTextureRegion.full(XAERO_MINIMAP_FRAME), Layout.XAERO_SQUARE
            ));
        }
        return Optional.empty();
    }

    private static boolean suppliedByResourcePack(
        final ResourceManager manager,
        final Identifier id,
        final boolean providingModLoaded
    ) {
        final int resources = resourceCount(manager, id);
        if (resources == 0) {
            return false;
        }
        return !providingModLoaded || resources > 1;
    }

    private static int resourceCount(final ResourceManager manager, final Identifier id) {
        //#if MC>=11900
        //$$ return manager.getAllResources(id).size();
        //#else
        try {
            return manager.getAllResources(id).size();
        } catch (final IOException exception) {
            return 0;
        }
        //#endif
    }

    private static Map<Identifier, UiIcon> xaeroIcons() {
        final Map<Identifier, UiIcon> icons = new HashMap<>();
        mapNativeIcon(icons, "annotation_circle.png");
        mapNativeIcon(icons, "annotation_collapse.png");
        mapNativeIcon(icons, "annotation_drawing.png");
        mapNativeIcon(icons, "annotation_eraser.png");
        mapNativeIcon(icons, "annotation_freehand.png");
        mapNativeIcon(icons, "annotation_label.png");
        mapNativeIcon(icons, "annotation_line.png");
        mapNativeIcon(icons, "annotation_persistence.png");
        mapNativeIcon(icons, "annotation_persistence_transient.png");
        mapNativeIcon(icons, "annotation_rectangle.png");
        mapNativeIcon(icons, "annotation_redo.png");
        mapNativeIcon(icons, "annotation_select.png");
        mapNativeIcon(icons, "annotation_undo.png");
        mapNativeIcon(icons, "chunk_load_state.png");
        mapNativeIcon(icons, "chunk_load_state_off.png");
        mapNativeIcon(icons, "group_actions.png");
        mapNativeIcon(icons, "group_view.png");
        mapNativeIcon(icons, "map_biome.png");
        mapNativeIcon(icons, "map_biome_off.png");
        mapNativeIcon(icons, "map_terrain.png");
        mapNativeIcon(icons, "structure_search.png");
        mapNativeIcon(icons, "structure_search_off.png");
        mapNativeIcon(icons, "waypoint_local.png");
        mapNativeIcon(icons, "waypoint_local_off.png");
        mapNativeIcon(icons, "waypoint_shared.png");
        mapNativeIcon(icons, "waypoint_shared_off.png");
        mapNativeIcon(icons, "world_profile.png");
        mapXaeroIcon(icons, "group_waypoints.png", 213, 0, 16, 16);
        mapXaeroIcon(icons, "waypoint_manage.png", 213, 0, 16, 16);
        mapXaeroIcon(icons, "map_export.png", 133, 0, 16, 16);
        mapXaeroIcon(icons, "map_settings.png", 113, 0, 20, 20);
        return Map.copyOf(icons);
    }

    static Set<Identifier> auditedIconIds() {
        return XAERO_WORLD_MAP_ICONS.keySet();
    }

    private static void mapNativeIcon(final Map<Identifier, UiIcon> icons, final String confluxFile) {
        final Identifier texture = confluxIcon(confluxFile);
        icons.put(texture, UiIcon.monochrome(texture));
    }

    private static void mapXaeroIcon(
        final Map<Identifier, UiIcon> icons,
        final String confluxFile,
        final int x,
        final int y,
        final int width,
        final int height
    ) {
        icons.put(
            confluxIcon(confluxFile),
            UiIcon.fullColor(
                UiTextureRegion.atlas(
                    XAERO_WORLD_MAP_GUI, x, y, width, height,
                    XAERO_ATLAS_SIZE, XAERO_ATLAS_SIZE
                )
            )
        );
    }

    private static Identifier confluxIcon(final String file) {
        return Ids.of("confluxmap", "textures/gui/" + file);
    }

    public record MinimapFrame(UiTextureRegion texture, Layout layout) {
        private static final int XAERO_SQUARE_CONTENT_INSET = 4;

        public static MinimapFrame overlay(final Identifier texture) {
            return new MinimapFrame(UiTextureRegion.full(texture), Layout.OVERLAY);
        }

        /**
         * Insets the map beneath frame artwork that extends four GUI pixels outside Xaero's map
         * rectangle. Full-overlay and circular layouts already define their own clipping shape.
         */
        public int contentInset() {
            return layout == Layout.XAERO_SQUARE ? XAERO_SQUARE_CONTENT_INSET : 0;
        }
    }

    public enum Layout {
        OVERLAY,
        XAERO_SQUARE,
        XAERO_CIRCLE
    }
}
