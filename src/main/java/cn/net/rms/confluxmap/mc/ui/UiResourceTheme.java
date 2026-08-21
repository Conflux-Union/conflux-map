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
    private static final Map<Identifier, UiTextureRegion> XAERO_WORLD_MAP_ICONS = xaeroIcons();

    private boolean xaeroMinimapFrame;
    private boolean xaeroWorldMapGui;
    private boolean confluxSquareFrame;
    private boolean confluxCircleFrame;
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
        xaeroMinimapFrame = suppliedByResourcePack(
            manager, XAERO_MINIMAP_FRAME, FabricLoader.getInstance().isModLoaded("xaerominimap")
        );
        xaeroWorldMapGui = suppliedByResourcePack(
            manager, XAERO_WORLD_MAP_GUI, FabricLoader.getInstance().isModLoaded("xaeroworldmap")
        );
        confluxSquareFrame = resourceCount(manager, CONFLUX_SQUARE_FRAME) > 0;
        confluxCircleFrame = resourceCount(manager, CONFLUX_CIRCLE_FRAME) > 0;

        final Set<Identifier> overridden = new HashSet<>();
        for (final Identifier icon : XAERO_WORLD_MAP_ICONS.keySet()) {
            if (resourceCount(manager, icon) > 1) {
                overridden.add(icon);
            }
        }
        overriddenConfluxIcons = Set.copyOf(overridden);
    }

    public UiTextureRegion icon(final Identifier confluxIcon) {
        if (!xaeroWorldMapGui || overriddenConfluxIcons.contains(confluxIcon)) {
            return UiTextureRegion.full(confluxIcon);
        }
        return XAERO_WORLD_MAP_ICONS.getOrDefault(confluxIcon, UiTextureRegion.full(confluxIcon));
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

    private static Map<Identifier, UiTextureRegion> xaeroIcons() {
        final Map<Identifier, UiTextureRegion> icons = new HashMap<>();
        mapXaeroIcon(icons, "group_waypoints.png", 213, 0, 16, 16);
        mapXaeroIcon(icons, "waypoint_manage.png", 213, 0, 16, 16);
        mapXaeroIcon(icons, "waypoint_local.png", 229, 48, 16, 16);
        mapXaeroIcon(icons, "waypoint_local_off.png", 213, 48, 16, 16);
        mapXaeroIcon(icons, "map_export.png", 133, 0, 16, 16);
        mapXaeroIcon(icons, "map_settings.png", 113, 0, 20, 20);
        mapXaeroIcon(icons, "world_profile.png", 197, 80, 16, 16);
        return Map.copyOf(icons);
    }

    private static void mapXaeroIcon(
        final Map<Identifier, UiTextureRegion> icons,
        final String confluxFile,
        final int x,
        final int y,
        final int width,
        final int height
    ) {
        icons.put(
            Ids.of("confluxmap", "textures/gui/" + confluxFile),
            UiTextureRegion.atlas(
                XAERO_WORLD_MAP_GUI, x, y, width, height, XAERO_ATLAS_SIZE, XAERO_ATLAS_SIZE
            )
        );
    }

    public record MinimapFrame(UiTextureRegion texture, Layout layout) {
        public static MinimapFrame overlay(final Identifier texture) {
            return new MinimapFrame(UiTextureRegion.full(texture), Layout.OVERLAY);
        }
    }

    public enum Layout {
        OVERLAY,
        XAERO_SQUARE,
        XAERO_CIRCLE
    }
}
