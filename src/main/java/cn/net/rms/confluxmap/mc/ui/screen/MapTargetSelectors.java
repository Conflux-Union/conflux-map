package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.bridge.GameBridge;
import cn.net.rms.confluxmap.bridge.PlayerView;
import cn.net.rms.confluxmap.compat.Ids;
import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.compat.MouseButtons;
import cn.net.rms.confluxmap.compat.Regs;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.core.annotation.Annotation;
import cn.net.rms.confluxmap.core.annotation.AnnotationDraft;
import cn.net.rms.confluxmap.api.ConfluxMapEvents;
import cn.net.rms.confluxmap.core.annotation.AnnotationGeometry;
import cn.net.rms.confluxmap.core.annotation.AnnotationPersistence;
import cn.net.rms.confluxmap.core.annotation.AnnotationPoint;
import cn.net.rms.confluxmap.core.annotation.AnnotationProjection;
import cn.net.rms.confluxmap.core.api.ConfluxMapApiImpl;
import cn.net.rms.confluxmap.core.api.CustomMarkerService;
import cn.net.rms.confluxmap.core.annotation.AnnotationService;
import cn.net.rms.confluxmap.core.annotation.AnnotationStore;
import cn.net.rms.confluxmap.core.annotation.AnnotationStyle;
import cn.net.rms.confluxmap.core.annotation.AnnotationTool;
import cn.net.rms.confluxmap.core.color.DaylightModel;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.export.MapExportBounds;
import cn.net.rms.confluxmap.core.export.MapExportLoadState;
import cn.net.rms.confluxmap.core.export.MapExportRequest;
import cn.net.rms.confluxmap.core.export.MapExportResolution;
import cn.net.rms.confluxmap.core.export.MapExportSelection;
import cn.net.rms.confluxmap.core.loadstate.ChunkLoadDetailMode;
import cn.net.rms.confluxmap.core.loadstate.ChunkLoadOverlayStyle;
import cn.net.rms.confluxmap.core.loadstate.ChunkScreenRect;
import cn.net.rms.confluxmap.core.loadstate.FullscreenDisplayMode;
import cn.net.rms.confluxmap.core.measure.MeasureFormat;
import cn.net.rms.confluxmap.core.measure.MeasurePath;
import cn.net.rms.confluxmap.core.measure.MeasureState;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.TileKey;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldProfile;
import cn.net.rms.confluxmap.core.net.MapSyncProgress;
import cn.net.rms.confluxmap.core.net.ChunkLoadBand;
import cn.net.rms.confluxmap.core.net.LoadStateDeltaS2C;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointAvailability;
import cn.net.rms.confluxmap.core.net.HelloPolicyS2C;
import cn.net.rms.confluxmap.core.portal.PortalMarker;
import cn.net.rms.confluxmap.core.portal.PortalService;
import cn.net.rms.confluxmap.core.predict.BaselineSampler;
import cn.net.rms.confluxmap.core.predict.BiomeCandidateSearch;
import cn.net.rms.confluxmap.core.predict.NativeBaselineSampler;
import cn.net.rms.confluxmap.core.predict.PredictedTileKeys;
import cn.net.rms.confluxmap.core.predict.PredictionDimensions;
import cn.net.rms.confluxmap.core.predict.PredictionState;
import cn.net.rms.confluxmap.core.predict.PredictionTileService;
import cn.net.rms.confluxmap.core.predict.StructureIndex;
import cn.net.rms.confluxmap.core.predict.WorldPreset;
import cn.net.rms.confluxmap.core.radar.RadarEntry;
import cn.net.rms.confluxmap.core.radar.RadarViewRange;
import cn.net.rms.confluxmap.core.radar.ServerPlayerRadarEntries;
import cn.net.rms.confluxmap.core.radar.ServerPlayerRadarState;
import cn.net.rms.confluxmap.core.selection.ChunkSelectionMath;
import cn.net.rms.confluxmap.core.store.MapWorld;
import cn.net.rms.confluxmap.core.store.MapWorldService;
import cn.net.rms.confluxmap.core.store.ColumnStore;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.tile.BiomeTileKeys;
import cn.net.rms.confluxmap.core.tile.TileService;
import cn.net.rms.confluxmap.core.trail.PlayerTrail;
import cn.net.rms.confluxmap.core.trail.PlayerTrailProjection;
import cn.net.rms.confluxmap.core.update.UpdateCheckService;
import cn.net.rms.confluxmap.core.util.TileMath;
import cn.net.rms.confluxmap.core.util.TileViewport;
import cn.net.rms.confluxmap.core.util.ChunkViewport;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import cn.net.rms.confluxmap.core.waypoint.WaypointRenderCatalog;
import cn.net.rms.confluxmap.core.waypoint.WaypointRenderEntry;
import cn.net.rms.confluxmap.core.waypoint.WaypointVerticalRelation;
import cn.net.rms.confluxmap.core.waypoint.DimensionScale;
import cn.net.rms.confluxmap.core.waypoint.WaypointService;
import cn.net.rms.confluxmap.core.waypoint.WaypointStore;
import cn.net.rms.confluxmap.mc.net.ChunkLoadStateClient;
import cn.net.rms.confluxmap.mc.net.CompanionSession;
import cn.net.rms.confluxmap.mc.net.shared.SharedWaypointClient;
import cn.net.rms.confluxmap.mc.predict.StructureMarkerService;
import cn.net.rms.confluxmap.mc.radar.EntityIconManager;
import cn.net.rms.confluxmap.mc.radar.EntityRadarScanner;
import cn.net.rms.confluxmap.mc.radar.RadarMarkerRenderer;
import cn.net.rms.confluxmap.mc.render.TileTextureManager;
import cn.net.rms.confluxmap.mc.teleport.ClientGroundTeleportService;
import cn.net.rms.confluxmap.mc.teleport.TeleportCommandAccess;
import cn.net.rms.confluxmap.mc.ui.BiomeLabels;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import cn.net.rms.confluxmap.mc.ui.MapLayerText;
import cn.net.rms.confluxmap.mc.ui.UiIcon;
import cn.net.rms.confluxmap.mc.ui.UiResourceTheme;
import cn.net.rms.confluxmap.mc.ui.UiTextureRegion;
import cn.net.rms.confluxmap.mc.ui.AnnotationRenderer;
import cn.net.rms.confluxmap.mc.ui.DisplayModeIconCatalog;
import cn.net.rms.confluxmap.mc.ui.PlayerMarkerRenderer;
import cn.net.rms.confluxmap.mc.ui.PlayerTrailRenderer;
import cn.net.rms.confluxmap.mc.ui.WaypointMarkerRenderer;
import cn.net.rms.confluxmap.mc.ui.world.WaypointHighlightState;
import cn.net.rms.confluxmap.mc.ui.PortalMarkerRenderer;
import cn.net.rms.confluxmap.mc.ui.StructureMarkerRenderer;
import cn.net.rms.confluxmap.mc.world.ClientChunkLookup;
import cn.net.rms.confluxmap.mc.world.LayerSelector;
import cn.net.rms.confluxmap.mc.world.ClientMultiworldService;
import cn.net.rms.confluxmap.mc.world.FullscreenMapBrowseService;
import cn.net.rms.confluxmap.mc.world.FullscreenMapLayerPolicy;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.MinecraftClient;
//#if MC>=12000
//$$ import net.minecraft.client.gui.DrawContext;
//#endif
//#if MC>=12109
//$$ import net.minecraft.client.gui.Click;
//$$ import net.minecraft.client.input.KeyInput;
//#endif
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.GameRenderer;
//#if MC>=12108
//$$ import net.minecraft.client.gl.RenderPipelines;
//#elseif MC>=12103
//$$ import net.minecraft.client.render.RenderLayer;
//#endif
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import static cn.net.rms.confluxmap.mc.ui.screen.FullscreenMapScreen.*;

/** The world/dimension browse selectors and their dropdown. */
final class MapTargetSelectors {
    private static final int TARGET_SELECTOR_WIDTH = 128;
    private static final int COMPACT_TARGET_SELECTOR_HEIGHT = 16;
    private static final int RESOURCE_PACK_TARGET_SELECTOR_HEIGHT = 20;
    private static final int TARGET_SELECTOR_GAP = 4;
    private static final int TARGET_DROPDOWN_ROW_HEIGHT = 18;
    private static final int TARGET_DROPDOWN_MAX_ROWS = 8;

    private final FullscreenMapScreen screen;
    private TargetSelector openTargetSelector;
    private int targetDropdownScrollOffset;
    private Map<WorldIdentity, String> targetWorldLabels = Map.of();
    private List<WorldIdentity> targetWorldOptions = List.of();
    private List<DimensionId> targetDimensionOptions = List.of();
    private ButtonWidget worldSelectorButton;
    private ButtonWidget dimensionSelectorButton;

    MapTargetSelectors(final FullscreenMapScreen screen) {
        this.screen = screen;
    }

    boolean wheelScrolled(final double mouseX, final double mouseY, final double amount) {
        final TargetDropdown dropdown = targetDropdown();
        if (amount == 0 || dropdown == null
            || (!dropdown.contains(mouseX, mouseY) && !isOverTargetSelector(mouseX, mouseY))) {
            return false;
        }
        targetDropdownScrollOffset = DropdownScroll.afterWheel(
            targetDropdownScrollOffset,
            amount,
            dropdown.optionCount(),
            dropdown.visibleRows()
        );
        return true;
    }

    boolean dropdownOpen() {
        return openTargetSelector != null;
    }

    void closeDropdown() {
        openTargetSelector = null;
        targetDropdownScrollOffset = 0;
    }

    void clearButtons() {
        worldSelectorButton = null;
        dimensionSelectorButton = null;
    }

        enum TargetSelector { WORLD, DIMENSION }

    record TargetDropdown(
        int x,
        int y,
        int width,
        int visibleRows,
        int optionCount
    ) {
        int height() {
            return visibleRows * TARGET_DROPDOWN_ROW_HEIGHT;
        }

        boolean contains(final double mouseX, final double mouseY) {
            return mouseX >= x && mouseX < x + width
                && mouseY >= y && mouseY < y + height();
        }
    }
    void addTargetSelectors() {
        refreshTargetOptions();
        final int availableWidth = Math.max(
            180, screen.width - (MARGIN + CONTROL_SIZE + CONTROL_GAP) * 2
        );
        final int selectorWidth = Math.min(
            TARGET_SELECTOR_WIDTH,
            Math.max(80, (availableWidth - TARGET_SELECTOR_GAP) / 2)
        );
        final int selectorHeight = targetSelectorHeight();
        final int startX = (screen.width - selectorWidth * 2 - TARGET_SELECTOR_GAP) / 2;
        worldSelectorButton = screen.addWidget(Widgets.button(
            startX,
            MARGIN,
            selectorWidth,
            selectorHeight,
            targetSelectorLabel(TargetSelector.WORLD, selectorWidth),
            ignored -> toggleTargetSelector(TargetSelector.WORLD)
        ));
        dimensionSelectorButton = screen.addWidget(Widgets.button(
            startX + selectorWidth + TARGET_SELECTOR_GAP,
            MARGIN,
            selectorWidth,
            selectorHeight,
            targetSelectorLabel(TargetSelector.DIMENSION, selectorWidth),
            ignored -> toggleTargetSelector(TargetSelector.DIMENSION)
        ));
        dimensionSelectorButton.active = !dimensionOptions().isEmpty();
    }

    private int targetSelectorHeight() {
        return screen.uiTheme.useVanillaButtonStyle()
            ? RESOURCE_PACK_TARGET_SELECTOR_HEIGHT
            : COMPACT_TARGET_SELECTOR_HEIGHT;
    }

    private Text targetSelectorLabel(final TargetSelector selector, final int buttonWidth) {
        final String value = selector == TargetSelector.WORLD
            ? worldLabel(screen.viewSession().world())
            : dimensionDisplayName(screen.viewSession().dimension());
        final String prefix = Texts.translatable(
            selector == TargetSelector.WORLD
                ? "confluxmap.map.target.world"
                : "confluxmap.map.target.dimension"
        ).getString();
        final String full = prefix + ": " + value + " ▾";
        //#if MC>=260100
        //$$ return Texts.literal(screen.font().plainSubstrByWidth(full, Math.max(20, buttonWidth - 8)));
        //#else
        return Texts.literal(screen.font().trimToWidth(full, Math.max(20, buttonWidth - 8)));
        //#endif
    }

    private void toggleTargetSelector(final TargetSelector selector) {
        openTargetSelector = openTargetSelector == selector ? null : selector;
        targetDropdownScrollOffset = 0;
    }

    private Map<WorldIdentity, String> worldLabels() {
        final Map<WorldIdentity, String> labels = new LinkedHashMap<>();
        final SessionGuard.Session live = screen.gameBridge.session();
        if (live.active()) {
            labels.put(live.world(), currentWorldLabel());
        }
        if (screen.clientMultiworld.canManageProfiles()) {
            for (final ClientWorldProfile profile : screen.clientMultiworld.profiles()) {
                labels.put(screen.clientMultiworld.worldIdentity(profile), profile.displayName());
            }
        }
        return labels;
    }

    private String worldLabel(final WorldIdentity world) {
        return targetWorldLabels.getOrDefault(world, MapInfoLabels.shortWorldId(world.worldId()));
    }

    private String currentWorldLabel() {
        return screen.clientMultiworld.companionWorldName()
            .or(() -> screen.clientMultiworld.currentProfile().map(ClientWorldProfile::displayName))
            .orElseGet(() -> Texts.translatable(
                "confluxmap.screen.map_view.current_world"
            ).getString());
    }

    private List<WorldIdentity> worldOptions() {
        return targetWorldOptions;
    }

    private List<DimensionId> dimensionOptions() {
        return targetDimensionOptions;
    }

    private void refreshTargetOptions() {
        targetWorldLabels = worldLabels();
        targetWorldOptions = screen.mapBrowser.worlds(
            screen.gameBridge.session(), new ArrayList<>(targetWorldLabels.keySet())
        );
        targetDimensionOptions = screen.mapBrowser.dimensions(
            screen.gameBridge.session(), screen.viewSession().world(), advertisedDimensions()
        );
    }

    private static List<DimensionId> advertisedDimensions() {
        final HelloPolicyS2C policy = ConfluxMapClient.get().companionSession().policy();
        if (policy == null) {
            return List.of();
        }
        return policy.dims().stream()
            .map(HelloPolicyS2C.DimDescriptor::dimId)
            .map(DimensionId::parse)
            .distinct()
            .toList();
    }
    TargetDropdown targetDropdown() {
        final ButtonWidget trigger = openTargetSelector == TargetSelector.WORLD
            ? worldSelectorButton
            : openTargetSelector == TargetSelector.DIMENSION ? dimensionSelectorButton : null;
        if (trigger == null) {
            return null;
        }
        final int optionCount = targetOptions().size();
        if (optionCount == 0) {
            return null;
        }
        return new TargetDropdown(
            Widgets.x(trigger),
            Widgets.y(trigger) + trigger.getHeight(),
            trigger.getWidth(),
            Math.min(TARGET_DROPDOWN_MAX_ROWS, optionCount),
            optionCount
        );
    }

    private List<?> targetOptions() {
        return openTargetSelector == TargetSelector.WORLD
            ? worldOptions()
            : openTargetSelector == TargetSelector.DIMENSION ? dimensionOptions() : List.of();
    }

    private String targetOptionLabel(final Object option) {
        if (option instanceof WorldIdentity world) {
            return worldLabel(world);
        }
        return dimensionDisplayName((DimensionId) option);
    }

    private boolean isTargetOptionSelected(final Object option) {
        final SessionGuard.Session viewed = screen.viewSession();
        return option instanceof WorldIdentity world
            ? world.equals(viewed.world())
            : option.equals(viewed.dimension());
    }

    boolean isOverTargetSelector(final double mouseX, final double mouseY) {
        return contains(worldSelectorButton, mouseX, mouseY)
            || contains(dimensionSelectorButton, mouseX, mouseY);
    }

    private static boolean contains(
        final ButtonWidget button,
        final double mouseX,
        final double mouseY
    ) {
        return button != null
            && mouseX >= Widgets.x(button) && mouseX < Widgets.x(button) + button.getWidth()
            && mouseY >= Widgets.y(button) && mouseY < Widgets.y(button) + button.getHeight();
    }

    boolean selectTargetDropdownOption(final double mouseX, final double mouseY) {
        final TargetDropdown dropdown = targetDropdown();
        if (dropdown == null || !dropdown.contains(mouseX, mouseY)) {
            return false;
        }
        final List<?> options = targetOptions();
        final int visibleIndex = (int) ((mouseY - dropdown.y()) / TARGET_DROPDOWN_ROW_HEIGHT);
        final int optionIndex = targetDropdownScrollOffset + visibleIndex;
        if (optionIndex >= 0 && optionIndex < options.size()) {
            selectTargetOption(options.get(optionIndex));
        }
        return true;
    }

    private void selectTargetOption(final Object option) {
        if (isTargetOptionSelected(option)) {
            openTargetSelector = null;
            targetDropdownScrollOffset = 0;
            return;
        }
        final SessionGuard.Session viewed = screen.viewSession();
        if (option instanceof WorldIdentity world) {
            final List<DimensionId> dimensions = screen.mapBrowser.dimensions(
                screen.gameBridge.session(), world, advertisedDimensions()
            );
            if (dimensions.isEmpty()) {
                return;
            }
            final DimensionId dimension = dimensions.contains(viewed.dimension())
                ? viewed.dimension()
                : dimensions.contains(DimensionId.OVERWORLD)
                    ? DimensionId.OVERWORLD
                    : dimensions.get(0);
            screen.selectViewTarget(new FullscreenMapBrowseService.Target(world, dimension));
        } else {
            screen.selectViewTarget(new FullscreenMapBrowseService.Target(
                viewed.world(), (DimensionId) option
            ));
        }
        openTargetSelector = null;
        targetDropdownScrollOffset = 0;
    }

    void renderTargetDropdown(
        final GuiDraw draw,
        final int mouseX,
        final int mouseY
    ) {
        final TargetDropdown dropdown = targetDropdown();
        if (dropdown == null) {
            return;
        }
        final List<?> options = targetOptions();
        targetDropdownScrollOffset = DropdownScroll.clamp(
            targetDropdownScrollOffset, options.size(), dropdown.visibleRows()
        );
        draw.fill(
            dropdown.x() - 1,
            dropdown.y() - 1,
            dropdown.x() + dropdown.width() + 1,
            dropdown.y() + dropdown.height() + 1,
            0xFF000000
        );
        for (int visibleIndex = 0; visibleIndex < dropdown.visibleRows(); visibleIndex++) {
            final int optionIndex = targetDropdownScrollOffset + visibleIndex;
            if (optionIndex >= options.size()) {
                break;
            }
            final Object option = options.get(optionIndex);
            final int rowY = dropdown.y() + visibleIndex * TARGET_DROPDOWN_ROW_HEIGHT;
            final boolean hovered = mouseX >= dropdown.x()
                && mouseX < dropdown.x() + dropdown.width()
                && mouseY >= rowY && mouseY < rowY + TARGET_DROPDOWN_ROW_HEIGHT;
            final boolean selected = isTargetOptionSelected(option);
            draw.fill(
                dropdown.x(), rowY,
                dropdown.x() + dropdown.width(), rowY + TARGET_DROPDOWN_ROW_HEIGHT,
                hovered ? 0xFF6E6E6E : selected ? 0xFF505050 : 0xFF2A2A2A
            );
            if (selected) {
                draw.fill(
                    dropdown.x(), rowY + 2, dropdown.x() + 2,
                    rowY + TARGET_DROPDOWN_ROW_HEIGHT - 2, 0xFFFFFFFF
                );
            }
            //#if MC>=260100
            //$$ final String label = screen.font().plainSubstrByWidth(
            //$$     targetOptionLabel(option), dropdown.width() - 10
            //$$ );
            //#else
            final String label = screen.font().trimToWidth(
                targetOptionLabel(option), dropdown.width() - 10
            );
            //#endif
            draw.drawTextWithShadow(
                screen.font(),
                label,
                dropdown.x() + 5,
                rowY + (TARGET_DROPDOWN_ROW_HEIGHT - screen.font().fontHeight) / 2f,
                selected ? 0xFFFFFFFF : 0xFFE0E0E0
            );
        }
        screen.drawListScrollbar(
            draw,
            dropdown.x() + dropdown.width() - 3,
            dropdown.y(),
            dropdown.height(),
            options.size(),
            dropdown.visibleRows(),
            targetDropdownScrollOffset
        );
    }
}
