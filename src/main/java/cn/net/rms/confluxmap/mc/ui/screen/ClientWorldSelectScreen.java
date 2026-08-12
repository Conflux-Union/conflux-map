package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldProfile;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldProfileDeletionService;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldResolution;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldVisit;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import cn.net.rms.confluxmap.mc.world.ClientMultiworldService;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.MinecraftClient;
//#if MC>=12109
//$$ import net.minecraft.client.gui.Click;
//$$ import net.minecraft.client.input.KeyInput;
//#endif
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.text.OrderedText;
import net.minecraft.text.StringVisitable;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

/** Manual fallback and profile manager for client-only proxy world recognition. */
public final class ClientWorldSelectScreen extends ConfluxScreen {
    private static final int MAIN_TOP = 58;
    private static final int ROW_HEIGHT = 24;
    private static final int GAP = 3;
    private static final int PANEL_GAP = 10;
    private static final int CONTENT_MAX_WIDTH = 800;
    private static final int LIST_MIN_WIDTH = 80;
    private static final int LIST_MAX_WIDTH = 240;
    private static final int FOOTER_HEIGHT = 46;
    private static final int FOOTER_GAP = 8;
    private static final int FUNCTION_ACTION_HEIGHT = 20;
    private static final int MANAGEMENT_BUTTON_MAX_WIDTH = 120;
    private static final int NARROW_VIEWPORT_HEIGHT = 360;
    private static final int REGULAR_INFO_MAX_HEIGHT = 210;
    private static final int MIN_PREVIEW_PANEL_HEIGHT = 96;
    private static final int PREVIEW_HEADER_HEIGHT = 18;
    private static final int PREVIEW_META_HEIGHT = 14;
    private static final int LIST_SCROLLBAR_WIDTH = 6;
    private static final int DETAIL_LINE_HEIGHT = 13;
    private static final int NARROW_DETAIL_LINE_HEIGHT = 11;
    private static final DateTimeFormatter VISIT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault());

    private final Screen parent;
    private final KeyBinding openMapKey;
    private final boolean openMapAfterSelection;
    private final ClientMultiworldService worlds;
    private int scrollOffset;
    private int detailScrollOffset;
    private int detailCount;
    private int profileCount;
    private boolean waitingToOpenMap;
    private String selectedProfileId;
    private String pendingDeleteProfileId;
    private String pendingClearProfileId;
    private String operationError;
    private String hoveredDetailText;
    private List<String> hoveredCandidateDetails;
    private final List<CandidateRowDiagnostic> candidateRowDiagnostics = new ArrayList<>();
    private boolean draggingScrollbar;
    private ClientWorldMapPreview mapPreview;
    private String previewProfileId;
    private String previewDimensionId;
    private long previewVisitTimestamp = Long.MIN_VALUE;
    private int previewRasterWidth;
    private int previewRasterHeight;

    public ClientWorldSelectScreen(
        final Screen parent,
        final KeyBinding openMapKey,
        final boolean openMapAfterSelection
    ) {
        super(Texts.translatable("confluxmap.screen.client_world.title"));
        this.parent = parent;
        this.openMapKey = openMapKey;
        this.openMapAfterSelection = openMapAfterSelection;
        this.worlds = ConfluxMapClient.get().clientMultiworldService();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        rebuild();
    }

    private void rebuild() {
        clearChildren();
        candidateRowDiagnostics.clear();
        final boolean registryAvailable = worlds.profileRegistryAvailable();
        final List<ClientWorldProfile> profiles = worlds.profiles();
        profileCount = profiles.size();
        final int visible = visibleRows();
        scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, profiles.size() - visible)));
        final PanelLayout layout = panelLayout();
        final String currentId = worlds.currentProfile().map(ClientWorldProfile::id).orElse(null);
        if (selectedProfileId == null || profiles.stream().noneMatch(profile -> profile.id().equals(selectedProfileId))) {
            selectedProfileId = currentId != null ? currentId : profiles.stream()
                .findFirst()
                .map(ClientWorldProfile::id)
                .orElse(null);
        }
        final int end = Math.min(profiles.size(), scrollOffset + visible);
        for (int index = scrollOffset; index < end; index++) {
            final ClientWorldProfile profile = profiles.get(index);
            final int y = layout.listRowsTop() + (index - scrollOffset) * ROW_HEIGHT;
            String rowLabel = profile.displayName();
            if (profile.id().equals(currentId)) {
                rowLabel = Texts.translatable("confluxmap.screen.client_world.row_current", rowLabel).getString();
            }
            if (profile.id().equals(selectedProfileId)) {
                rowLabel = Texts.translatable("confluxmap.screen.client_world.row_selected", rowLabel).getString();
            }
            final String visibleRowLabel = rowLabel;
            addDrawableChild(Widgets.button(
                layout.listX() + 4, y, layout.listButtonWidth(), 20,
                Texts.literal(clipped(visibleRowLabel, layout.listButtonWidth() - 12)),
                ignored -> {
                    clearPendingActions();
                    selectedProfileId = profile.id();
                    detailScrollOffset = 0;
                    rebuild();
                }
            ));
            candidateRowDiagnostics.add(new CandidateRowDiagnostic(
                layout.listX() + 4, y, layout.listButtonWidth(), 20, candidateHoverDetails(profile)
            ));
        }

        final ClientWorldProfile selected = selectedProfile();
        if (selected != null) {
            final int detailCapacity = Math.max(1,
                detailRowsPerColumn(layout) * detailColumnCount(
                    layout, profileDetails(selected).size(), detailRowsPerColumn(layout)
                )
            );
            detailCount = profileDetails(selected).size();
            detailScrollOffset = clampDetailScroll(detailScrollOffset, detailCount, detailCapacity);
            ensureMapPreview(selected, layout);
            addWorldFunctionActions(layout, selected);
        } else {
            closeMapPreview();
        }
        addFooterActions(layout, selected, registryAvailable);
    }

    private void ensureMapPreview(final ClientWorldProfile profile, final PanelLayout layout) {
        if (!layout.previewVisible()) {
            closeMapPreview();
            return;
        }
        final ClientWorldVisit visit = latestVisit(profile);
        final WorldIdentity identity = worlds.identityForProfile(profile.id()).orElse(null);
        if (visit == null || identity == null) {
            closeMapPreview();
            return;
        }
        final int rasterWidth = Math.max(1, layout.previewWidth() - 2);
        final int rasterHeight = Math.max(1,
            layout.previewBottom() - layout.previewTop() - PREVIEW_HEADER_HEIGHT - PREVIEW_META_HEIGHT - 2);
        if (mapPreview != null
            && profile.id().equals(previewProfileId)
            && visit.dimensionId().equals(previewDimensionId)
            && visit.lastVisitedAtEpochMs() == previewVisitTimestamp
            && rasterWidth == previewRasterWidth
            && rasterHeight == previewRasterHeight) {
            return;
        }
        closeMapPreview();
        final double markerX = visit.lastPosition() == null ? Double.NaN : visit.lastPosition().x();
        final double markerZ = visit.lastPosition() == null ? Double.NaN : visit.lastPosition().z();
        mapPreview = new ClientWorldMapPreview(
            identity,
            visit.dimensionId(),
            markerX,
            markerZ,
            rasterWidth,
            rasterHeight
        );
        previewProfileId = profile.id();
        previewDimensionId = visit.dimensionId();
        previewVisitTimestamp = visit.lastVisitedAtEpochMs();
        previewRasterWidth = rasterWidth;
        previewRasterHeight = rasterHeight;
    }

    private void closeMapPreview() {
        if (mapPreview != null) {
            mapPreview.close();
            mapPreview = null;
        }
        previewProfileId = null;
        previewDimensionId = null;
        previewVisitTimestamp = Long.MIN_VALUE;
        previewRasterWidth = 0;
        previewRasterHeight = 0;
    }

    private void addWorldFunctionActions(final PanelLayout layout, final ClientWorldProfile profile) {
        final int x = layout.detailX();
        final int panelWidth = layout.detailWidth();
        final int buttonWidth = (panelWidth - GAP * 3) / 4;
        addDrawableChild(Widgets.button(
            x, layout.functionActionsTop(), buttonWidth, FUNCTION_ACTION_HEIGHT,
            Texts.translatable("confluxmap.screen.client_world.select"),
            ignored -> select(profile.id())
        ));
        addDrawableChild(Widgets.button(
            x + buttonWidth + GAP, layout.functionActionsTop(), buttonWidth, FUNCTION_ACTION_HEIGHT,
            Texts.translatable("confluxmap.screen.client_world.commands"),
            ignored -> openCommandEditor(profile)
        ));
        addDrawableChild(Widgets.button(
            x + (buttonWidth + GAP) * 2, layout.functionActionsTop(), buttonWidth, FUNCTION_ACTION_HEIGHT,
            Texts.translatable("confluxmap.screen.client_world.rename"),
            ignored -> openNameEditor(profile)
        ));
        final ButtonWidget forget = addDrawableChild(Widgets.button(
            x + (buttonWidth + GAP) * 3, layout.functionActionsTop(),
            panelWidth - (buttonWidth + GAP) * 3, FUNCTION_ACTION_HEIGHT,
            Texts.translatable(
                profile.id().equals(pendingClearProfileId)
                    ? "confluxmap.screen.client_world.forget_confirm_maps_kept"
                    : "confluxmap.screen.client_world.forget"
            ),
            ignored -> forget(profile.id())
        ));
        forget.active = profile.bindingCount() > 0;
    }

    private void addFooterActions(
        final PanelLayout layout,
        final ClientWorldProfile profile,
        final boolean registryAvailable
    ) {
        final int buttonWidth = Math.min(
            MANAGEMENT_BUTTON_MAX_WIDTH, (layout.footerWidth() - GAP * 2) / 3
        );
        final int rowWidth = buttonWidth * 3 + GAP * 2;
        final int rowX = layout.footerX() + (layout.footerWidth() - rowWidth) / 2;
        final int rowY = layout.footerActionsTop();
        addDrawableChild(Widgets.button(
            rowX, rowY, buttonWidth, 20,
            Texts.translatable(
                registryAvailable
                    ? "confluxmap.screen.client_world.create"
                    : "confluxmap.screen.client_world.retry_load"
            ),
            ignored -> {
                if (registryAvailable) {
                    openNameEditor(null);
                } else {
                    retryRegistryLoad();
                }
            }
        ));
        addDrawableChild(Widgets.button(
            rowX + buttonWidth + GAP, rowY, buttonWidth, 20,
            Texts.translatable("confluxmap.screen.client_world.back"),
            ignored -> onClose()
        ));
        final ButtonWidget delete = addDrawableChild(Widgets.button(
            rowX + (buttonWidth + GAP) * 2, rowY, buttonWidth, 20,
            Texts.translatable(
                profile == null ? "confluxmap.screen.client_world.delete" :
                    profile.id().equals(pendingDeleteProfileId)
                        ? "confluxmap.screen.client_world.delete_confirm_recoverable"
                        : "confluxmap.screen.client_world.delete"
            ).formatted(Formatting.RED),
            ignored -> {
                if (profile != null) {
                    delete(profile);
                }
            }
        ));
        final boolean current = profile != null
            && profile.id().equals(worlds.currentProfile().map(ClientWorldProfile::id).orElse(null));
        delete.active = profile != null && !current;
        if (current) {
            setDisabledTooltip(delete, "confluxmap.screen.client_world.delete_current");
        }
    }

    @Override
    public void tick() {
        if (waitingToOpenMap && ConfluxMapClient.get().sessionGuard().current().active()) {
            waitingToOpenMap = false;
            MinecraftAccess.setScreen(MinecraftClient.getInstance(), new FullscreenMapScreen(openMapKey));
        }
    }

    private void select(final String profileId) {
        clearPendingActions();
        if (worlds.select(profileId).applied()) {
            finishSelection();
        } else {
            operationError = worlds.persistenceError();
            rebuild();
        }
    }

    private void forget(final String profileId) {
        if (!profileId.equals(pendingClearProfileId)) {
            pendingClearProfileId = profileId;
            pendingDeleteProfileId = null;
            operationError = null;
            rebuild();
            return;
        }
        pendingClearProfileId = null;
        if (!worlds.clearBindings(profileId).applied()) {
            operationError = worlds.persistenceError();
        }
        rebuild();
    }

    private void delete(final ClientWorldProfile profile) {
        if (!profile.id().equals(pendingDeleteProfileId)) {
            pendingDeleteProfileId = profile.id();
            pendingClearProfileId = null;
            operationError = null;
            rebuild();
            return;
        }
        final ClientWorldProfileDeletionService.DeletionResult result = worlds.delete(profile.id());
        pendingDeleteProfileId = null;
        if (result.deleted()) {
            selectedProfileId = null;
            operationError = null;
        } else {
            operationError = result.error();
        }
        rebuild();
    }

    private void openNameEditor(final ClientWorldProfile profile) {
        clearPendingActions();
        MinecraftAccess.setScreen(MinecraftClient.getInstance(), new ClientWorldNameScreen(
            this,
            profile == null ? null : profile.displayName(),
            name -> {
                if (profile == null) {
                    if (worlds.createAndSelect(name).applied()) {
                        finishSelection();
                    } else {
                        operationError = worlds.persistenceError();
                        rebuild();
                    }
                } else {
                    if (!worlds.rename(profile.id(), name).applied()) {
                        operationError = worlds.persistenceError();
                    }
                    rebuild();
                }
            }
        ));
    }

    private void openCommandEditor(final ClientWorldProfile profile) {
        clearPendingActions();
        MinecraftAccess.setScreen(MinecraftClient.getInstance(), new ClientWorldCommandScreen(this, profile.id()));
    }

    private void retryRegistryLoad() {
        clearPendingActions();
        final var result = worlds.retryProfileRegistryLoad();
        operationError = result.applied() ? null : result.error();
        if (result.applied()) {
            selectedProfileId = null;
        }
        rebuild();
    }

    private void finishSelection() {
        if (parent != null) {
            MinecraftAccess.setScreen(MinecraftClient.getInstance(), parent);
        } else if (openMapAfterSelection) {
            waitingToOpenMap = true;
            rebuild();
        } else {
            MinecraftAccess.setScreen(MinecraftClient.getInstance(), null);
        }
    }

    private int visibleRows() {
        final PanelLayout layout = panelLayout();
        return Math.max(1, (layout.listRowsBottom() - layout.listRowsTop()) / ROW_HEIGHT);
    }

    @Override
    //#if MC>=12002
    //$$ public boolean mouseScrolled(
    //$$     final double mouseX,
    //$$     final double mouseY,
    //$$     final double horizontalAmount,
    //$$     final double amount
    //$$ ) {
    //#else
    public boolean mouseScrolled(final double mouseX, final double mouseY, final double amount) {
    //#endif
        final PanelLayout layout = panelLayout();
        if (amount != 0
            && mouseX >= layout.detailX() && mouseX <= layout.detailX() + layout.detailWidth()
            && mouseY >= layout.infoRowsTop() && mouseY <= layout.infoBottom()) {
            final int capacity = Math.max(1, detailRowsPerColumn(layout)
                * detailColumnCount(layout, detailCount, detailRowsPerColumn(layout)));
            final int next = clampDetailScroll(
                detailScrollOffset - (int) Math.signum(amount), detailCount, capacity
            );
            if (next != detailScrollOffset) {
                detailScrollOffset = next;
                return true;
            }
        }
        if (amount != 0 && profileCount > visibleRows()
            && mouseX >= layout.listX() && mouseX <= layout.listX() + layout.listWidth()
            && mouseY >= layout.listRowsTop() && mouseY <= layout.listRowsBottom()) {
            scrollOffset -= (int) Math.signum(amount);
            rebuild();
            return true;
        }
        //#if MC>=12002
        //$$ return super.mouseScrolled(mouseX, mouseY, horizontalAmount, amount);
        //#else
        return super.mouseScrolled(mouseX, mouseY, amount);
        //#endif
    }

    @Override
    //#if MC>=12109
    //$$ public boolean mouseClicked(final Click click, final boolean doubledClick) {
    //$$     final double mouseX = click.x();
    //$$     final double mouseY = click.y();
    //$$     final int button = click.button();
    //#else
    public boolean mouseClicked(final double mouseX, final double mouseY, final int button) {
    //#endif
        final PanelLayout layout = panelLayout();
        if (button == GLFW.GLFW_MOUSE_BUTTON_1 && isOverScrollbar(layout, mouseX, mouseY)) {
            draggingScrollbar = true;
            updateScrollFromMouse(layout, mouseY);
            return true;
        }
        //#if MC>=12109
        //$$ return super.mouseClicked(click, doubledClick);
        //#else
        return super.mouseClicked(mouseX, mouseY, button);
        //#endif
    }

    @Override
    //#if MC>=12109
    //$$ public boolean mouseDragged(final Click click, final double deltaX, final double deltaY) {
    //$$     final double mouseX = click.x();
    //$$     final double mouseY = click.y();
    //$$     final int button = click.button();
    //#else
    public boolean mouseDragged(
        final double mouseX,
        final double mouseY,
        final int button,
        final double deltaX,
        final double deltaY
    ) {
    //#endif
        if (draggingScrollbar && button == GLFW.GLFW_MOUSE_BUTTON_1) {
            updateScrollFromMouse(panelLayout(), mouseY);
            return true;
        }
        //#if MC>=12109
        //$$ return super.mouseDragged(click, deltaX, deltaY);
        //#else
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
        //#endif
    }

    @Override
    //#if MC>=12109
    //$$ public boolean mouseReleased(final Click click) {
    //$$     final int button = click.button();
    //#else
    public boolean mouseReleased(final double mouseX, final double mouseY, final int button) {
    //#endif
        if (button == GLFW.GLFW_MOUSE_BUTTON_1) {
            draggingScrollbar = false;
        }
        //#if MC>=12109
        //$$ return super.mouseReleased(click);
        //#else
        return super.mouseReleased(mouseX, mouseY, button);
        //#endif
    }

    private boolean isOverScrollbar(final PanelLayout layout, final double mouseX, final double mouseY) {
        return profileCount > visibleRows()
            && mouseX >= layout.scrollbarX() - 3
            && mouseX <= layout.scrollbarX() + LIST_SCROLLBAR_WIDTH + 3
            && mouseY >= layout.listRowsTop()
            && mouseY <= layout.listRowsBottom();
    }

    private void updateScrollFromMouse(final PanelLayout layout, final double mouseY) {
        final int maximum = Math.max(0, profileCount - visibleRows());
        if (maximum == 0) {
            scrollOffset = 0;
            return;
        }
        final double trackHeight = layout.listRowsBottom() - layout.listRowsTop();
        final double position = Math.max(0, Math.min(trackHeight, mouseY - layout.listRowsTop()));
        scrollOffset = (int) Math.round(position / trackHeight * maximum);
        rebuild();
    }

    @Override
    public void onClose() {
        clearPendingActions();
        MinecraftAccess.setScreen(MinecraftClient.getInstance(), parent);
    }

    private void clearPendingActions() {
        pendingDeleteProfileId = null;
        pendingClearProfileId = null;
    }

    @Override
    public void removed() {
        closeMapPreview();
        super.removed();
    }

    @Override
    //#if MC>=12109
    //$$ public boolean keyPressed(final KeyInput input) {
    //$$     final int keyCode = input.key();
    //#else
    public boolean keyPressed(final int keyCode, final int scanCode, final int modifiers) {
    //#endif
        // Give the focused child first chance to handle Enter/Space and navigation. The previous
        // ordering consumed these keys here, so keyboard users could not activate focused buttons.
        //#if MC>=12109
        //$$ if (super.keyPressed(input)) {
        //$$     return true;
        //$$ }
        //#else
        if (super.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        //#endif
        final int visible = visibleRows();
        switch (keyCode) {
            case GLFW.GLFW_KEY_UP:
                moveSelection(-1);
                return true;
            case GLFW.GLFW_KEY_DOWN:
                moveSelection(1);
                return true;
            case GLFW.GLFW_KEY_PAGE_UP:
                moveSelection(-visible);
                return true;
            case GLFW.GLFW_KEY_PAGE_DOWN:
                moveSelection(visible);
                return true;
            case GLFW.GLFW_KEY_ENTER:
            case GLFW.GLFW_KEY_KP_ENTER:
                if (selectedProfileId != null) {
                    select(selectedProfileId);
                    return true;
                }
                break;
            case GLFW.GLFW_KEY_DELETE:
                final ClientWorldProfile selected = selectedProfile();
                if (selected != null) {
                    delete(selected);
                    return true;
                }
                break;
            default:
                break;
        }
        //#if MC>=12109
        //$$ return false;
        //#else
        return false;
        //#endif
    }

    @Override
    protected void renderContents(final GuiDraw draw, final int mouseX, final int mouseY, final float tickDelta) {
        draw.renderBackground(this, mouseX, mouseY, tickDelta);
        hoveredDetailText = null;
        hoveredCandidateDetails = null;
        drawCentered(draw, getTitle().getString(), 14, 0xFFFFFFFF);
        final boolean registryAvailable = worlds.profileRegistryAvailable();
        final String promptKey = !registryAvailable
            ? "confluxmap.screen.client_world.registry_unavailable"
            : worlds.needsSelection()
                ? "confluxmap.screen.client_world.ambiguous"
                : "confluxmap.screen.client_world.prompt";
        drawWrappedCentered(draw, Texts.translatable(promptKey).getString(), 34,
            !registryAvailable || worlds.needsSelection() ? 0xFFFFCC55 : 0xFFBBBBBB);
        if (operationError != null) {
            drawWrappedCentered(
                draw,
                registryAvailable ? operationError : Texts.translatable(
                    "confluxmap.screen.client_world.retry_failed"
                ).getString(),
                47,
                0xFFFF7777
            );
        }
        final PanelLayout layout = panelLayout();
        drawPanel(draw, layout.listX(), layout.top(), layout.listWidth(), layout.bottom() - layout.top());
        drawPanel(draw, layout.detailX(), layout.top(), layout.detailWidth(), layout.infoBottom() - layout.top());
        draw.fill(layout.footerX(), layout.footerTop(), layout.footerX() + layout.footerWidth(),
            layout.footerTop() + 1, 0x88777777);
        draw.drawTextWithShadow(
            this.textRenderer,
            Texts.translatable("confluxmap.screen.client_world.list_heading").getString(),
            layout.listX() + 8, layout.top() + 8, 0xFFFFFFFF
        );
        draw.drawTextWithShadow(
            this.textRenderer,
            Texts.translatable("confluxmap.screen.client_world.management_heading").getString(),
            layout.footerX() + 8, layout.footerTop() + 5, 0xFFFFFFFF
        );
        drawListScrollbar(draw, layout);
        for (final CandidateRowDiagnostic diagnostic : candidateRowDiagnostics) {
            if (diagnostic.contains(mouseX, mouseY)) {
                hoveredCandidateDetails = diagnostic.lines();
                break;
            }
        }
        final ClientWorldProfile selected = selectedProfile();
        if (selected != null) {
            drawProfilePanel(draw, layout, selected, mouseX, mouseY);
        } else {
            drawCenteredInPanel(draw,
                Texts.translatable(
                    registryAvailable
                        ? "confluxmap.screen.client_world.empty"
                        : "confluxmap.screen.client_world.registry_recovery"
                ).getString(),
                layout.detailX(), layout.top(), layout.detailWidth(), layout.infoBottom() - layout.top(),
                0xFFBBBBBB);
            if (layout.previewVisible()) {
                drawPanel(draw, layout.previewX(), layout.previewTop(), layout.previewWidth(),
                    layout.previewBottom() - layout.previewTop());
                drawCenteredInPanel(draw,
                    Texts.translatable("confluxmap.screen.client_world.preview_none").getString(),
                    layout.previewX(), layout.previewTop(), layout.previewWidth(),
                    layout.previewBottom() - layout.previewTop(), 0xFF999999);
            }
        }
    }

    @Override
    protected void renderAfterWidgets(
        final GuiDraw draw,
        final int mouseX,
        final int mouseY,
        final float tickDelta
    ) {
        if (hoveredCandidateDetails != null && !hoveredCandidateDetails.isEmpty()) {
            drawCandidateTooltip(draw, hoveredCandidateDetails, mouseX, mouseY);
        } else if (hoveredDetailText != null) {
            draw.drawTooltip(this, this.textRenderer, Texts.literal(hoveredDetailText), mouseX, mouseY);
        }
    }

    private void drawProfilePanel(
        final GuiDraw draw,
        final PanelLayout layout,
        final ClientWorldProfile profile,
        final int mouseX,
        final int mouseY
    ) {
        final int x = layout.detailX() + 8;
        final int panelWidth = layout.detailWidth() - 16;
        final String stateKey = profile.id().equals(worlds.currentProfile().map(ClientWorldProfile::id).orElse(null))
            ? "confluxmap.screen.client_world.detail_current"
            : "confluxmap.screen.client_world.detail_inactive";
        draw.drawTextWithShadow(this.textRenderer, clipped(profile.displayName(), panelWidth),
            x, layout.top() + 7, 0xFFFFFFFF);
        drawDetailLine(draw, x, layout.top() + 21, panelWidth, Texts.translatable(
            "confluxmap.screen.client_world.detail_status", Texts.translatable(stateKey).getString()
        ).getString(), 0xFFBBBBBB, mouseX, mouseY);

        final List<String> details = profileDetails(profile);
        final int rowsPerColumn = detailRowsPerColumn(layout);
        final int columns = detailColumnCount(layout, details.size(), rowsPerColumn);
        final int columnGap = columns == 1 ? 0 : 10;
        final int columnWidth = Math.max(40, (panelWidth - columnGap * (columns - 1)) / columns);
        final int lineHeight = detailLineHeight(layout);
        final int capacity = rowsPerColumn * columns;
        final int visibleDetails = Math.min(capacity, Math.max(0, details.size() - detailScrollOffset));
        for (int visibleIndex = 0; visibleIndex < visibleDetails; visibleIndex++) {
            final int index = detailScrollOffset + visibleIndex;
            final int column = visibleIndex / rowsPerColumn;
            final int row = visibleIndex % rowsPerColumn;
            drawDetailLine(
                draw,
                x + column * (columnWidth + columnGap),
                layout.infoRowsTop() + row * lineHeight,
                columnWidth,
                details.get(index),
                0xFFBBBBBB,
                mouseX,
                mouseY
            );
        }
        if (details.size() > capacity) {
            final String position = (detailScrollOffset + 1) + "-"
                + Math.min(details.size(), detailScrollOffset + capacity) + "/" + details.size();
            draw.drawTextWithShadow(this.textRenderer, position,
                layout.detailX() + layout.detailWidth() - 8 - this.textRenderer.getWidth(position),
                layout.top() + 7, 0xFF999999);
        }
        if (layout.previewVisible()) {
            drawProfilePreview(draw, layout, profile);
        }
    }

    private void drawProfilePreview(
        final GuiDraw draw,
        final PanelLayout layout,
        final ClientWorldProfile profile
    ) {
        final int x = layout.previewX();
        final int y = layout.previewTop();
        final int previewWidth = layout.previewWidth();
        final int previewHeight = layout.previewBottom() - y;
        final int mapX = x + 1;
        final int mapY = y + PREVIEW_HEADER_HEIGHT;
        final int mapWidth = previewWidth - 2;
        final int mapHeight = Math.max(1, previewHeight - PREVIEW_HEADER_HEIGHT - PREVIEW_META_HEIGHT - 2);
        final ClientWorldVisit visit = latestVisit(profile);
        drawPanel(draw, x, y, previewWidth, previewHeight);
        final boolean rendered = mapPreview != null && mapPreview.render(draw, mapX, mapY, mapWidth, mapHeight);
        draw.drawTextWithShadow(this.textRenderer,
            Texts.translatable("confluxmap.screen.client_world.preview_heading").getString(),
            x + 7, y + 6, 0xFFDDDDDD);
        if (visit == null) {
            drawCenteredInPanel(draw,
                Texts.translatable("confluxmap.screen.client_world.preview_none").getString(),
                mapX, mapY, mapWidth, mapHeight, 0xFF999999);
            return;
        }
        final ClientWorldMapPreview.State previewState = mapPreview == null
            ? ClientWorldMapPreview.State.FAILED
            : mapPreview.state();
        if (previewState == ClientWorldMapPreview.State.EMPTY) {
            drawCenteredInPanel(draw,
                Texts.translatable("confluxmap.screen.client_world.preview_empty").getString(),
                mapX, mapY, mapWidth, mapHeight, 0xFF999999);
        } else if (previewState == ClientWorldMapPreview.State.FAILED) {
            drawCenteredInPanel(draw,
                Texts.translatable("confluxmap.screen.client_world.preview_failed").getString(),
                mapX, mapY, mapWidth, mapHeight, 0xFFFF7777);
        } else if (!rendered) {
            drawCenteredInPanel(draw,
                Texts.translatable("confluxmap.screen.client_world.preview_loading").getString(),
                mapX, mapY, mapWidth, mapHeight, 0xFFBBBBBB);
        }
        if (previewState == ClientWorldMapPreview.State.READY) {
            final String scale = Texts.translatable(
                "confluxmap.screen.client_world.preview_scale",
                mapPreview.exploredChunks(), formattedScale(mapPreview.blocksPerPixel())
            ).getString();
            draw.drawTextWithShadow(this.textRenderer, clipped(scale, Math.max(30, previewWidth / 2)),
                Math.max(x + previewWidth / 2, x + previewWidth - 7 - this.textRenderer.getWidth(scale)),
                y + 6, 0xFF999999);
            final ClientWorldMapPreview.Marker marker = mapPreview.marker();
            if (marker != null) {
                final int markerX = mapX + (int) Math.round(marker.x());
                final int markerY = mapY + (int) Math.round(marker.y());
                draw.fill(markerX - 1, markerY - 6, markerX + 2, markerY + 7, 0xFFE6B84A);
                draw.fill(markerX - 6, markerY - 1, markerX + 7, markerY + 2, 0xFFE6B84A);
            }
        }
        draw.drawTextWithShadow(this.textRenderer, "N", mapX + mapWidth / 2 - 3, mapY + 4, 0xFFFFFFFF);
        final int metadataY = layout.previewBottom() - PREVIEW_META_HEIGHT + 3;
        draw.drawTextWithShadow(this.textRenderer, Texts.translatable(
            "confluxmap.screen.client_world.preview_lock"
        ).getString(), x + 7, metadataY, 0xFF999999);
        final String location = previewLocation(visit);
        final int locationWidth = Math.max(20, previewWidth - 92);
        final String clippedLocation = clipped(location, locationWidth);
        draw.drawTextWithShadow(this.textRenderer, clippedLocation,
            x + previewWidth - 7 - this.textRenderer.getWidth(clippedLocation),
            metadataY, 0xFFE6E6E6);
    }

    private static String formattedScale(final double blocksPerPixel) {
        if (!Double.isFinite(blocksPerPixel)) {
            return "-";
        }
        return blocksPerPixel < 1.0D
            ? String.format(java.util.Locale.ROOT, "%.2f", blocksPerPixel)
            : String.format(java.util.Locale.ROOT, "%.1f", blocksPerPixel);
    }

    private ClientWorldVisit latestVisit(final ClientWorldProfile profile) {
        return profile.visits().stream()
            .max(Comparator.comparingLong(ClientWorldVisit::lastVisitedAtEpochMs))
            .orElse(null);
    }

    private void drawListScrollbar(final GuiDraw draw, final PanelLayout layout) {
        if (profileCount <= visibleRows()) {
            return;
        }
        final int top = layout.listRowsTop();
        final int trackHeight = layout.listRowsBottom() - top;
        final int thumbHeight = Math.max(18, trackHeight * visibleRows() / profileCount);
        final int maximum = Math.max(1, profileCount - visibleRows());
        final int thumbTop = top + (trackHeight - thumbHeight) * scrollOffset / maximum;
        draw.fill(layout.scrollbarX(), top, layout.scrollbarX() + LIST_SCROLLBAR_WIDTH, layout.listRowsBottom(),
            0x55000000);
        draw.fill(layout.scrollbarX(), thumbTop, layout.scrollbarX() + LIST_SCROLLBAR_WIDTH,
            thumbTop + thumbHeight, 0xFFAAAAAA);
    }

    private void drawPanel(final GuiDraw draw, final int x, final int y, final int panelWidth, final int panelHeight) {
        draw.fill(x, y, x + panelWidth, y + panelHeight, 0x45000000);
        draw.fill(x, y, x + panelWidth, y + 1, 0xFF777777);
        draw.fill(x, y + panelHeight - 1, x + panelWidth, y + panelHeight, 0xFF333333);
        draw.fill(x, y, x + 1, y + panelHeight, 0xFF777777);
        draw.fill(x + panelWidth - 1, y, x + panelWidth, y + panelHeight, 0xFF333333);
    }

    private void drawCenteredInPanel(
        final GuiDraw draw,
        final String text,
        final int x,
        final int y,
        final int panelWidth,
        final int panelHeight,
        final int color
    ) {
        final String clipped = clipped(text, Math.max(1, panelWidth));
        draw.drawTextWithShadow(this.textRenderer, clipped,
            x + panelWidth / 2f - this.textRenderer.getWidth(clipped) / 2f,
            y + panelHeight / 2f - 4, color);
    }

    private List<String> profileDetails(final ClientWorldProfile profile) {
        final List<String> details = new ArrayList<>();
        final ClientWorldVisit visit = latestVisit(profile);
        final List<String> matches = matchDetails(profile);
        details.addAll(matches);
        details.add(Texts.translatable("confluxmap.screen.client_world.section.profile").getString());
        details.add(Texts.translatable(
            "confluxmap.screen.client_world.detail_recognition", profile.bindingCount()
        ).getString());
        final List<String> commands = profile.switchCommands();
        details.add((commands.isEmpty()
            ? Texts.translatable("confluxmap.screen.client_world.detail_commands_none")
            : Texts.translatable("confluxmap.screen.client_world.detail_commands", commandSummary(commands))
        ).getString());
        if (visit == null) {
            details.add(Texts.translatable("confluxmap.screen.client_world.detail_visit_none").getString());
        } else {
            details.add(Texts.translatable(
                "confluxmap.screen.client_world.detail_dimension", visit.dimensionId()
            ).getString());
            details.add(Texts.translatable(
                "confluxmap.screen.client_world.detail_position", position(visit)
            ).getString());
            details.add(Texts.translatable(
                "confluxmap.screen.client_world.detail_last_seen",
                VISIT_TIME.format(Instant.ofEpochMilli(visit.lastVisitedAtEpochMs()))
            ).getString());
            details.add(Texts.translatable(
                visit.terrainFingerprint() == null
                    ? "confluxmap.screen.client_world.detail_terrain_none"
                    : "confluxmap.screen.client_world.detail_terrain_present"
            ).getString());
            details.add(Texts.translatable(
                "confluxmap.screen.client_world.detail_game_mode",
                visit.gameMode() == null ? "?" : visit.gameMode()
            ).getString());
            details.add(Texts.translatable(
                "confluxmap.screen.client_world.detail_visit_context", contextSummary(visit)
            ).getString());
        }
        details.add(Texts.translatable(
            "confluxmap.screen.client_world.detail_seed", seedSummary(profile)
        ).getString());
        details.add(Texts.translatable(
            "confluxmap.screen.client_world.detail_id", profile.id()
        ).getString());
        return details;
    }

    private void drawDetailLine(
        final GuiDraw draw,
        final int x,
        final int y,
        final int panelWidth,
        final String text,
        final int color,
        final int mouseX,
        final int mouseY
    ) {
        final String clipped = clipped(text, panelWidth);
        draw.drawTextWithShadow(this.textRenderer, clipped, x, y, color);
        if (!text.equals(clipped) && mouseX >= x && mouseX < x + panelWidth
            && mouseY >= y && mouseY < y + DETAIL_LINE_HEIGHT) {
            hoveredDetailText = text;
        }
    }

    private ClientWorldProfile selectedProfile() {
        return selectedProfileId == null ? null : worlds.profile(selectedProfileId).orElse(null);
    }

    private List<String> matchDetails(final ClientWorldProfile profile) {
        final List<String> details = new ArrayList<>();
        final boolean current = profile.id().equals(
            worlds.currentProfile().map(ClientWorldProfile::id).orElse(null)
        );
        if (current) {
            details.add(Texts.translatable(
                "confluxmap.screen.client_world.detail_confirmation_source",
                confirmationSourceLabel(worlds.confirmationSource())
            ).getString());
        }
        final ClientWorldResolution.Candidate candidate = candidate(profile);
        if (candidate == null) {
            if (!current) {
                details.add(Texts.translatable("confluxmap.screen.client_world.detail_confidence_none").getString());
            }
            return details;
        }
        details.add(Texts.translatable("confluxmap.screen.client_world.section.decision").getString());
        final String score = candidate.scored()
            ? candidate.confidencePercent() + "%"
            : Texts.translatable("confluxmap.screen.client_world.summary.unscored").getString();
        details.add(Texts.translatable(
            "confluxmap.screen.client_world.detail_decision_summary",
            score, candidate.requiredConfidencePercent(), candidate.queue(),
            candidate.runnerUpConfidencePercent(), candidate.actualMarginPercent(),
            candidate.requiredMarginPercent()
        ).getString());
        details.add(Texts.translatable(
            "confluxmap.screen.client_world.detail_primary_reason",
            candidatePrimaryReason(candidate)
        ).getString());
        details.add(Texts.translatable(
            "confluxmap.screen.client_world.detail_seed_filter",
            candidate.seedCompatible(), candidate.sameSeedCandidates()
        ).getString());
        details.add(Texts.translatable("confluxmap.screen.client_world.section.factors").getString());
        details.add(Texts.translatable("confluxmap.screen.client_world.detail_factor_header").getString());
        for (final ClientWorldResolution.Factor factor : candidate.factors()) {
            details.add(factorTableRow(factor));
            if (!factor.metrics().isEmpty()) {
                details.add(Texts.translatable(
                    "confluxmap.screen.client_world.detail_metrics", metricSummary(factor.metrics())
                ).getString());
            }
        }
        details.add(Texts.translatable("confluxmap.screen.client_world.section.diagnostics").getString());
        for (final String blocker : candidate.blockers()) {
            details.add(Texts.translatable(
                "confluxmap.screen.client_world.detail_blocker", diagnosticCodeLabel("blocker", blocker)
            ).getString());
        }
        for (final String reason : candidate.reasons()) {
            details.add(Texts.translatable(
                "confluxmap.screen.client_world.detail_evidence", reasonDescription(reason)
            ).getString());
        }
        return details;
    }

    private List<String> candidateHoverDetails(final ClientWorldProfile profile) {
        final List<String> lines = new ArrayList<>();
        final ClientWorldResolution.Candidate candidate = candidate(profile);
        if (candidate == null) {
            lines.add(Texts.translatable("confluxmap.screen.client_world.summary.no_diagnostic").getString());
            return lines;
        }
        final String score = candidate.scored()
            ? candidate.confidencePercent() + "%"
            : Texts.translatable("confluxmap.screen.client_world.summary.unscored").getString();
        lines.add(Texts.translatable("confluxmap.screen.client_world.section.decision").getString());
        lines.add(Texts.translatable(
            "confluxmap.screen.client_world.summary.score_threshold",
            score, candidate.requiredConfidencePercent(), candidate.queue()
        ).getString());
        lines.add(Texts.translatable(
            "confluxmap.screen.client_world.summary.comparison",
            candidate.runnerUpConfidencePercent(), candidate.actualMarginPercent(), candidate.requiredMarginPercent()
        ).getString());
        lines.add(Texts.translatable(
            "confluxmap.screen.client_world.summary.primary_reason", candidatePrimaryReason(candidate)
        ).getString());
        lines.add(Texts.translatable("confluxmap.screen.client_world.section.factors").getString());
        lines.add(Texts.translatable("confluxmap.screen.client_world.detail_factor_header").getString());
        for (final ClientWorldResolution.Factor factor : candidate.factors()) {
            lines.add(factorTableRow(factor));
        }
        return lines;
    }

    private static String candidatePrimaryReason(final ClientWorldResolution.Candidate candidate) {
        if (candidate.blockers().isEmpty()) {
            return outcomeLabel(candidate.outcome());
        }
        if ("margin_not_strictly_greater".equals(candidate.blockers().get(0))) {
            return Texts.translatable(
                "confluxmap.screen.client_world.summary.margin",
                candidate.actualMarginPercent(), candidate.requiredMarginPercent()
            ).getString();
        }
        return diagnosticCodeLabel("blocker", candidate.blockers().get(0));
    }

    private static String factorTableRow(final ClientWorldResolution.Factor factor) {
        final boolean available = factor.availability() == ClientWorldResolution.FactorAvailability.AVAILABLE;
        final String status = Texts.translatable(
            factor.veto() ? "confluxmap.screen.client_world.factor_status.veto"
                : available ? "confluxmap.screen.client_world.factor_status.available"
                    : "confluxmap.screen.client_world.factor_status.unavailable"
        ).getString();
        return Texts.translatable(
            "confluxmap.screen.client_world.detail_factor_row",
            factorLabel(factor.key()), available ? percent(factor.rawScore()) + "%" : "-",
            percent(factor.configuredWeight()), percent(factor.effectiveWeight()),
            percent(factor.contribution()), status
        ).getString();
    }

    private void drawCandidateTooltip(
        final GuiDraw draw,
        final List<String> sourceLines,
        final int mouseX,
        final int mouseY
    ) {
        final int maxTextWidth = Math.max(180, Math.min(480, this.width - 28));
        final List<OrderedText> wrapped = new ArrayList<>();
        for (final String line : sourceLines) {
            wrapped.addAll(this.textRenderer.wrapLines(StringVisitable.plain(line), maxTextWidth));
        }
        if (wrapped.isEmpty()) {
            return;
        }
        int textWidth = 0;
        for (final OrderedText line : wrapped) {
            textWidth = Math.max(textWidth, this.textRenderer.getWidth(line));
        }
        final int tooltipWidth = textWidth + 8;
        final int tooltipHeight = wrapped.size() * 11 + 6;
        int x = mouseX + 12;
        int y = mouseY + 10;
        if (x + tooltipWidth > this.width - 4) {
            x = mouseX - tooltipWidth - 12;
        }
        if (y + tooltipHeight > this.height - 4) {
            y = this.height - tooltipHeight - 4;
        }
        x = Math.max(4, x);
        y = Math.max(4, y);
        draw.fill(x - 1, y - 1, x + tooltipWidth + 1, y + tooltipHeight + 1, 0xF0100010);
        draw.fill(x, y, x + tooltipWidth, y + tooltipHeight, 0xF0202020);
        int lineY = y + 4;
        for (final OrderedText line : wrapped) {
            draw.drawTextWithShadow(this.textRenderer, line, x + 4, lineY, 0xFFFFFFFF);
            lineY += 11;
        }
    }

    private static int percent(final double value) {
        return (int) Math.round(value * 100.0D);
    }

    private static String metricSummary(final java.util.Map<String, String> metrics) {
        return metrics.entrySet().stream()
            .sorted(java.util.Map.Entry.comparingByKey())
            .map(entry -> diagnosticCodeLabel("metric", entry.getKey()) + "=" + entry.getValue())
            .collect(java.util.stream.Collectors.joining(", "));
    }

    private static String factorLabel(final String factor) {
        return diagnosticCodeLabel("factor", factor);
    }

    private static String outcomeLabel(final ClientWorldResolution.CandidateOutcome outcome) {
        return diagnosticCodeLabel("outcome", outcome.name().toLowerCase(java.util.Locale.ROOT));
    }

    private static String confirmationSourceLabel(final ClientWorldResolution.ConfirmationSource source) {
        return diagnosticCodeLabel("confirmation", source.name().toLowerCase(java.util.Locale.ROOT));
    }

    private static String diagnosticCodeLabel(final String group, final String code) {
        return Texts.translatable(
            "confluxmap.screen.client_world." + group + "." + code, code
        ).getString();
    }

    static int clampDetailScroll(final int offset, final int detailCount, final int capacity) {
        return Math.max(0, Math.min(offset, Math.max(0, detailCount - Math.max(1, capacity))));
    }

    private ClientWorldResolution.Candidate candidate(final ClientWorldProfile profile) {
        return worlds.candidates().stream()
            .filter(entry -> entry.profileId().equals(profile.id()))
            .findFirst()
            .orElse(null);
    }

    private String reasonDescription(final String reason) {
        final ReasonLabel label = reasonLabel(reason);
        return Texts.translatable(label.translationKey(), label.arguments()).getString();
    }

    static ReasonLabel reasonLabel(final String reason) {
        if (reason.startsWith("visit_context_") && !"visit_context_conflict".equals(reason)) {
            return countedReasonLabel(
                reason, "visit_context_", "confluxmap.screen.client_world.reason.visit_context"
            );
        }
        if (reason.startsWith("terrain_") && !"terrain_unavailable".equals(reason)) {
            return countedReasonLabel(reason, "terrain_", "confluxmap.screen.client_world.reason.terrain");
        }
        if (reason.startsWith("identity_signals_")) {
            final String count = reason.substring("identity_signals_".length());
            if (count.chars().allMatch(Character::isDigit)) {
                return new ReasonLabel("confluxmap.screen.client_world.reason.identity_signals", count);
            }
        }
        return switch (reason) {
            case "seed_match",
                "seed_conflict",
                "game_mode_match",
                "game_mode_mismatch",
                "trajectory_continuity",
                "trajectory_stale",
                "last_stable_profile",
                "not_last_stable_profile",
                "last_stable_conflict",
                "last_stable_suppressed_world_boundary",
                "last_stable_suppressed_stronger_current_trajectory",
                "candidate_dimension_checkpoint",
                "last_dimension_mismatch",
                "departed_profile_boundary",
                "corridor_near",
                "corridor_outside_radius",
                "position_near_without_trajectory",
                "position_far_without_trajectory",
                "terrain_unavailable",
                "terrain_mismatch",
                "terrain_weak_match",
                "terrain_conflict",
                "visit_context_conflict",
                "signal_conflict",
                "legacy_profile",
                "dimension_unavailable",
                "observation_incomplete",
                "none" -> new ReasonLabel("confluxmap.screen.client_world.reason." + reason);
            default -> new ReasonLabel("confluxmap.screen.client_world.reason.unknown", reason);
        };
    }

    private static ReasonLabel countedReasonLabel(
        final String reason,
        final String prefix,
        final String translationKey
    ) {
        final String counts = reason.substring(prefix.length());
        final int separator = counts.indexOf("_of_");
        if (separator <= 0 || separator + 4 >= counts.length()) {
            return new ReasonLabel("confluxmap.screen.client_world.reason.unknown", reason);
        }
        final String matches = counts.substring(0, separator);
        final String available = counts.substring(separator + 4);
        if (!matches.chars().allMatch(Character::isDigit) || !available.chars().allMatch(Character::isDigit)) {
            return new ReasonLabel("confluxmap.screen.client_world.reason.unknown", reason);
        }
        return new ReasonLabel(translationKey, matches, available);
    }

    private static String commandSummary(final List<String> commands) {
        return commands.size() == 1 ? commands.get(0) : commands.get(0) + " (+" + (commands.size() - 1) + ")";
    }

    private String clipped(final String text, final int maxWidth) {
        if (this.textRenderer.getWidth(text) <= maxWidth) {
            return text;
        }
        final String suffix = "...";
        return this.textRenderer.trimToWidth(text, Math.max(0, maxWidth - this.textRenderer.getWidth(suffix))) + suffix;
    }

    private static String position(final ClientWorldVisit visit) {
        return visit.lastPosition() == null
            ? "?"
            : "X: " + visit.lastPosition().x()
                + ", Y: " + visit.lastPosition().y()
                + ", Z: " + visit.lastPosition().z();
    }

    static String previewLocation(final ClientWorldVisit visit) {
        return visit.lastPosition() == null
            ? visit.dimensionId()
            : Texts.translatable(
                "confluxmap.screen.client_world.preview_position",
                position(visit), visit.dimensionId()
            ).getString();
    }

    private static String seedSummary(final ClientWorldProfile profile) {
        final List<String> seeds = profile.seedSignatures();
        return seeds.isEmpty() ? "?" : String.join(", ", seeds);
    }

    private String contextSummary(final ClientWorldVisit visit) {
        if (visit.contextSignals().isEmpty()) {
            return Texts.translatable("confluxmap.screen.client_world.detail_context_none").getString();
        }
        return visit.contextSignals().keySet().stream()
            .map(this::contextLabel)
            .reduce((first, second) -> first + ", " + second)
            .orElse(Texts.translatable("confluxmap.screen.client_world.detail_context_none").getString());
    }

    private String contextLabel(final String signal) {
        return switch (signal) {
            case "dimension_type" -> Texts.translatable(
                "confluxmap.screen.client_world.context.dimension_type"
            ).getString();
            case "world_shape" -> Texts.translatable(
                "confluxmap.screen.client_world.context.world_shape"
            ).getString();
            case "spawn" -> Texts.translatable(
                "confluxmap.screen.client_world.context.spawn"
            ).getString();
            case "world_border" -> Texts.translatable(
                "confluxmap.screen.client_world.context.world_border"
            ).getString();
            case "difficulty" -> Texts.translatable(
                "confluxmap.screen.client_world.context.difficulty"
            ).getString();
            default -> Texts.translatable("confluxmap.screen.client_world.context.other").getString();
        };
    }

    private void moveSelection(final int delta) {
        final List<ClientWorldProfile> profiles = worlds.profiles();
        if (profiles.isEmpty()) {
            return;
        }
        int index = 0;
        for (int candidate = 0; candidate < profiles.size(); candidate++) {
            if (profiles.get(candidate).id().equals(selectedProfileId)) {
                index = candidate;
                break;
            }
        }
        index = Math.max(0, Math.min(profiles.size() - 1, index + delta));
        selectedProfileId = profiles.get(index).id();
        final int visible = visibleRows();
        if (index < scrollOffset) {
            scrollOffset = index;
        } else if (index >= scrollOffset + visible) {
            scrollOffset = index - visible + 1;
        }
        rebuild();
    }

    private PanelLayout panelLayout() {
        return panelLayout(width, height);
    }

    static PanelLayout panelLayout(final int width, final int height) {
        final int contentWidth = Math.max(1, Math.min(CONTENT_MAX_WIDTH, width - 16));
        final int contentX = Math.max(0, (width - contentWidth) / 2);
        final int horizontalGap = Math.min(PANEL_GAP, Math.max(0, contentWidth - 2));
        final int listWidth = Math.min(
            Math.max(1, contentWidth - horizontalGap - 1),
            Math.max(LIST_MIN_WIDTH, Math.min(LIST_MAX_WIDTH, contentWidth / 3))
        );
        final int footerTop = Math.max(MAIN_TOP + 108, height - FOOTER_HEIGHT);
        final int bottom = footerTop - FOOTER_GAP;
        final int listRowsTop = MAIN_TOP + 29;
        final int listRowsBottom = bottom - 8;
        final int mainHeight = bottom - MAIN_TOP;
        final boolean previewVisible = height > NARROW_VIEWPORT_HEIGHT;
        final int infoBottom;
        final int functionActionsTop;
        final int previewTop;
        if (previewVisible) {
            final int reservedHeight = PANEL_GAP + FUNCTION_ACTION_HEIGHT + PANEL_GAP
                + MIN_PREVIEW_PANEL_HEIGHT;
            final int maximumInfoHeight = Math.max(40, mainHeight - reservedHeight);
            final int targetInfoHeight = Math.max(120, mainHeight * 48 / 100);
            final int infoHeight = Math.max(40, Math.min(
                REGULAR_INFO_MAX_HEIGHT, Math.min(targetInfoHeight, maximumInfoHeight)
            ));
            infoBottom = MAIN_TOP + infoHeight;
            functionActionsTop = infoBottom + PANEL_GAP;
            previewTop = functionActionsTop + FUNCTION_ACTION_HEIGHT + PANEL_GAP;
        } else {
            functionActionsTop = bottom - FUNCTION_ACTION_HEIGHT;
            infoBottom = functionActionsTop - PANEL_GAP;
            previewTop = bottom;
        }
        return new PanelLayout(
            contentX,
            listWidth,
            contentX + listWidth + horizontalGap,
            contentWidth - listWidth - horizontalGap,
            MAIN_TOP,
            bottom,
            listRowsTop,
            listRowsBottom,
            footerTop,
            height - 8,
            infoBottom,
            MAIN_TOP + 37,
            functionActionsTop,
            previewTop,
            bottom,
            previewVisible
        );
    }

    static int detailRowsPerColumn(final PanelLayout layout) {
        return Math.max(1,
            (layout.infoBottom() - 8 - layout.infoRowsTop()) / detailLineHeight(layout));
    }

    static int detailColumnCount(
        final PanelLayout layout,
        final int detailCount,
        final int rowsPerColumn
    ) {
        return 1;
    }

    private static int detailLineHeight(final PanelLayout layout) {
        return layout.previewVisible() ? DETAIL_LINE_HEIGHT : NARROW_DETAIL_LINE_HEIGHT;
    }

    private void drawCentered(final GuiDraw draw, final String text, final float y, final int color) {
        draw.drawTextWithShadow(
            this.textRenderer, text, width / 2f - this.textRenderer.getWidth(text) / 2f, y, color
        );
    }

    record ReasonLabel(String translationKey, Object... arguments) {
    }

    record CandidateRowDiagnostic(int x, int y, int width, int height, List<String> lines) {
        boolean contains(final int mouseX, final int mouseY) {
            return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        }
    }

    record PanelLayout(
        int listX,
        int listWidth,
        int detailX,
        int detailWidth,
        int top,
        int bottom,
        int listRowsTop,
        int listRowsBottom,
        int footerTop,
        int footerBottom,
        int infoBottom,
        int infoRowsTop,
        int functionActionsTop,
        int previewTop,
        int previewBottom,
        boolean previewVisible
    ) {
        int listButtonWidth() {
            return Math.max(1, listWidth - 12);
        }

        int scrollbarX() {
            return listX + listWidth - LIST_SCROLLBAR_WIDTH - 4;
        }

        int previewX() {
            return detailX;
        }

        int previewWidth() {
            return detailWidth;
        }

        int footerX() {
            return listX;
        }

        int footerWidth() {
            return detailX + detailWidth - listX;
        }

        int footerActionsTop() {
            return footerTop + 18;
        }
    }

    private void drawWrappedCentered(final GuiDraw draw, final String text, final int y, final int color) {
        int lineY = y;
        for (final OrderedText line : this.textRenderer.wrapLines(
            StringVisitable.plain(text), Math.max(40, width - 32)
        )) {
            draw.drawTextWithShadow(
                this.textRenderer, line, width / 2f - this.textRenderer.getWidth(line) / 2f, lineY, color
            );
            lineY += this.textRenderer.fontHeight + 1;
        }
    }
}
