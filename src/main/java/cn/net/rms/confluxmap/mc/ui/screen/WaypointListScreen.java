package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.bridge.GameBridge;
import cn.net.rms.confluxmap.bridge.PlayerView;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointAvailability;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointClientState;
import cn.net.rms.confluxmap.core.shared.SharedWaypoint;
import cn.net.rms.confluxmap.core.waypoint.DimensionScale;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import cn.net.rms.confluxmap.core.waypoint.WaypointRenderEntry;
import cn.net.rms.confluxmap.core.waypoint.WaypointService;
import cn.net.rms.confluxmap.core.waypoint.WaypointSet;
import cn.net.rms.confluxmap.core.waypoint.WaypointStore;
import cn.net.rms.confluxmap.mc.net.shared.SharedWaypointClient;
import cn.net.rms.confluxmap.mc.render.RenderUtil;
import cn.net.rms.confluxmap.mc.ui.WaypointMarkerRenderer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.math.MathHelper;

/** Management UI for local waypoint sets and server-enabled shared waypoints. */
public final class WaypointListScreen extends Screen {
    public enum Tab { LOCAL, PUBLIC, LOCKED }

    private static final int ROW_HEIGHT = 28;
    private static final int SHARED_LIST_TOP = 56;
    private static final int LOCAL_IDLE_LIST_TOP = 80;
    private static final int LOCAL_LIST_TOP = 104;
    private static final int BOTTOM_MARGIN = 34;
    private static final int MIN_SIDE_MARGIN = 16;
    private static final int MAX_CONTENT_WIDTH = 880;
    private static final int ROW_PADDING = 6;
    private static final int MARKER_SIZE = 14;
    private static final int CHECK_WIDTH = 20;
    private static final int DELETE_WIDTH = 52;
    private static final int ACTION_WIDTH = 56;
    private static final int DIM_WIDTH = 66;
    private static final int DIST_WIDTH = 58;
    private static final int GAP = 4;
    private static final int TOOLBAR_Y = 52;
    private static final int TOOLBAR_HEIGHT = 20;
    private static final int NARROW_TOOLBAR_WIDTH = 248;
    private static final int NARROW_ROW_WIDTH = 254;
    private static final int NARROW_ACTION_WIDTH = 36;
    private static final int NARROW_DELETE_WIDTH = 40;
    private static final int DROPDOWN_MAX_WIDTH = 200;
    private static final int DROPDOWN_ROW_HEIGHT = 20;
    private static final int DROPDOWN_MAX_VISIBLE_ROWS = 6;
    private static final int DROPDOWN_SCROLLBAR_WIDTH = 6;
    private static final int DROPDOWN_ACTION_HEIGHT = 22;

    private enum SetDropdown { FILTER, MOVE_TARGET }

    private record DropdownGeometry(
        int x,
        int triggerY,
        int width,
        int popupY,
        int visibleRows,
        int actionHeight
    ) {
        int optionHeight() {
            return visibleRows * DROPDOWN_ROW_HEIGHT;
        }

        int popupHeight() {
            return optionHeight() + actionHeight;
        }

        boolean containsTrigger(final double mouseX, final double mouseY) {
            return mouseX >= x && mouseX < x + width
                && mouseY >= triggerY && mouseY < triggerY + TOOLBAR_HEIGHT;
        }

        boolean containsPopup(final double mouseX, final double mouseY) {
            return mouseX >= x && mouseX < x + width
                && mouseY >= popupY && mouseY < popupY + popupHeight();
        }
    }

    private record RowInfo(
        Waypoint local,
        SharedWaypoint shared,
        int y,
        double distance,
        String dimensionText
    ) {
        UUID id() {
            return local != null ? local.id : shared.id();
        }

        String name() {
            return local != null ? local.name : shared.name();
        }

        String secondaryText() {
            if (local != null) {
                return setDisplayName(local.group);
            }
            return new TranslatableText(
                "confluxmap.screen.waypoints.shared_by", shared.publisherName()
            ).getString();
        }

        WaypointRenderEntry renderEntry() {
            if (local != null) {
                return new WaypointRenderEntry(
                    local.id, local.name, local.dimensionId, local.x, local.y, local.z,
                    local.colorArgb, local.type, WaypointRenderEntry.Source.LOCAL, false
                );
            }
            return new WaypointRenderEntry(
                shared.id(), shared.name(), shared.dimensionId(), shared.x(), shared.y(), shared.z(),
                shared.colorArgb(), shared.type(), WaypointRenderEntry.Source.SHARED, shared.locked()
            );
        }
    }

    private final GameBridge gameBridge;
    private final WaypointService waypointService;
    private final SharedWaypointClient sharedWaypoints;
    private final Screen parent;
    private final Set<UUID> selectedWaypointIds = new LinkedHashSet<>();
    private Tab tab;

    private int scrollOffset;
    private UUID pendingDeleteId;
    private String selectedSetFilter;
    private String moveTargetSet = WaypointSet.DEFAULT_NAME;
    private SetDropdown openSetDropdown;
    private int dropdownScrollOffset;
    private int dropdownKeyboardIndex;
    private boolean draggingDropdownScrollbar;
    private int filterDropdownX;
    private int filterDropdownY;
    private int filterDropdownWidth;
    private int moveDropdownX;
    private int moveDropdownY;
    private int moveDropdownWidth;
    private List<RowInfo> rows = new ArrayList<>();
    private List<UUID> filteredLocalIds = List.of();
    private long lastSharedRevision = Long.MIN_VALUE;
    private SharedWaypointClientState.State lastSharedState;
    private boolean lastSharedSynchronized;
    private boolean lastOperator;
    private long lastLocalRevision = Long.MIN_VALUE;
    private WaypointStore lastLocalStore;
    private DimensionId lastDimension;

    public WaypointListScreen() {
        this(null, Tab.LOCAL);
    }

    public WaypointListScreen(final Screen parent, final Tab initialTab) {
        super(new TranslatableText("confluxmap.screen.waypoints.title"));
        final ConfluxMapClient app = ConfluxMapClient.get();
        this.gameBridge = app.gameBridge();
        this.waypointService = app.waypointService();
        this.sharedWaypoints = app.sharedWaypoints();
        this.parent = parent;
        this.tab = initialTab == null ? Tab.LOCAL : initialTab;
        if (this.tab != Tab.LOCAL && !sharedWaypoints.availability().enabled()) {
            this.tab = Tab.LOCAL;
        }
    }

    @Override
    public void onClose() {
        MinecraftClient.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        rebuild();
    }

    @Override
    public void tick() {
        final SharedWaypointAvailability availability = sharedWaypoints.availability();
        if (!availability.enabled() && tab != Tab.LOCAL) {
            selectTab(Tab.LOCAL);
            return;
        }
        final WaypointStore store = waypointService.current();
        if (store != lastLocalStore) {
            resetLocalUiState();
            rebuild();
            return;
        }
        final DimensionId currentDimension = gameBridge.session().dimension();
        final long localRevision = store == null ? 0L : store.revision();
        if (lastSharedRevision != sharedWaypoints.revision()
            || lastSharedState != sharedWaypoints.state()
            || lastSharedSynchronized != sharedWaypoints.isSynchronized()
            || lastOperator != sharedWaypoints.isOperator()
            || lastLocalRevision != localRevision
            || !currentDimension.equals(lastDimension)) {
            rebuild();
        }
    }

    private void rebuild() {
        clearChildren();
        rows.clear();
        filterDropdownWidth = 0;
        moveDropdownWidth = 0;
        final WaypointStore store = waypointService.current();
        normalizeSetState(store);
        normalizeDropdownState(store);
        final Optional<PlayerView> playerView = gameBridge.player();
        final DimensionId currentDimension = gameBridge.session().dimension();
        final double px = playerView.map(PlayerView::x).orElse(0.0);
        final double py = playerView.map(PlayerView::y).orElse(0.0);
        final double pz = playerView.map(PlayerView::z).orElse(0.0);

        final List<RowInfo> sorted = buildRows(currentDimension, px, py, pz);
        sorted.sort((a, b) -> Double.compare(a.distance(), b.distance()));
        filteredLocalIds = tab == Tab.LOCAL
            ? sorted.stream().map(RowInfo::id).toList()
            : List.of();
        if (tab == Tab.LOCAL) {
            selectedWaypointIds.retainAll(filteredLocalIds);
        } else {
            selectedWaypointIds.clear();
        }

        addTabs();
        if (tab == Tab.LOCAL) {
            addLocalSetControls(store);
        }

        final int bottom = height - BOTTOM_MARGIN;
        final int visibleRowCount = Math.max(1, (bottom - listTop()) / ROW_HEIGHT);
        scrollOffset = MathHelper.clamp(scrollOffset, 0, Math.max(0, sorted.size() - visibleRowCount));

        final int end = Math.min(sorted.size(), scrollOffset + visibleRowCount);
        for (int i = scrollOffset; i < end; i++) {
            final RowInfo source = sorted.get(i);
            final RowInfo row = new RowInfo(
                source.local(), source.shared(), listTop() + (i - scrollOffset) * ROW_HEIGHT,
                source.distance(), source.dimensionText()
            );
            rows.add(row);
            addRowWidgets(row, store);
        }

        addBottomButtons(store);
        snapshotObservedState(store);
    }

    private void addBottomButtons(final WaypointStore store) {
        if (tab != Tab.LOCKED) {
            final int createWidth = tab == Tab.LOCAL ? bottomActionWidth() : 116;
            final ButtonWidget create = addDrawableChild(new ButtonWidget(
                contentLeft(),
                height - 24,
                createWidth,
                18,
                fitButtonLabel(new TranslatableText(
                    tab == Tab.LOCAL
                        ? "confluxmap.screen.waypoints.create_local"
                        : "confluxmap.screen.waypoints.create_public"
                ), createWidth),
                button -> openCreate(store)
            ));
            if (tab == Tab.PUBLIC) {
                create.active = sharedWaypoints.availability().ready();
            } else {
                create.active = store != null && store.persistenceWritable();
            }
            if (tab == Tab.LOCAL) {
                final ButtonWidget importButton = addDrawableChild(new ButtonWidget(
                    contentLeft() + createWidth + GAP,
                    height - 24,
                    createWidth,
                    18,
                    fitButtonLabel(new TranslatableText("confluxmap.screen.waypoints.import_open"), createWidth),
                    button -> openImport(store)
                ));
                importButton.active = store != null && store.persistenceWritable();
            }
        }
        addDrawableChild(new ButtonWidget(
            contentRight() - 80,
            height - 24,
            80,
            18,
            new TranslatableText("confluxmap.screen.waypoint.done"),
            button -> onClose()
        ));
    }

    private void snapshotObservedState(final WaypointStore store) {
        lastSharedRevision = sharedWaypoints.revision();
        lastSharedState = sharedWaypoints.state();
        lastSharedSynchronized = sharedWaypoints.isSynchronized();
        lastOperator = sharedWaypoints.isOperator();
        lastLocalRevision = store == null ? 0L : store.revision();
        lastLocalStore = store;
        lastDimension = gameBridge.session().dimension();
    }

    private void addTabs() {
        final List<Tab> tabs = sharedWaypoints.availability().enabled()
            ? List.of(Tab.LOCAL, Tab.PUBLIC, Tab.LOCKED)
            : List.of(Tab.LOCAL);
        final int availableWidth = contentWidth() - GAP * (tabs.size() - 1);
        final int tabWidth = Math.max(80, Math.min(140, availableWidth / tabs.size()));
        final int totalWidth = tabWidth * tabs.size() + GAP * (tabs.size() - 1);
        int x = width / 2 - totalWidth / 2;
        for (final Tab candidate : tabs) {
            final ButtonWidget button = addDrawableChild(new ButtonWidget(
                x,
                28,
                tabWidth,
                20,
                new TranslatableText(tabKey(candidate)),
                ignored -> selectTab(candidate)
            ));
            button.active = candidate != tab;
            x += tabWidth + GAP;
        }
    }

    private void addLocalSetControls(final WaypointStore store) {
        final boolean writable = store != null && store.persistenceWritable();
        if (narrowToolbar()) {
            addNarrowLocalSetControls(store, writable);
            return;
        }
        final int selectWidth = 84;
        final int filterWidth = Math.min(
            DROPDOWN_MAX_WIDTH,
            Math.max(44, contentWidth() - selectWidth - GAP)
        );
        final int firstRowWidth = filterWidth + GAP + selectWidth;
        final int x = contentLeft() + Math.max(0, (contentWidth() - firstRowWidth) / 2);
        addSetDropdownButton(SetDropdown.FILTER, x, TOOLBAR_Y, filterWidth, setFilterLabel(), store);
        final boolean allFilteredSelected = !filteredLocalIds.isEmpty()
            && selectedWaypointIds.containsAll(filteredLocalIds);
        final ButtonWidget selectAll = addDrawableChild(new ButtonWidget(
            x + filterWidth + GAP,
            TOOLBAR_Y,
            selectWidth,
            TOOLBAR_HEIGHT,
            fitButtonLabel(new TranslatableText(
                allFilteredSelected
                    ? "confluxmap.screen.waypoints.selection_clear"
                    : "confluxmap.screen.waypoints.selection_all"
            ), selectWidth),
            button -> toggleSelectAll(store)
        ));
        selectAll.active = writable && !filteredLocalIds.isEmpty();

        if (selectedWaypointIds.isEmpty()) {
            return;
        }

        final int secondY = TOOLBAR_Y + TOOLBAR_HEIGHT + GAP;
        final int clearWidth = 84;
        final int moveWidth = 92;
        final int targetWidth = Math.min(
            DROPDOWN_MAX_WIDTH,
            Math.max(48, contentWidth() - clearWidth - moveWidth - GAP * 2)
        );
        final int secondRowWidth = clearWidth + targetWidth + moveWidth + GAP * 2;
        final int secondRowX = contentLeft() + Math.max(0, (contentWidth() - secondRowWidth) / 2);
        final ButtonWidget clearSelection = addDrawableChild(new ButtonWidget(
            secondRowX, secondY, clearWidth, TOOLBAR_HEIGHT,
            fitButtonLabel(selectionClearLabel(), clearWidth),
            button -> clearSelection()
        ));
        clearSelection.active = writable;
        addSetDropdownButton(
            SetDropdown.MOVE_TARGET,
            secondRowX + clearWidth + GAP,
            secondY,
            targetWidth,
            moveTargetLabel(),
            store
        );
        final ButtonWidget move = addDrawableChild(new ButtonWidget(
            secondRowX + clearWidth + GAP + targetWidth + GAP,
            secondY,
            moveWidth,
            TOOLBAR_HEIGHT,
            fitButtonLabel(
                new TranslatableText("confluxmap.screen.waypoints.selection_move", selectedWaypointIds.size()),
                moveWidth
            ),
            button -> moveSelection(store)
        ));
        move.active = writable && store.sets().size() > 1 && !selectedWaypointIds.isEmpty();
    }

    private void addNarrowLocalSetControls(final WaypointStore store, final boolean writable) {
        final int selectWidth = Math.min(84, Math.max(44, contentWidth() / 3));
        final int filterWidth = Math.max(44, contentWidth() - selectWidth - GAP);
        addSetDropdownButton(
            SetDropdown.FILTER, contentLeft(), TOOLBAR_Y, filterWidth, setFilterLabel(), store
        );
        final boolean allFilteredSelected = !filteredLocalIds.isEmpty()
            && selectedWaypointIds.containsAll(filteredLocalIds);
        final ButtonWidget selectAll = addDrawableChild(new ButtonWidget(
            contentLeft() + filterWidth + GAP,
            TOOLBAR_Y,
            selectWidth,
            TOOLBAR_HEIGHT,
            fitButtonLabel(new TranslatableText(
                allFilteredSelected
                    ? "confluxmap.screen.waypoints.selection_clear"
                    : "confluxmap.screen.waypoints.selection_all"
            ), selectWidth),
            button -> toggleSelectAll(store)
        ));
        selectAll.active = writable && !filteredLocalIds.isEmpty();

        if (selectedWaypointIds.isEmpty()) {
            return;
        }

        final int firstColumnWidth = Math.max(1, (contentWidth() - GAP * 2) / 3);
        final int batchActionsY = TOOLBAR_Y + TOOLBAR_HEIGHT + GAP;
        int x = contentLeft();
        final ButtonWidget clearSelection = addDrawableChild(new ButtonWidget(
            x, batchActionsY, firstColumnWidth, TOOLBAR_HEIGHT,
            fitButtonLabel(selectionClearLabel(), firstColumnWidth),
            button -> clearSelection()
        ));
        clearSelection.active = writable;
        x += firstColumnWidth + GAP;
        addSetDropdownButton(
            SetDropdown.MOVE_TARGET,
            x,
            batchActionsY,
            firstColumnWidth,
            moveTargetLabel(),
            store
        );
        x += firstColumnWidth + GAP;
        final ButtonWidget move = addDrawableChild(new ButtonWidget(
            x, batchActionsY, Math.max(1, contentRight() - x), TOOLBAR_HEIGHT,
            fitButtonLabel(
                new TranslatableText("confluxmap.screen.waypoints.selection_move", selectedWaypointIds.size()),
                Math.max(1, contentRight() - x)
            ),
            button -> moveSelection(store)
        ));
        move.active = writable && store.sets().size() > 1 && !selectedWaypointIds.isEmpty();
    }

    private List<RowInfo> buildRows(
        final DimensionId currentDimension,
        final double px,
        final double py,
        final double pz
    ) {
        final List<RowInfo> result = new ArrayList<>();
        final boolean crossDimension = ConfluxMapClient.get().config().waypointCrossDimensionEnabled;
        if (tab == Tab.LOCAL) {
            for (final Waypoint waypoint : waypointService.list()) {
                if (selectedSetFilter != null && !selectedSetFilter.equals(waypoint.group)) {
                    continue;
                }
                result.add(new RowInfo(
                    waypoint,
                    null,
                    0,
                    distance(
                        waypoint.dimensionId, waypoint.x, waypoint.y, waypoint.z,
                        currentDimension, px, py, pz, crossDimension
                    ),
                    dimensionLabel(waypoint.dimensionId)
                ));
            }
            return result;
        }
        final boolean locked = tab == Tab.LOCKED;
        for (final SharedWaypoint waypoint : sharedWaypoints.list()) {
            if (waypoint.locked() != locked) {
                continue;
            }
            result.add(new RowInfo(
                null,
                waypoint,
                0,
                distance(
                    waypoint.dimensionId(), waypoint.x(), waypoint.y(), waypoint.z(),
                    currentDimension, px, py, pz, crossDimension
                ),
                dimensionLabel(waypoint.dimensionId())
            ));
        }
        return result;
    }

    private void addRowWidgets(final RowInfo row, final WaypointStore renderedStore) {
        final int buttonY = row.y() + 4;
        final int actionWidth = rowActionWidth();
        final int deleteWidth = rowDeleteWidth();
        final int deleteX = deleteX();
        final int secondaryX = secondaryX();
        final int primaryX = primaryX();

        if (row.local() != null) {
            final Waypoint waypoint = row.local();
            final ButtonWidget selected = addDrawableChild(new ButtonWidget(
                contentLeft() + ROW_PADDING,
                buttonY,
                CHECK_WIDTH,
                20,
                Text.of(selectedWaypointIds.contains(waypoint.id) ? "\u2713" : ""),
                button -> toggleSelected(renderedStore, waypoint.id)
            ));
            selected.active = renderedStore != null && renderedStore.persistenceWritable();
            final ButtonWidget visibility = addDrawableChild(new ButtonWidget(
                primaryX,
                buttonY,
                actionWidth,
                20,
                fitButtonLabel(new TranslatableText(
                    waypoint.visible
                        ? "confluxmap.screen.waypoints.hide"
                        : "confluxmap.screen.waypoints.show"
                ), actionWidth),
                button -> toggleVisible(renderedStore, waypoint)
            ));
            visibility.active = renderedStore != null && renderedStore.persistenceWritable();
            addDrawableChild(new ButtonWidget(
                secondaryX,
                buttonY,
                actionWidth,
                20,
                fitButtonLabel(new TranslatableText("confluxmap.screen.waypoints.share"), actionWidth),
                button -> openShare(renderedStore, waypoint)
            ));
        } else {
            final SharedWaypoint waypoint = row.shared();
            addDrawableChild(new ButtonWidget(
                primaryX,
                buttonY,
                actionWidth,
                20,
                fitButtonLabel(new TranslatableText("confluxmap.screen.waypoints.chat"), actionWidth),
                button -> openSharedChat(waypoint)
            ));
            final ButtonWidget lock = addDrawableChild(new ButtonWidget(
                secondaryX,
                buttonY,
                actionWidth,
                20,
                fitButtonLabel(new TranslatableText(
                    waypoint.locked()
                        ? "confluxmap.screen.waypoints.unlock"
                        : "confluxmap.screen.waypoints.lock"
                ), actionWidth),
                button -> setLocked(waypoint, !waypoint.locked())
            ));
            lock.active = sharedWaypoints.availability().ready() && sharedWaypoints.isOperator();
        }

        final boolean pendingThis = row.id().equals(pendingDeleteId);
        final ButtonWidget delete = addDrawableChild(new ButtonWidget(
            deleteX,
            buttonY,
            deleteWidth,
            20,
            fitButtonLabel(new TranslatableText(
                pendingThis
                    ? "confluxmap.screen.waypoints.confirm"
                    : "confluxmap.screen.waypoints.delete"
            ), deleteWidth),
            button -> delete(renderedStore, row)
        ));
        delete.active = row.shared() == null
            ? renderedStore != null && renderedStore.persistenceWritable()
            : sharedWaypoints.availability().ready() && sharedWaypoints.canDelete(row.shared());
    }

    private void selectTab(final Tab selected) {
        if (selected != Tab.LOCAL && !sharedWaypoints.availability().enabled()) {
            tab = Tab.LOCAL;
        } else {
            tab = selected;
        }
        scrollOffset = 0;
        pendingDeleteId = null;
        selectedWaypointIds.clear();
        closeSetDropdown();
        rebuild();
    }

    private void openCreate(final WaypointStore renderedStore) {
        if (tab == Tab.LOCAL
            && (renderedStore == null
                || renderedStore != waypointService.current()
                || !renderedStore.persistenceWritable())) {
            return;
        }
        final Optional<PlayerView> playerView = gameBridge.player();
        final DimensionId dimension = gameBridge.session().dimension();
        final double x = Math.floor(playerView.map(PlayerView::x).orElse(0.0));
        final double y = Math.floor(playerView.map(PlayerView::y).orElse(64.0));
        final double z = Math.floor(playerView.map(PlayerView::z).orElse(0.0));
        MinecraftClient.getInstance().setScreen(
            tab == Tab.PUBLIC
                ? WaypointEditScreen.forPublicCreate(this, dimension, x, y, z)
                : WaypointEditScreen.forCreate(
                    this, dimension, x, y, z,
                    selectedSetFilter == null ? WaypointSet.DEFAULT_NAME : selectedSetFilter
                )
        );
    }

    private void openEdit(final WaypointStore renderedStore, final Waypoint waypoint) {
        if (renderedStore == null
            || renderedStore != waypointService.current()
            || !renderedStore.persistenceWritable()) {
            return;
        }
        MinecraftClient.getInstance().setScreen(WaypointEditScreen.forEdit(this, waypoint));
    }

    private void openImport(final WaypointStore renderedStore) {
        if (renderedStore == null
            || renderedStore != waypointService.current()
            || !renderedStore.persistenceWritable()) {
            return;
        }
        MinecraftClient.getInstance().setScreen(new WaypointImportScreen(this, renderedStore));
    }

    private void openShare(final WaypointStore renderedStore, final Waypoint waypoint) {
        if (renderedStore != waypointService.current()) {
            return;
        }
        MinecraftClient.getInstance().setScreen(new WaypointShareMenuScreen(this, waypoint));
    }

    private void openSharedChat(final SharedWaypoint waypoint) {
        MinecraftClient.getInstance().setScreen(new WaypointShareConfirmScreen(
            this,
            displayCopy(waypoint),
            WaypointShareConfirmScreen.Target.CHAT
        ));
    }

    private void toggleVisible(final WaypointStore renderedStore, final Waypoint waypoint) {
        final WaypointStore store = waypointService.current();
        if (store == null || store != renderedStore || !store.persistenceWritable()) {
            return;
        }
        final Waypoint updated = waypoint.copy();
        updated.visible = !updated.visible;
        store.update(updated);
        clearPendingActions();
        rebuild();
    }

    private void setLocked(final SharedWaypoint waypoint, final boolean locked) {
        clearPendingActions();
        sharedWaypoints.setLocked(waypoint, locked);
    }

    private void delete(final WaypointStore renderedStore, final RowInfo row) {
        if (row.local() != null
            && (renderedStore == null
                || renderedStore != waypointService.current()
                || !renderedStore.persistenceWritable())) {
            return;
        }
        if (!row.id().equals(pendingDeleteId)) {
            pendingDeleteId = row.id();
            rebuild();
            return;
        }
        if (row.local() != null) {
            renderedStore.remove(row.local().id);
            selectedWaypointIds.remove(row.local().id);
        } else {
            sharedWaypoints.delete(row.shared());
        }
        pendingDeleteId = null;
        rebuild();
    }

    private void toggleSelected(final WaypointStore renderedStore, final UUID waypointId) {
        if (renderedStore == null
            || renderedStore != waypointService.current()
            || !renderedStore.persistenceWritable()) {
            return;
        }
        if (!selectedWaypointIds.add(waypointId)) {
            selectedWaypointIds.remove(waypointId);
        }
        clearPendingActions();
        rebuild();
    }

    private void toggleSelectAll(final WaypointStore store) {
        if (store == null || store != waypointService.current() || !store.persistenceWritable()) {
            return;
        }
        if (!filteredLocalIds.isEmpty() && selectedWaypointIds.containsAll(filteredLocalIds)) {
            selectedWaypointIds.removeAll(filteredLocalIds);
        } else {
            selectedWaypointIds.addAll(filteredLocalIds);
        }
        clearPendingActions();
        rebuild();
    }

    private void clearSelection() {
        selectedWaypointIds.clear();
        clearPendingActions();
        closeSetDropdown();
        rebuild();
    }

    private void moveSelection(final WaypointStore store) {
        if (store == null
            || store != waypointService.current()
            || !store.persistenceWritable()
            || selectedWaypointIds.isEmpty()) {
            return;
        }
        final WaypointStore.BatchMoveResult result = store.moveToSet(selectedWaypointIds, moveTargetSet);
        if (result.result() == WaypointStore.MutationResult.APPLIED
            || result.result() == WaypointStore.MutationResult.NO_CHANGE) {
            selectedWaypointIds.clear();
        }
        clearPendingActions();
        rebuild();
    }

    private void addSetDropdownButton(
        final SetDropdown dropdown,
        final int x,
        final int y,
        final int buttonWidth,
        final Text label,
        final WaypointStore store
    ) {
        final ButtonWidget button = addDrawableChild(new ButtonWidget(
            x,
            y,
            buttonWidth,
            TOOLBAR_HEIGHT,
            fitButtonLabel(label, buttonWidth),
            ignored -> toggleSetDropdown(dropdown, store)
        ));
        if (dropdown == SetDropdown.FILTER) {
            filterDropdownX = x;
            filterDropdownY = y;
            filterDropdownWidth = buttonWidth;
            button.active = store != null;
        } else {
            moveDropdownX = x;
            moveDropdownY = y;
            moveDropdownWidth = buttonWidth;
            button.active = store != null && store.sets().size() > 1;
        }
    }

    private void toggleSetDropdown(final SetDropdown dropdown, final WaypointStore store) {
        if (store == null || store != waypointService.current()) {
            return;
        }
        if (openSetDropdown == dropdown) {
            closeSetDropdown();
        } else {
            openSetDropdown = dropdown;
            dropdownKeyboardIndex = selectedDropdownIndex(store);
            dropdownScrollOffset = DropdownScroll.ensureVisible(
                dropdownKeyboardIndex,
                dropdownOptions(store).size(),
                dropdownVisibleRows(store)
            );
            draggingDropdownScrollbar = false;
        }
        rebuild();
    }

    private void closeSetDropdown() {
        openSetDropdown = null;
        dropdownScrollOffset = 0;
        dropdownKeyboardIndex = 0;
        draggingDropdownScrollbar = false;
    }

    private List<String> dropdownOptions(final WaypointStore store) {
        if (store == null) {
            return List.of();
        }
        final List<String> options = new ArrayList<>();
        if (openSetDropdown == SetDropdown.FILTER) {
            options.add(null);
        }
        for (final WaypointSet set : store.sets()) {
            options.add(set.name());
        }
        return options;
    }

    private int selectedDropdownIndex(final WaypointStore store) {
        final String selected = openSetDropdown == SetDropdown.FILTER
            ? selectedSetFilter
            : moveTargetSet;
        return Math.max(0, dropdownOptions(store).indexOf(selected));
    }

    private int dropdownVisibleRows(final WaypointStore store) {
        final int popupY = dropdownTriggerY() + TOOLBAR_HEIGHT;
        final int actionHeight = openSetDropdown == SetDropdown.FILTER ? DROPDOWN_ACTION_HEIGHT : 0;
        final int availableHeight = height - BOTTOM_MARGIN - popupY - actionHeight;
        final int rowsByHeight = Math.max(1, availableHeight / DROPDOWN_ROW_HEIGHT);
        return Math.min(dropdownOptions(store).size(), Math.min(DROPDOWN_MAX_VISIBLE_ROWS, rowsByHeight));
    }

    private DropdownGeometry dropdownGeometry(final WaypointStore store) {
        if (openSetDropdown == null || store == null) {
            return null;
        }
        final int x = openSetDropdown == SetDropdown.FILTER ? filterDropdownX : moveDropdownX;
        final int y = dropdownTriggerY();
        final int dropdownWidth = openSetDropdown == SetDropdown.FILTER
            ? filterDropdownWidth
            : moveDropdownWidth;
        if (dropdownWidth <= 0) {
            return null;
        }
        final int actionHeight = openSetDropdown == SetDropdown.FILTER ? DROPDOWN_ACTION_HEIGHT : 0;
        return new DropdownGeometry(
            x, y, dropdownWidth, y + TOOLBAR_HEIGHT, dropdownVisibleRows(store), actionHeight
        );
    }

    private int dropdownTriggerY() {
        return openSetDropdown == SetDropdown.FILTER ? filterDropdownY : moveDropdownY;
    }

    private void selectDropdownOption(final WaypointStore store, final String option) {
        if (openSetDropdown == SetDropdown.FILTER) {
            selectedSetFilter = option;
            scrollOffset = 0;
            selectedWaypointIds.clear();
        } else {
            moveTargetSet = option;
        }
        clearPendingActions();
        closeSetDropdown();
        rebuild();
    }

    private void openSetNameScreen(final WaypointStore store, final String existingName) {
        if (store == null || store != waypointService.current() || !store.persistenceWritable()) {
            return;
        }
        MinecraftClient.getInstance().setScreen(new WaypointSetNameScreen(
            this,
            store,
            existingName,
            name -> {
                selectedSetFilter = name;
                moveTargetSet = name;
                selectedWaypointIds.clear();
                clearPendingActions();
            }
        ));
    }

    private void openDeleteSetConfirm(final WaypointStore store, final int members) {
        if (store == null
            || store != waypointService.current()
            || !store.persistenceWritable()
            || !isCustomSetSelected()) {
            return;
        }
        final String setName = selectedSetFilter;
        MinecraftClient.getInstance().setScreen(new WaypointSetDeleteConfirmScreen(
            this,
            store,
            setName,
            members,
            () -> {
                selectedSetFilter = null;
                moveTargetSet = WaypointSet.DEFAULT_NAME;
                selectedWaypointIds.clear();
                scrollOffset = 0;
                clearPendingActions();
            }
        ));
    }

    private void clearPendingActions() {
        pendingDeleteId = null;
    }

    private void normalizeSetState(final WaypointStore store) {
        if (store == null) {
            selectedSetFilter = null;
            moveTargetSet = WaypointSet.DEFAULT_NAME;
            selectedWaypointIds.clear();
            return;
        }
        final List<String> names = store.sets().stream().map(WaypointSet::name).toList();
        if (selectedSetFilter != null && !names.contains(selectedSetFilter)) {
            selectedSetFilter = null;
        }
        if (!names.contains(moveTargetSet)) {
            moveTargetSet = WaypointSet.DEFAULT_NAME;
        }
    }

    private void normalizeDropdownState(final WaypointStore store) {
        if (openSetDropdown == null) {
            return;
        }
        final List<String> options = dropdownOptions(store);
        if (options.isEmpty()) {
            closeSetDropdown();
            return;
        }
        dropdownKeyboardIndex = MathHelper.clamp(dropdownKeyboardIndex, 0, options.size() - 1);
        dropdownScrollOffset = DropdownScroll.keepVisible(
            dropdownScrollOffset,
            dropdownKeyboardIndex,
            options.size(),
            dropdownVisibleRows(store)
        );
    }

    private boolean isCustomSetSelected() {
        return selectedSetFilter != null && !selectedSetFilter.isEmpty();
    }

    private void resetLocalUiState() {
        selectedSetFilter = null;
        moveTargetSet = WaypointSet.DEFAULT_NAME;
        selectedWaypointIds.clear();
        pendingDeleteId = null;
        scrollOffset = 0;
        closeSetDropdown();
    }

    @Override
    public boolean mouseScrolled(final double mouseX, final double mouseY, final double amount) {
        final WaypointStore store = waypointService.current();
        final DropdownGeometry dropdown = dropdownGeometry(store);
        if (amount != 0 && dropdown != null
            && (dropdown.containsPopup(mouseX, mouseY) || dropdown.containsTrigger(mouseX, mouseY))) {
            final int optionCount = dropdownOptions(store).size();
            dropdownScrollOffset = DropdownScroll.afterWheel(
                dropdownScrollOffset, amount, optionCount, dropdown.visibleRows()
            );
            return true;
        }
        if (amount != 0) {
            scrollOffset -= (int) Math.signum(amount);
            rebuild();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public boolean mouseClicked(final double mouseX, final double mouseY, final int button) {
        final WaypointStore store = waypointService.current();
        final DropdownGeometry dropdown = dropdownGeometry(store);
        if (button == 0 && dropdown != null) {
            if (dropdown.containsPopup(mouseX, mouseY)) {
                final List<String> options = dropdownOptions(store);
                final int optionBottom = dropdown.popupY() + dropdown.optionHeight();
                if (mouseY < optionBottom
                    && options.size() > dropdown.visibleRows()
                    && mouseX >= dropdown.x() + dropdown.width() - DROPDOWN_SCROLLBAR_WIDTH) {
                    draggingDropdownScrollbar = true;
                    updateDropdownScrollFromMouse(mouseY, dropdown, options.size());
                    return true;
                }
                if (mouseY >= optionBottom && dropdown.actionHeight() > 0) {
                    activateDropdownAction(store, dropdownActionAt(mouseX, dropdown));
                    return true;
                }
                final int visibleIndex = (int) ((mouseY - dropdown.popupY()) / DROPDOWN_ROW_HEIGHT);
                final int optionIndex = dropdownScrollOffset + visibleIndex;
                if (optionIndex >= 0 && optionIndex < options.size()) {
                    selectDropdownOption(store, options.get(optionIndex));
                }
                return true;
            }
            if (!dropdown.containsTrigger(mouseX, mouseY)) {
                closeSetDropdown();
                rebuild();
                return true;
            }
        }
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button == 0 && tab == Tab.LOCAL) {
            for (final RowInfo row : rows) {
                if (row.local() != null
                    && mouseY >= row.y() && mouseY < row.y() + ROW_HEIGHT
                    && mouseX >= nameX() && mouseX < nameRight()) {
                    final WaypointStore currentStore = waypointService.current();
                    if (currentStore == lastLocalStore
                        && currentStore != null
                        && currentStore.persistenceWritable()) {
                        openEdit(currentStore, row.local());
                    }
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(
        final double mouseX,
        final double mouseY,
        final int button,
        final double deltaX,
        final double deltaY
    ) {
        if (button == 0 && draggingDropdownScrollbar) {
            final WaypointStore store = waypointService.current();
            final DropdownGeometry dropdown = dropdownGeometry(store);
            if (dropdown != null) {
                updateDropdownScrollFromMouse(mouseY, dropdown, dropdownOptions(store).size());
                return true;
            }
            draggingDropdownScrollbar = false;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(final double mouseX, final double mouseY, final int button) {
        if (button == 0 && draggingDropdownScrollbar) {
            draggingDropdownScrollbar = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(final int keyCode, final int scanCode, final int modifiers) {
        if (openSetDropdown != null) {
            final WaypointStore store = waypointService.current();
            final List<String> options = dropdownOptions(store);
            if (keyCode == 256) {
                closeSetDropdown();
                rebuild();
                return true;
            }
            if (!options.isEmpty() && (keyCode == 264 || keyCode == 265 || keyCode == 268 || keyCode == 269)) {
                dropdownKeyboardIndex = switch (keyCode) {
                    case 264 -> Math.min(options.size() - 1, dropdownKeyboardIndex + 1);
                    case 265 -> Math.max(0, dropdownKeyboardIndex - 1);
                    case 268 -> 0;
                    case 269 -> options.size() - 1;
                    default -> dropdownKeyboardIndex;
                };
                dropdownScrollOffset = DropdownScroll.keepVisible(
                    dropdownScrollOffset,
                    dropdownKeyboardIndex,
                    options.size(),
                    dropdownVisibleRows(store)
                );
                return true;
            }
            if (!options.isEmpty() && (keyCode == 257 || keyCode == 335)) {
                selectDropdownOption(store, options.get(dropdownKeyboardIndex));
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private int dropdownActionAt(final double mouseX, final DropdownGeometry dropdown) {
        final double relativeX = MathHelper.clamp(mouseX - dropdown.x(), 0.0, dropdown.width() - 1.0);
        return Math.min(2, (int) (relativeX * 3.0 / dropdown.width()));
    }

    private void activateDropdownAction(final WaypointStore store, final int actionIndex) {
        if (!dropdownActionActive(store, actionIndex)) {
            return;
        }
        closeSetDropdown();
        if (actionIndex == 0) {
            openSetNameScreen(store, null);
        } else if (actionIndex == 1) {
            openSetNameScreen(store, selectedSetFilter);
        } else {
            openDeleteSetConfirm(store, store.waypointCount(selectedSetFilter));
        }
    }

    private boolean dropdownActionActive(final WaypointStore store, final int actionIndex) {
        final boolean writable = store != null
            && store == waypointService.current()
            && store.persistenceWritable();
        return writable && (actionIndex == 0 || isCustomSetSelected());
    }

    private Text dropdownActionLabel(final WaypointStore store, final int actionIndex) {
        return switch (actionIndex) {
            case 0 -> new TranslatableText("confluxmap.screen.waypoints.set_new");
            case 1 -> new TranslatableText("confluxmap.screen.waypoints.set_rename");
            default -> new TranslatableText(
                "confluxmap.screen.waypoints.set_delete",
                store == null || !isCustomSetSelected() ? 0 : store.waypointCount(selectedSetFilter)
            );
        };
    }

    private void updateDropdownScrollFromMouse(
        final double mouseY,
        final DropdownGeometry dropdown,
        final int optionCount
    ) {
        // The track deliberately spans only the option rows, never the action strip below them.
        final int trackTop = dropdown.popupY() + 1;
        final int trackHeight = Math.max(1, dropdown.optionHeight() - 2);
        final int thumbHeight = dropdownThumbHeight(trackHeight, optionCount, dropdown.visibleRows());
        dropdownScrollOffset = DropdownScroll.fromThumbPosition(
            mouseY,
            trackTop,
            trackHeight,
            thumbHeight,
            optionCount,
            dropdown.visibleRows()
        );
    }

    @Override
    public void render(final MatrixStack matrices, final int mouseX, final int mouseY, final float tickDelta) {
        renderBackground(matrices);
        final String title = getTitle().getString();
        textRenderer.drawWithShadow(
            matrices, title, width / 2f - textRenderer.getWidth(title) / 2f, 10, 0xFFFFFFFF
        );

        final boolean compact = compactRows();
        for (final RowInfo row : rows) {
            final boolean hovered = mouseX >= contentLeft() && mouseX <= contentRight()
                && mouseY >= row.y() && mouseY < row.y() + ROW_HEIGHT;
            RenderUtil.fillRect(
                matrices,
                contentLeft(),
                row.y() + 1,
                contentWidth(),
                ROW_HEIGHT - 2,
                hovered ? 0x55333333 : 0x33202020
            );
            final int markerLeft = markerX();
            WaypointMarkerRenderer.draw(
                matrices,
                textRenderer,
                row.renderEntry(),
                markerLeft + MARKER_SIZE / 2f,
                row.y() + ROW_HEIGHT / 2f,
                MARKER_SIZE / 2f - 1f,
                1f,
                hovered
            );
            final int maxNameWidth = Math.max(24, nameRight() - nameX());
            final String name = textRenderer.trimToWidth(row.name(), maxNameWidth);
            textRenderer.drawWithShadow(matrices, name, nameX(), row.y() + 4, 0xFFFFFFFF);
            final String secondary = textRenderer.trimToWidth(row.secondaryText(), maxNameWidth);
            textRenderer.drawWithShadow(matrices, secondary, nameX(), row.y() + 15, 0xFFAAAAAA);
            if (!compact) {
                final String distance = textRenderer.trimToWidth(formatDistance(row.distance()), DIST_WIDTH);
                textRenderer.drawWithShadow(matrices, distance, distanceX(), row.y() + 10, 0xFFCCCCCC);
                final String dimension = textRenderer.trimToWidth(row.dimensionText(), DIM_WIDTH);
                textRenderer.drawWithShadow(matrices, dimension, dimensionX(), row.y() + 10, 0xFFCCCCCC);
            }
        }
        if (rows.isEmpty()) {
            final String empty = textRenderer.trimToWidth(
                new TranslatableText(emptyKey()).getString(), Math.max(40, contentWidth() - 16)
            );
            textRenderer.drawWithShadow(
                matrices, empty, width / 2f - textRenderer.getWidth(empty) / 2f, listTop() + 6, 0xFFAAAAAA
            );
        }
        final WaypointStore store = waypointService.current();
        if (tab == Tab.LOCAL && store != null && !store.persistenceWritable()) {
            final String readOnly = textRenderer.trimToWidth(
                new TranslatableText("confluxmap.screen.waypoints.read_only").getString(), contentWidth()
            );
            textRenderer.drawWithShadow(
                matrices, readOnly, width / 2f - textRenderer.getWidth(readOnly) / 2f,
                height - BOTTOM_MARGIN - 10, 0xFFFF7777
            );
        }

        super.render(matrices, mouseX, mouseY, tickDelta);
        renderSetDropdown(matrices, mouseX, mouseY);
    }

    private void renderSetDropdown(final MatrixStack matrices, final int mouseX, final int mouseY) {
        final WaypointStore store = waypointService.current();
        final DropdownGeometry dropdown = dropdownGeometry(store);
        if (dropdown == null || dropdown.visibleRows() <= 0) {
            return;
        }
        final List<String> options = dropdownOptions(store);
        dropdownScrollOffset = DropdownScroll.clamp(
            dropdownScrollOffset, options.size(), dropdown.visibleRows()
        );
        final boolean hasScrollbar = options.size() > dropdown.visibleRows();
        final int textRightPadding = hasScrollbar ? DROPDOWN_SCROLLBAR_WIDTH + 5 : 5;

        fill(
            matrices,
            dropdown.x() - 1,
            dropdown.popupY() - 1,
            dropdown.x() + dropdown.width() + 1,
            dropdown.popupY() + dropdown.popupHeight() + 1,
            0xFF000000
        );
        for (int visibleIndex = 0; visibleIndex < dropdown.visibleRows(); visibleIndex++) {
            final int optionIndex = dropdownScrollOffset + visibleIndex;
            if (optionIndex >= options.size()) {
                break;
            }
            final int rowY = dropdown.popupY() + visibleIndex * DROPDOWN_ROW_HEIGHT;
            final boolean hovered = mouseX >= dropdown.x()
                && mouseX < dropdown.x() + dropdown.width() - (hasScrollbar ? DROPDOWN_SCROLLBAR_WIDTH : 0)
                && mouseY >= rowY
                && mouseY < rowY + DROPDOWN_ROW_HEIGHT;
            final String option = options.get(optionIndex);
            final boolean selected = openSetDropdown == SetDropdown.FILTER
                ? java.util.Objects.equals(option, selectedSetFilter)
                : java.util.Objects.equals(option, moveTargetSet);
            final boolean keyboardFocused = optionIndex == dropdownKeyboardIndex;
            fill(
                matrices,
                dropdown.x(),
                rowY,
                dropdown.x() + dropdown.width(),
                rowY + DROPDOWN_ROW_HEIGHT,
                hovered || keyboardFocused ? 0xFF6E6E6E : selected ? 0xFF505050 : 0xFF2A2A2A
            );
            if (selected) {
                fill(matrices, dropdown.x(), rowY + 2, dropdown.x() + 2, rowY + DROPDOWN_ROW_HEIGHT - 2, 0xFFFFFFFF);
            }
            final String count = Integer.toString(dropdownOptionCount(store, option));
            final int textRight = dropdown.x() + dropdown.width() - textRightPadding;
            final int countWidth = textRenderer.getWidth(count);
            final String label = textRenderer.trimToWidth(
                setDisplayName(option), Math.max(8, textRight - countWidth - GAP - dropdown.x() - 5)
            );
            textRenderer.drawWithShadow(
                matrices,
                label,
                dropdown.x() + 5,
                rowY + (DROPDOWN_ROW_HEIGHT - textRenderer.fontHeight) / 2f,
                selected ? 0xFFFFFFFF : 0xFFE0E0E0
            );
            textRenderer.drawWithShadow(
                matrices,
                count,
                textRight - countWidth,
                rowY + (DROPDOWN_ROW_HEIGHT - textRenderer.fontHeight) / 2f,
                0xFFAAAAAA
            );
        }

        if (hasScrollbar) {
            final int trackX = dropdown.x() + dropdown.width() - DROPDOWN_SCROLLBAR_WIDTH;
            final int trackTop = dropdown.popupY() + 1;
            final int trackHeight = Math.max(1, dropdown.optionHeight() - 2);
            final int thumbHeight = dropdownThumbHeight(trackHeight, options.size(), dropdown.visibleRows());
            final int maxOffset = DropdownScroll.maxOffset(options.size(), dropdown.visibleRows());
            final int thumbTravel = Math.max(0, trackHeight - thumbHeight);
            final int thumbTop = trackTop + (maxOffset == 0
                ? 0
                : Math.round(thumbTravel * (dropdownScrollOffset / (float) maxOffset)));
            fill(
                matrices,
                trackX,
                dropdown.popupY(),
                dropdown.x() + dropdown.width(),
                dropdown.popupY() + dropdown.optionHeight(),
                0xFF151515
            );
            fill(
                matrices,
                trackX + 1,
                thumbTop,
                dropdown.x() + dropdown.width() - 1,
                thumbTop + thumbHeight,
                draggingDropdownScrollbar ? 0xFFFFFFFF : 0xFFAAAAAA
            );
        }

        if (dropdown.actionHeight() > 0) {
            renderDropdownActions(matrices, mouseX, mouseY, store, dropdown);
        }
    }

    private int dropdownOptionCount(final WaypointStore store, final String option) {
        return store == null
            ? 0
            : option == null ? store.size() : store.waypointCount(option);
    }

    private void renderDropdownActions(
        final MatrixStack matrices,
        final int mouseX,
        final int mouseY,
        final WaypointStore store,
        final DropdownGeometry dropdown
    ) {
        final int actionY = dropdown.popupY() + dropdown.optionHeight();
        fill(
            matrices,
            dropdown.x(),
            actionY,
            dropdown.x() + dropdown.width(),
            actionY + 1,
            0xFF111111
        );
        for (int actionIndex = 0; actionIndex < 3; actionIndex++) {
            final int actionLeft = dropdown.x() + dropdown.width() * actionIndex / 3;
            final int actionRight = dropdown.x() + dropdown.width() * (actionIndex + 1) / 3;
            final boolean active = dropdownActionActive(store, actionIndex);
            final boolean hovered = active
                && mouseX >= actionLeft
                && mouseX < actionRight
                && mouseY >= actionY
                && mouseY < actionY + dropdown.actionHeight();
            fill(
                matrices,
                actionLeft,
                actionY,
                actionRight,
                actionY + dropdown.actionHeight(),
                actionIndex == 2 && active
                    ? hovered ? 0xFF8A3A3A : 0xFF5A2A2A
                    : hovered ? 0xFF6E6E6E : active ? 0xFF383838 : 0xFF202020
            );
            if (actionIndex > 0) {
                fill(matrices, actionLeft, actionY + 3, actionLeft + 1, actionY + dropdown.actionHeight() - 3, 0xFF101010);
            }
            final String label = textRenderer.trimToWidth(
                dropdownActionLabel(store, actionIndex).getString(), Math.max(8, actionRight - actionLeft - 6)
            );
            textRenderer.drawWithShadow(
                matrices,
                label,
                (actionLeft + actionRight - textRenderer.getWidth(label)) / 2f,
                actionY + (dropdown.actionHeight() - textRenderer.fontHeight) / 2f,
                active ? 0xFFFFFFFF : 0xFF777777
            );
        }
    }

    private static int dropdownThumbHeight(
        final int trackHeight,
        final int optionCount,
        final int visibleRows
    ) {
        if (optionCount <= 0) {
            return trackHeight;
        }
        return Math.max(8, Math.round(trackHeight * (visibleRows / (float) optionCount)));
    }

    private String emptyKey() {
        if (tab == Tab.LOCAL) {
            return selectedSetFilter == null
                ? "confluxmap.screen.waypoints.empty_local"
                : "confluxmap.screen.waypoints.empty_set";
        }
        return sharedWaypoints.isSynchronized()
            ? "confluxmap.screen.waypoints.empty_public"
            : "confluxmap.shared_waypoints.status.syncing";
    }

    private Text setFilterLabel() {
        return new TranslatableText(
            "confluxmap.screen.waypoints.set_filter",
            setDisplayName(selectedSetFilter),
            filteredLocalIds.size()
        );
    }

    private Text moveTargetLabel() {
        return new TranslatableText(
            "confluxmap.screen.waypoints.selection_target",
            setDisplayName(moveTargetSet)
        );
    }

    private Text selectionClearLabel() {
        return Text.of(
            new TranslatableText("confluxmap.screen.waypoints.selection_clear").getString()
                + " (" + selectedWaypointIds.size() + ")"
        );
    }

    private Text fitButtonLabel(final Text label, final int buttonWidth) {
        return Text.of(textRenderer.trimToWidth(label.getString(), Math.max(8, buttonWidth - 8)));
    }

    private static String setDisplayName(final String setName) {
        if (setName == null) {
            return new TranslatableText("confluxmap.screen.waypoints.set_all").getString();
        }
        if (setName.isEmpty()) {
            return new TranslatableText("confluxmap.screen.waypoints.set_unassigned").getString();
        }
        return setName;
    }

    private int listTop() {
        if (tab != Tab.LOCAL) {
            return SHARED_LIST_TOP;
        }
        return selectedWaypointIds.isEmpty() ? LOCAL_IDLE_LIST_TOP : LOCAL_LIST_TOP;
    }

    private int contentLeft() {
        return Math.max(MIN_SIDE_MARGIN, (width - MAX_CONTENT_WIDTH) / 2);
    }

    private int contentRight() {
        return width - contentLeft();
    }

    private int contentWidth() {
        return contentRight() - contentLeft();
    }

    private boolean compactRows() {
        return contentWidth() < 620;
    }

    private boolean narrowToolbar() {
        return contentWidth() < NARROW_TOOLBAR_WIDTH;
    }

    /** Bottom-bar action width: two actions plus the done button must fit on narrow screens. */
    private int bottomActionWidth() {
        return Math.min(116, Math.max(60, (contentWidth() - 80 - GAP * 2) / 2));
    }

    private boolean narrowRows() {
        return contentWidth() < NARROW_ROW_WIDTH;
    }

    private int rowActionWidth() {
        return narrowRows() ? NARROW_ACTION_WIDTH : ACTION_WIDTH;
    }

    private int rowDeleteWidth() {
        return narrowRows() ? NARROW_DELETE_WIDTH : DELETE_WIDTH;
    }

    private int deleteX() {
        return contentRight() - ROW_PADDING - rowDeleteWidth();
    }

    private int secondaryX() {
        return deleteX() - GAP - rowActionWidth();
    }

    private int primaryX() {
        return secondaryX() - GAP - rowActionWidth();
    }

    private int dimensionX() {
        return primaryX() - GAP - DIM_WIDTH;
    }

    private int distanceX() {
        return dimensionX() - GAP - DIST_WIDTH;
    }

    private int markerX() {
        final int selectionWidth = tab == Tab.LOCAL ? CHECK_WIDTH + GAP : 0;
        return contentLeft() + ROW_PADDING + selectionWidth;
    }

    private int nameX() {
        return markerX() + MARKER_SIZE + GAP;
    }

    private int nameRight() {
        return compactRows() ? primaryX() - GAP : distanceX() - GAP;
    }

    private static double distance(
        final DimensionId waypointDimension,
        final double x,
        final double y,
        final double z,
        final DimensionId currentDimension,
        final double px,
        final double py,
        final double pz,
        final boolean crossDimension
    ) {
        if (!waypointDimension.equals(currentDimension)
            && (!crossDimension || !DimensionScale.isVisibleFrom(waypointDimension, currentDimension))) {
            return Double.POSITIVE_INFINITY;
        }
        final double dx = DimensionScale.convertHorizontal(x, waypointDimension, currentDimension) - px;
        final double dz = DimensionScale.convertHorizontal(z, waypointDimension, currentDimension) - pz;
        final double dy = y - py;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static Waypoint displayCopy(final SharedWaypoint waypoint) {
        return new Waypoint(
            waypoint.id(), waypoint.name(), waypoint.dimensionId(),
            waypoint.x(), waypoint.y(), waypoint.z(), waypoint.colorArgb(), "", true,
            waypoint.type(), waypoint.createdAtEpochMs()
        );
    }

    private static String tabKey(final Tab tab) {
        return switch (tab) {
            case LOCAL -> "confluxmap.screen.waypoints.tab.local";
            case PUBLIC -> "confluxmap.screen.waypoints.tab.public";
            case LOCKED -> "confluxmap.screen.waypoints.tab.locked";
        };
    }

    private static String formatDistance(final double distance) {
        return Double.isFinite(distance)
            ? new TranslatableText("confluxmap.value.blocks", Math.round(distance)).getString()
            : "-";
    }

    private static String dimensionLabel(final DimensionId dimension) {
        if (dimension.equals(DimensionId.OVERWORLD)) {
            return new TranslatableText("confluxmap.dimension.overworld").getString();
        }
        if (dimension.equals(DimensionId.NETHER)) {
            return new TranslatableText("confluxmap.dimension.the_nether").getString();
        }
        if (dimension.equals(DimensionId.END)) {
            return new TranslatableText("confluxmap.dimension.the_end").getString();
        }
        return dimension.path();
    }
}
