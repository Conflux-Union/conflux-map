package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.compat.Ids;
import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.compat.MouseButtons;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.api.ConfluxMapEvents;
import cn.net.rms.confluxmap.bridge.GameBridge;
import cn.net.rms.confluxmap.bridge.PlayerView;
import cn.net.rms.confluxmap.core.api.ConfluxMapApiImpl;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointAvailability;
import cn.net.rms.confluxmap.core.waypoint.DimensionScale;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import cn.net.rms.confluxmap.core.waypoint.WaypointDimensionFilter;
import cn.net.rms.confluxmap.core.waypoint.WaypointListFilter;
import cn.net.rms.confluxmap.core.waypoint.WaypointListGrouping;
import cn.net.rms.confluxmap.core.waypoint.WaypointRenderEntry;
import cn.net.rms.confluxmap.core.waypoint.WaypointService;
import cn.net.rms.confluxmap.core.waypoint.WaypointStore;
import cn.net.rms.confluxmap.core.waypoint.WaypointVerticalRelation;
import cn.net.rms.confluxmap.core.waypoint.list.GroupRef;
import cn.net.rms.confluxmap.core.waypoint.list.LocalWaypointListModel;
import cn.net.rms.confluxmap.core.waypoint.list.SharedWaypointListModel;
import cn.net.rms.confluxmap.core.waypoint.list.WaypointListModel;
import cn.net.rms.confluxmap.core.waypoint.list.WaypointRow;
import cn.net.rms.confluxmap.mc.net.shared.SharedWaypointClient;
import cn.net.rms.confluxmap.mc.render.GuiShapes;
import cn.net.rms.confluxmap.mc.teleport.ClientGroundTeleportService;
import cn.net.rms.confluxmap.mc.teleport.TeleportCommandAccess;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import cn.net.rms.confluxmap.mc.ui.WaypointMarkerRenderer;
import cn.net.rms.confluxmap.mc.ui.widget.ConfluxTextButton;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.MinecraftClient;
//#if MC>=12109
//$$ import net.minecraft.client.gui.Click;
//$$ import net.minecraft.client.input.KeyInput;
//#endif
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

/**
 * Management UI for local waypoint sets and server-enabled shared waypoints.
 * Both tabs render one shared template: rows, group headers, the toolbar, the
 * dropdowns, and every permission come from the active tab's
 * {@link WaypointListModel}, so the two sides keep parity by construction and
 * differ only in the model each tab binds.
 */
public final class WaypointListScreen extends ConfluxScreen {
    public enum Tab { LOCAL, PUBLIC }

    private static final int ROW_HEIGHT = 28;
    private static final int SEARCH_Y = 52;
    private static final int LOCAL_IDLE_LIST_TOP = 128;
    private static final int LOCAL_LIST_TOP = 152;
    private static final int BOTTOM_MARGIN = 34;
    private static final int MIN_SIDE_MARGIN = 16;
    private static final int MAX_CONTENT_WIDTH = 880;
    private static final int HEADER_CONTROL_MAX_WIDTH = 280;
    private static final int ROW_PADDING = 6;
    private static final int MARKER_SIZE = 14;
    private static final int CHECK_WIDTH = 20;
    private static final int ROW_ACTION_MAX_WIDTH = 48;
    private static final int ROW_ICON_ACTION_WIDTH = 20;
    /** Both tabs expose the same row actions: eye, share, edit, delete, locate, teleport. */
    private static final int[] ROW_ACTION_WIDTHS = {
        ROW_ICON_ACTION_WIDTH, ROW_ICON_ACTION_WIDTH,
        ROW_ACTION_MAX_WIDTH, ROW_ACTION_MAX_WIDTH, ROW_ACTION_MAX_WIDTH, ROW_ACTION_MAX_WIDTH
    };
    private static final Identifier VISIBLE_ICON = Ids.of(
        "confluxmap", "textures/gui/waypoint_visible.png"
    );
    private static final Identifier HIDDEN_ICON = Ids.of(
        "confluxmap", "textures/gui/waypoint_hidden.png"
    );
    private static final Identifier SHARE_ICON = Ids.of(
        "confluxmap", "textures/gui/waypoint_share.png"
    );
    private static final int DIM_WIDTH = 66;
    private static final int DIST_WIDTH = 58;
    private static final int GAP = 4;
    private static final int DIMENSION_FILTER_Y = 76;
    private static final int TOOLBAR_Y = 100;
    private static final int TOOLBAR_HEIGHT = 20;
    private static final int NARROW_TOOLBAR_WIDTH = 248;
    private static final int DROPDOWN_ROW_HEIGHT = 20;
    private static final int DROPDOWN_MAX_VISIBLE_ROWS = 6;
    private static final int DROPDOWN_SCROLLBAR_WIDTH = 6;
    private static final int DROPDOWN_ACTION_HEIGHT = 22;
    private static final int SET_MEMBER_INDENT = 10;
    private static final String COLLAPSED_ARROW = "\u25b8";
    private static final String EXPANDED_ARROW = "\u25be";

    private enum GroupDropdown { FILTER, MOVE_TARGET }

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
        WaypointRow row,
        WaypointRenderEntry.Source source,
        int y,
        double distance,
        String dimensionText
    ) {
        UUID id() {
            return row.id();
        }

        String name() {
            return row.name();
        }

        WaypointRenderEntry renderEntry() {
            return new WaypointRenderEntry(
                row.id(), row.name(), row.dimensionId(), row.x(), row.y(), row.z(),
                row.colorArgb(), row.iconItemId(), row.markerLabel(), row.type(), source,
                row.crossDimensionVisible(), row.worldLabel()
            );
        }
    }

    /** One group header row of the grouped list. */
    private record HeaderRow(GroupRef group, int visibleCount, int y) {
    }

    /** One rendered list row: a group header or a (possibly indented) waypoint row. */
    private record ListRow(HeaderRow header, RowInfo waypoint, boolean indented) {
        static ListRow ofHeader(final HeaderRow header) {
            return new ListRow(header, null, false);
        }

        static ListRow ofWaypoint(final RowInfo waypoint, final boolean indented) {
            return new ListRow(null, waypoint, indented);
        }

        boolean isHeader() {
            return header != null;
        }

        int y() {
            return header != null ? header.y() : waypoint.y();
        }
    }

    private final GameBridge gameBridge;
    private final WaypointService waypointService;
    private final LocalWaypointListModel localModel;
    private final SharedWaypointListModel sharedModel;
    private final SharedWaypointClient sharedWaypoints;
    private final ConfluxConfig config;
    private final ClientGroundTeleportService groundTeleport;
    private final Screen parent;
    private final boolean openedFromHotkey;
    private final Set<UUID> selectedWaypointIds = new LinkedHashSet<>();
    private final Set<ButtonWidget> iconActions =
        java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private Tab tab;

    private int scrollOffset;
    private UUID pendingDeleteId;
    /** Group filter of the active tab; null means "All", so it survives tab switches per tab. */
    private GroupRef selectedLocalGroupFilter;
    private GroupRef selectedSharedGroupFilter;
    private GroupRef localMoveTarget = GroupRef.ungrouped(false);
    private GroupRef sharedMoveTarget = GroupRef.ungrouped(false);
    private WaypointDimensionFilter dimensionFilter = WaypointDimensionFilter.current();
    private boolean dimensionDropdownOpen;
    private int dimensionDropdownX;
    private int dimensionDropdownWidth;
    private List<WaypointDimensionFilter> dimensionDropdownOptions = List.of();
    private int dimensionDropdownScrollOffset;
    private int dimensionDropdownKeyboardIndex;
    private GroupDropdown openGroupDropdown;
    private int dropdownScrollOffset;
    private int dropdownKeyboardIndex;
    private boolean draggingDropdownScrollbar;
    private int filterDropdownX;
    private int filterDropdownY;
    private int filterDropdownWidth;
    private int moveDropdownX;
    private int moveDropdownY;
    private int moveDropdownWidth;
    private List<WaypointRow> filteredOwnedRows = List.of();
    private List<UUID> filteredOwnedIds = List.of();
    private List<ListRow> rows = new ArrayList<>();
    private int totalRowCount;
    private TextFieldWidget searchField;
    private WaypointRowActionLayout actionLayout;
    private String observedSearch = "";
    private long lastStateVersion = Long.MIN_VALUE;
    private WaypointStore lastLocalStore;
    private DimensionId lastDimension;

    public WaypointListScreen() {
        this(null, Tab.LOCAL, false);
    }

    public WaypointListScreen(final Screen parent, final Tab initialTab) {
        this(parent, initialTab, false);
    }

    public static WaypointListScreen openedFromHotkey() {
        return new WaypointListScreen(null, Tab.LOCAL, true);
    }

    private WaypointListScreen(
        final Screen parent,
        final Tab initialTab,
        final boolean openedFromHotkey
    ) {
        super(Texts.translatable("confluxmap.screen.waypoints.title"));
        final ConfluxMapClient app = ConfluxMapClient.get();
        this.gameBridge = app.gameBridge();
        this.waypointService = app.waypointService();
        this.localModel = app.localWaypointListModel();
        this.sharedModel = app.sharedWaypointListModel();
        this.sharedWaypoints = app.sharedWaypoints();
        this.config = app.config();
        this.groundTeleport = app.groundTeleportService();
        this.parent = parent;
        this.openedFromHotkey = openedFromHotkey;
        this.tab = initialTab == null ? Tab.LOCAL : initialTab;
        // Opening defaults come in layers: the caller's explicit tab, then the hotkey
        // config defaults, then the API opening event; the availability guard runs last.
        if (openedFromHotkey) {
            if (config.waypointListDefaultTab == ConfluxConfig.WaypointListTab.PUBLIC) {
                this.tab = Tab.PUBLIC;
            }
            if (config.waypointListDefaultDimension == ConfluxConfig.WaypointListDefaultDimension.ALL) {
                this.dimensionFilter = WaypointDimensionFilter.all();
            }
        }
        final ConfluxMapApiImpl api = app.api();
        if (api != null) {
            final ConfluxMapEvents.WaypointListOpening opening = api.queryWaypointListOpening(
                openedFromHotkey, sharedWaypoints.availability().enabled()
            );
            if (opening.requestedTab() == ConfluxMapEvents.WaypointListTab.LOCAL) {
                this.tab = Tab.LOCAL;
            } else if (opening.requestedTab() == ConfluxMapEvents.WaypointListTab.PUBLIC) {
                this.tab = Tab.PUBLIC;
            }
            if (opening.requestedAllDimensions()) {
                this.dimensionFilter = WaypointDimensionFilter.all();
            }
        }
        if (this.tab != Tab.LOCAL && !sharedWaypoints.availability().enabled()) {
            this.tab = Tab.LOCAL;
        }
    }

    private WaypointListModel activeModel() {
        return tab == Tab.LOCAL ? localModel : sharedModel;
    }

    @Override
    public void onClose() {
        MinecraftAccess.setScreen(MinecraftClient.getInstance(), parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        final int searchWidth = headerControlWidth();
        searchField = new TextFieldWidget(
            this.textRenderer,
            width / 2 - searchWidth / 2,
            SEARCH_Y,
            searchWidth,
            20,
            Texts.translatable("confluxmap.screen.waypoints.search")
        );
        searchField.setMaxLength(64);
        searchField.setText(observedSearch);
        rebuild();
        if (openedFromHotkey) {
            deferInitialFocusUntilNextTick(searchField);
        } else {
            setInitialFocus(searchField);
        }
    }

    @Override
    public void tick() {
        super.tick();
        Widgets.tick(searchField);
        final String currentSearch = searchField == null ? "" : searchField.getText();
        if (!currentSearch.equals(observedSearch)) {
            observedSearch = currentSearch;
            scrollOffset = 0;
            rebuild();
            return;
        }
        final SharedWaypointAvailability availability = sharedWaypoints.availability();
        if (!availability.enabled() && tab != Tab.LOCAL) {
            selectTab(Tab.LOCAL);
            return;
        }
        // A local store swap is session lifecycle, not model data: the filters and the
        // scroll position belong to the old world and reset here, not through a rebuild.
        if (waypointService.current() != lastLocalStore) {
            resetLocalUiState();
            rebuild();
            return;
        }
        final DimensionId currentDimension = gameBridge.session().dimension();
        if (!currentDimension.equals(lastDimension) || activeModel().stateVersion() != lastStateVersion) {
            rebuild();
        }
    }

    private void rebuild() {
        clearChildren();
        iconActions.clear();
        actionLayout = createRowActionLayout();
        if (searchField != null) {
            addDrawableChild(searchField);
        }
        rows.clear();
        filterDropdownWidth = 0;
        moveDropdownWidth = 0;
        final WaypointListModel model = activeModel();
        normalizeGroupState(model);
        final Optional<PlayerView> playerView = gameBridge.player();
        final DimensionId currentDimension = gameBridge.session().dimension();
        final List<DimensionId> knownDimensions = knownDimensions(currentDimension);
        dimensionFilter = dimensionFilter.normalized(currentDimension, knownDimensions);
        final double px = playerView.map(PlayerView::x).orElse(0.0);
        final double py = playerView.map(PlayerView::y).orElse(0.0);
        final double pz = playerView.map(PlayerView::z).orElse(0.0);

        final List<RowInfo> candidates = buildRows(model, currentDimension, px, py, pz);
        candidates.removeIf(row -> !matchesSearch(row, observedSearch));
        final List<ListRow> listModel = buildListModel(model, candidates);
        totalRowCount = listModel.size();
        filteredOwnedRows = listModel.stream()
            .filter(row -> !row.isHeader() && !row.waypoint().row().guest())
            .map(row -> row.waypoint().row())
            .toList();
        filteredOwnedIds = filteredOwnedRows.stream().map(WaypointRow::id).toList();
        selectedWaypointIds.retainAll(filteredOwnedIds);

        addTabs();
        addDimensionFilter(knownDimensions, currentDimension);
        addGroupControls(model);

        final int bottom = height - BOTTOM_MARGIN;
        final int visibleRowCount = Math.max(1, (bottom - listTop()) / ROW_HEIGHT);
        scrollOffset = MathHelper.clamp(scrollOffset, 0, Math.max(0, listModel.size() - visibleRowCount));

        final int end = Math.min(listModel.size(), scrollOffset + visibleRowCount);
        for (int i = scrollOffset; i < end; i++) {
            final ListRow source = listModel.get(i);
            final int rowY = listTop() + (i - scrollOffset) * ROW_HEIGHT;
            if (source.isHeader()) {
                final HeaderRow header = source.header();
                rows.add(ListRow.ofHeader(new HeaderRow(
                    header.group(), header.visibleCount(), rowY
                )));
                continue;
            }
            final RowInfo info = source.waypoint();
            final RowInfo row = new RowInfo(
                info.row(), info.source(),
                rowY,
                info.distance(), info.dimensionText()
            );
            rows.add(ListRow.ofWaypoint(row, source.indented()));
            addRowWidgets(row, source.indented());
        }

        addBottomButtons(model);
        snapshotObservedState();
    }

    /**
     * Orders the search-filtered rows for both tabs: rows grouped under
     * collapsible group headers (core grouping; encounter order = distance
     * order inside a group, groups in the model's display order), then
     * read-only cross-world guest rows as a flat distance-sorted tail on the
     * local tab - they belong to another world's sets, not this store's.
     */
    private List<ListRow> buildListModel(final WaypointListModel model, final List<RowInfo> candidates) {
        final List<RowInfo> owned = new ArrayList<>();
        final List<RowInfo> guests = new ArrayList<>();
        for (final RowInfo row : candidates) {
            (row.row().guest() ? guests : owned).add(row);
        }
        owned.sort((a, b) -> Double.compare(a.distance(), b.distance()));
        guests.sort((a, b) -> Double.compare(a.distance(), b.distance()));
        final List<ListRow> list = new ArrayList<>();
        for (final WaypointListGrouping.Entry<RowInfo> entry : WaypointListGrouping.group(
            model.groupsForDisplay(), owned, row -> row.row().groupKey(), isSearchActive()
        )) {
            if (entry instanceof WaypointListGrouping.GroupHeader<?> header) {
                @SuppressWarnings("unchecked")
                final GroupRef group = ((WaypointListGrouping.GroupHeader<RowInfo>) header).group();
                list.add(ListRow.ofHeader(new HeaderRow(group, header.visibleCount(), 0)));
            } else if (entry instanceof WaypointListGrouping.Member<?> member) {
                @SuppressWarnings("unchecked") final RowInfo row = (RowInfo) member.row();
                list.add(ListRow.ofWaypoint(row, true));
            }
        }
        for (final RowInfo guest : guests) {
            list.add(ListRow.ofWaypoint(guest, false));
        }
        return list;
    }

    private boolean isSearchActive() {
        return !observedSearch.trim().isEmpty();
    }

    private static boolean matchesSearch(final RowInfo row, final String query) {
        return WaypointSearch.matches(
            query, row.name(), row.row().secondaryText(), row.dimensionText(),
            row.row().x(), row.row().y(), row.row().z()
        );
    }

    private List<RowInfo> buildRows(
        final WaypointListModel model,
        final DimensionId currentDimension,
        final double px,
        final double py,
        final double pz
    ) {
        final List<RowInfo> result = new ArrayList<>();
        final WaypointRenderEntry.Source source = tab == Tab.LOCAL
            ? WaypointRenderEntry.Source.LOCAL
            : WaypointRenderEntry.Source.SHARED;
        for (final WaypointRow row : WaypointListFilter.rows(model.waypoints(), currentDimension, dimensionFilter)) {
            if (!matchesGroupFilter(row.groupKey())) {
                continue;
            }
            result.add(new RowInfo(
                row,
                source,
                0,
                distance(row, currentDimension, px, py, pz),
                dimensionLabel(row.dimensionId())
            ));
        }
        // Seed-sibling rows are read-only guests: shown when the model exposes them and no
        // group filter is active, never part of the filter or batch selection.
        if (tab == Tab.LOCAL && groupFilter() == null) {
            for (final WaypointRow guest : WaypointListFilter.rows(
                model.guestWaypoints(), currentDimension, dimensionFilter
            )) {
                result.add(new RowInfo(
                    guest,
                    WaypointRenderEntry.Source.SIBLING,
                    0,
                    distance(guest, currentDimension, px, py, pz),
                    dimensionLabel(guest.dimensionId())
                ));
            }
        }
        return result;
    }

    private boolean matchesGroupFilter(final String groupKey) {
        final GroupRef filter = groupFilter();
        if (filter == null) {
            return true;
        }
        if (filter.isUngrouped()) {
            return groupKey.isEmpty();
        }
        return filter.key().equals(groupKey);
    }

    private GroupRef groupFilter() {
        return tab == Tab.LOCAL ? selectedLocalGroupFilter : selectedSharedGroupFilter;
    }

    private GroupRef moveTarget() {
        return tab == Tab.LOCAL ? localMoveTarget : sharedMoveTarget;
    }

    private List<DimensionId> knownDimensions(final DimensionId currentDimension) {
        final Set<DimensionId> dimensions = new LinkedHashSet<>();
        dimensions.add(currentDimension);
        localModel.waypoints().forEach(row -> dimensions.add(row.dimensionId()));
        if (sharedWaypoints.availability().visible()) {
            sharedModel.waypoints().forEach(row -> dimensions.add(row.dimensionId()));
        }
        return List.copyOf(dimensions);
    }

    private void addDimensionFilter(
        final List<DimensionId> knownDimensions,
        final DimensionId currentDimension
    ) {
        final int buttonWidth = headerControlWidth();
        final List<WaypointDimensionFilter> options = WaypointDimensionFilter.options(
            currentDimension, knownDimensions
        );
        dimensionDropdownX = width / 2 - buttonWidth / 2;
        dimensionDropdownWidth = buttonWidth;
        dimensionDropdownOptions = options;
        normalizeDimensionDropdownState();
        final ButtonWidget button = addDrawableChild(Widgets.button(
            dimensionDropdownX,
            DIMENSION_FILTER_Y,
            buttonWidth,
            TOOLBAR_HEIGHT,
            fitButtonLabel(Text.of(
                Texts.translatable(
                    "confluxmap.screen.waypoints.dimension_filter",
                    dimensionFilterLabel(dimensionFilter, currentDimension),
                    totalRowCount
                ).getString() + " \u25be"
            ), buttonWidth),
            ignored -> toggleDimensionDropdown()
        ));
        button.active = options.size() > 1;
    }

    private void toggleDimensionDropdown() {
        if (dimensionDropdownOptions.size() <= 1) {
            return;
        }
        if (dimensionDropdownOpen) {
            closeDimensionDropdown();
        } else {
            closeGroupDropdown();
            dimensionDropdownOpen = true;
            dimensionDropdownKeyboardIndex = Math.max(
                0, dimensionDropdownOptions.indexOf(dimensionFilter)
            );
            dimensionDropdownScrollOffset = DropdownScroll.ensureVisible(
                dimensionDropdownKeyboardIndex,
                dimensionDropdownOptions.size(),
                dimensionDropdownVisibleRows()
            );
        }
        rebuild();
    }

    private void selectDimensionFilter(final WaypointDimensionFilter selected) {
        dimensionFilter = selected;
        scrollOffset = 0;
        selectedWaypointIds.clear();
        clearPendingActions();
        closeDimensionDropdown();
        closeGroupDropdown();
        rebuild();
    }

    private void closeDimensionDropdown() {
        dimensionDropdownOpen = false;
        dimensionDropdownScrollOffset = 0;
        dimensionDropdownKeyboardIndex = 0;
        draggingDropdownScrollbar = false;
    }

    private void normalizeDimensionDropdownState() {
        if (!dimensionDropdownOpen) {
            return;
        }
        if (dimensionDropdownOptions.size() <= 1) {
            closeDimensionDropdown();
            return;
        }
        dimensionDropdownKeyboardIndex = MathHelper.clamp(
            dimensionDropdownKeyboardIndex, 0, dimensionDropdownOptions.size() - 1
        );
        dimensionDropdownScrollOffset = DropdownScroll.keepVisible(
            dimensionDropdownScrollOffset,
            dimensionDropdownKeyboardIndex,
            dimensionDropdownOptions.size(),
            dimensionDropdownVisibleRows()
        );
    }

    private int dimensionDropdownVisibleRows() {
        final int popupY = DIMENSION_FILTER_Y + TOOLBAR_HEIGHT;
        final int availableHeight = height - BOTTOM_MARGIN - popupY;
        final int rowsByHeight = Math.max(1, availableHeight / DROPDOWN_ROW_HEIGHT);
        return Math.min(
            dimensionDropdownOptions.size(), Math.min(DROPDOWN_MAX_VISIBLE_ROWS, rowsByHeight)
        );
    }

    private DropdownGeometry dimensionDropdownGeometry() {
        if (!dimensionDropdownOpen || dimensionDropdownWidth <= 0) {
            return null;
        }
        return new DropdownGeometry(
            dimensionDropdownX,
            DIMENSION_FILTER_Y,
            dimensionDropdownWidth,
            DIMENSION_FILTER_Y + TOOLBAR_HEIGHT,
            dimensionDropdownVisibleRows(),
            0
        );
    }

    private String dimensionFilterLabel(
        final WaypointDimensionFilter filter,
        final DimensionId currentDimension
    ) {
        return switch (filter.mode()) {
            case CURRENT -> Texts.translatable(
                "confluxmap.screen.waypoints.dimension_current",
                dimensionLabel(currentDimension)
            ).getString();
            case ALL -> Texts.translatable(
                "confluxmap.screen.waypoints.dimension_all"
            ).getString();
            case ONLY -> dimensionLabel(filter.dimension());
        };
    }

    private void addBottomButtons(final WaypointListModel model) {
        final int createWidth = tab == Tab.LOCAL ? bottomActionWidth() : 116;
        final ButtonWidget create = addDrawableChild(Widgets.button(
            contentLeft(),
            height - 24,
            createWidth,
            20,
            fitButtonLabel(Texts.translatable(
                tab == Tab.LOCAL
                    ? "confluxmap.screen.waypoints.create_local"
                    : "confluxmap.screen.waypoints.create_public"
            ), createWidth),
            button -> openCreate(model)
        ));
        final WaypointListModel.Permission createPermission = model.canCreateWaypoint();
        create.active = createPermission.granted();
        setDisabledTooltip(create, createPermission.reasonKey());
        if (tab == Tab.LOCAL) {
            final ButtonWidget importButton = addDrawableChild(Widgets.button(
                contentLeft() + createWidth + GAP,
                height - 24,
                createWidth,
                20,
                fitButtonLabel(Texts.translatable("confluxmap.screen.waypoints.import_open"), createWidth),
                button -> openImport()
            ));
            importButton.active = localStoreWritable();
        }
        addDrawableChild(Widgets.button(
            contentRight() - 80,
            height - 24,
            80,
            20,
            Texts.translatable("confluxmap.screen.waypoint.done"),
            button -> onClose()
        ));
    }

    private boolean localStoreWritable() {
        final WaypointStore store = waypointService.current();
        return store != null && store.persistenceWritable();
    }

    private void snapshotObservedState() {
        lastStateVersion = activeModel().stateVersion();
        lastLocalStore = waypointService.current();
        lastDimension = gameBridge.session().dimension();
    }

    private void addTabs() {
        final SharedWaypointAvailability availability = sharedWaypoints.availability();
        final List<Tab> tabs = availability.visible()
            ? List.of(Tab.LOCAL, Tab.PUBLIC)
            : List.of(Tab.LOCAL);
        final int availableWidth = contentWidth() - GAP * (tabs.size() - 1);
        final int tabWidth = Math.max(80, Math.min(140, availableWidth / tabs.size()));
        final int totalWidth = tabWidth * tabs.size() + GAP * (tabs.size() - 1);
        int x = width / 2 - totalWidth / 2;
        for (final Tab candidate : tabs) {
            final Text label = Texts.translatable(tabKey(candidate));
            final ButtonWidget button = addDrawableChild(Widgets.button(
                x,
                28,
                tabWidth,
                20,
                candidate == tab ? Texts.literal("[" + label.getString() + "]") : label,
                ignored -> selectTab(candidate)
            ));
            button.active = candidate == Tab.LOCAL || availability.enabled();
            if (candidate != Tab.LOCAL && availability.disabledByServer()) {
                setDisabledTooltip(button, "confluxmap.shared_waypoints.disabled_by_server");
            }
            x += tabWidth + GAP;
        }
    }

    private void addGroupControls(final WaypointListModel model) {
        if (narrowToolbar()) {
            addNarrowGroupControls(model);
            return;
        }
        final int selectWidth = 84;
        final int firstRowWidth = headerControlWidth();
        final int filterWidth = firstRowWidth - selectWidth - GAP;
        final int x = width / 2 - firstRowWidth / 2;
        addGroupDropdownButton(GroupDropdown.FILTER, x, TOOLBAR_Y, filterWidth, filterLabel());
        addSelectAllButton(x + filterWidth + GAP, TOOLBAR_Y, selectWidth, model);

        if (selectedWaypointIds.isEmpty()) {
            return;
        }

        final int secondY = TOOLBAR_Y + TOOLBAR_HEIGHT + GAP;
        final int clearWidth = 84;
        final int moveWidth = 92;
        final int secondRowWidth = headerControlWidth();
        final int targetWidth = secondRowWidth - clearWidth - moveWidth - GAP * 2;
        final int secondRowX = width / 2 - secondRowWidth / 2;
        final ButtonWidget clearSelection = addDrawableChild(Widgets.button(
            secondRowX, secondY, clearWidth, TOOLBAR_HEIGHT,
            fitButtonLabel(selectionClearLabel(), clearWidth),
            button -> clearSelection()
        ));
        // Clearing only drops screen state, so the shared tab keeps it usable regardless
        // of server policy; the local tab ties it to store writability as before.
        clearSelection.active = tab == Tab.PUBLIC || model.canCreateWaypoint().granted();
        addGroupDropdownButton(
            GroupDropdown.MOVE_TARGET,
            secondRowX + clearWidth + GAP,
            secondY,
            targetWidth,
            moveTargetLabel()
        );
        final ButtonWidget move = addDrawableChild(Widgets.button(
            secondRowX + clearWidth + GAP + targetWidth + GAP,
            secondY,
            moveWidth,
            TOOLBAR_HEIGHT,
            fitButtonLabel(
                Texts.translatable("confluxmap.screen.waypoints.selection_move", selectedWaypointIds.size()),
                moveWidth
            ),
            button -> moveSelection(model)
        ));
        move.active = batchMoveReady() && !selectedWaypointIds.isEmpty()
            && (tab == Tab.PUBLIC || model.groupsForDisplay().size() > 1);
    }

    private void addNarrowGroupControls(final WaypointListModel model) {
        final int selectWidth = Math.min(84, Math.max(44, contentWidth() / 3));
        final int filterWidth = Math.max(44, contentWidth() - selectWidth - GAP);
        addGroupDropdownButton(
            GroupDropdown.FILTER, contentLeft(), TOOLBAR_Y, filterWidth, filterLabel()
        );
        addSelectAllButton(contentLeft() + filterWidth + GAP, TOOLBAR_Y, selectWidth, model);

        if (selectedWaypointIds.isEmpty()) {
            return;
        }

        final int firstColumnWidth = Math.max(1, (contentWidth() - GAP * 2) / 3);
        final int batchActionsY = TOOLBAR_Y + TOOLBAR_HEIGHT + GAP;
        int x = contentLeft();
        final ButtonWidget clearSelection = addDrawableChild(Widgets.button(
            x, batchActionsY, firstColumnWidth, TOOLBAR_HEIGHT,
            fitButtonLabel(selectionClearLabel(), firstColumnWidth),
            button -> clearSelection()
        ));
        clearSelection.active = tab == Tab.PUBLIC || model.canCreateWaypoint().granted();
        x += firstColumnWidth + GAP;
        addGroupDropdownButton(
            GroupDropdown.MOVE_TARGET,
            x,
            batchActionsY,
            firstColumnWidth,
            moveTargetLabel()
        );
        x += firstColumnWidth + GAP;
        final ButtonWidget move = addDrawableChild(Widgets.button(
            x, batchActionsY, Math.max(1, contentRight() - x), TOOLBAR_HEIGHT,
            fitButtonLabel(
                Texts.translatable("confluxmap.screen.waypoints.selection_move", selectedWaypointIds.size()),
                Math.max(1, contentRight() - x)
            ),
            button -> moveSelection(model)
        ));
        move.active = batchMoveReady() && !selectedWaypointIds.isEmpty()
            && (tab == Tab.PUBLIC || model.groupsForDisplay().size() > 1);
    }

    private void addSelectAllButton(final int x, final int y, final int buttonWidth, final WaypointListModel model) {
        final List<UUID> selectable = selectableIds(model);
        final boolean allSelected = !selectable.isEmpty() && selectedWaypointIds.containsAll(selectable);
        final ButtonWidget selectAll = addDrawableChild(Widgets.button(
            x,
            y,
            buttonWidth,
            TOOLBAR_HEIGHT,
            fitButtonLabel(Texts.translatable(
                allSelected
                    ? "confluxmap.screen.waypoints.selection_clear"
                    : "confluxmap.screen.waypoints.selection_all"
            ), buttonWidth),
            button -> toggleSelectAll(model)
        ));
        selectAll.active = batchMoveReady() && !selectable.isEmpty();
        setDisabledTooltip(selectAll, selectionDisabledReasonKey(model, selectable));
    }

    /** Shared rows move through group mutations on the wire; the local store always supports them. */
    private boolean batchMoveReady() {
        return tab == Tab.LOCAL || sharedWaypoints.supportsGroupMutations();
    }

    private String batchMoveDisabledReasonKey() {
        return batchMoveReady() ? null : "confluxmap.shared_waypoints.group_mutations_unsupported";
    }

    /** Rows the active tab allows into a batch: movable per the model, never guests. */
    private List<UUID> selectableIds(final WaypointListModel model) {
        return filteredOwnedRows.stream()
            .filter(row -> model.canMoveWaypoint(row).granted())
            .map(WaypointRow::id)
            .toList();
    }

    private String selectionDisabledReasonKey(final WaypointListModel model, final List<UUID> selectable) {
        final String unsupported = batchMoveDisabledReasonKey();
        if (unsupported != null) {
            return unsupported;
        }
        if (!selectable.isEmpty() || filteredOwnedRows.isEmpty()) {
            return null;
        }
        for (final WaypointRow row : filteredOwnedRows) {
            final WaypointListModel.Permission permission = model.canMoveWaypoint(row);
            if (!permission.granted()) {
                return permission.reasonKey();
            }
        }
        return null;
    }

    private void toggleSelectAll(final WaypointListModel model) {
        if (!batchMoveReady()) {
            return;
        }
        final List<UUID> selectable = selectableIds(model);
        if (!selectable.isEmpty() && selectedWaypointIds.containsAll(selectable)) {
            selectedWaypointIds.removeAll(selectable);
        } else {
            selectedWaypointIds.addAll(selectable);
        }
        clearPendingActions();
        rebuild();
    }

    /** One model transaction: the local store moves atomically, shared rows go as one batch request. */
    private void moveSelection(final WaypointListModel model) {
        if (selectedWaypointIds.isEmpty() || !batchMoveReady()) {
            return;
        }
        final List<WaypointRow> selected = new ArrayList<>();
        for (final WaypointRow row : model.waypoints()) {
            if (selectedWaypointIds.contains(row.id())) {
                selected.add(row);
            }
        }
        final GroupRef target = model.groupByKey(moveTarget().key());
        if (target == null || selected.isEmpty()) {
            return;
        }
        if (model.moveWaypoints(selected, target)) {
            selectedWaypointIds.clear();
        }
        clearPendingActions();
        rebuild();
    }

    private void addGroupDropdownButton(
        final GroupDropdown dropdown,
        final int x,
        final int y,
        final int buttonWidth,
        final Text label
    ) {
        final ButtonWidget button = addDrawableChild(Widgets.button(
            x,
            y,
            buttonWidth,
            TOOLBAR_HEIGHT,
            fitButtonLabel(label, buttonWidth),
            ignored -> toggleGroupDropdown(dropdown)
        ));
        if (dropdown == GroupDropdown.FILTER) {
            filterDropdownX = x;
            filterDropdownY = y;
            filterDropdownWidth = buttonWidth;
            button.active = tab != Tab.PUBLIC || sharedWaypoints.availability().ready();
        } else {
            moveDropdownX = x;
            moveDropdownY = y;
            moveDropdownWidth = buttonWidth;
            button.active = tab == Tab.PUBLIC
                ? batchMoveReady() && !selectedWaypointIds.isEmpty()
                : activeModel().groupsForDisplay().size() > 1;
            setDisabledTooltip(button, tab == Tab.PUBLIC ? batchMoveDisabledReasonKey() : null);
        }
    }

    private void toggleGroupDropdown(final GroupDropdown dropdown) {
        if (openGroupDropdown == dropdown) {
            closeGroupDropdown();
        } else {
            closeDimensionDropdown();
            openGroupDropdown = dropdown;
            dropdownKeyboardIndex = Math.max(0, groupDropdownOptions().indexOf(
                dropdown == GroupDropdown.FILTER ? groupFilter() : moveTarget()
            ));
            dropdownScrollOffset = DropdownScroll.ensureVisible(
                dropdownKeyboardIndex,
                groupDropdownOptions().size(),
                dropdownVisibleRows()
            );
            draggingDropdownScrollbar = false;
        }
        rebuild();
    }

    private void closeGroupDropdown() {
        openGroupDropdown = null;
        dropdownScrollOffset = 0;
        dropdownKeyboardIndex = 0;
        draggingDropdownScrollbar = false;
    }

    /**
     * Dropdown options of the active tab: the move target lists every group
     * including ungrouped, the filter additionally offers "All" as a null head.
     */
    private List<GroupRef> groupDropdownOptions() {
        final List<GroupRef> options = new ArrayList<>();
        if (openGroupDropdown == GroupDropdown.FILTER) {
            options.add(null);
        }
        options.addAll(activeModel().groupsForDisplay());
        return options;
    }

    private int dropdownVisibleRows() {
        final int popupY = dropdownTriggerY() + TOOLBAR_HEIGHT;
        final int actionHeight = openGroupDropdown == GroupDropdown.FILTER ? DROPDOWN_ACTION_HEIGHT : 0;
        final int availableHeight = height - BOTTOM_MARGIN - popupY - actionHeight;
        final int rowsByHeight = Math.max(1, availableHeight / DROPDOWN_ROW_HEIGHT);
        return Math.min(groupDropdownOptions().size(), Math.min(DROPDOWN_MAX_VISIBLE_ROWS, rowsByHeight));
    }

    private DropdownGeometry groupDropdownGeometry() {
        if (openGroupDropdown == null) {
            return null;
        }
        final int x = openGroupDropdown == GroupDropdown.FILTER ? filterDropdownX : moveDropdownX;
        final int dropdownWidth = openGroupDropdown == GroupDropdown.FILTER
            ? filterDropdownWidth
            : moveDropdownWidth;
        if (dropdownWidth <= 0) {
            return null;
        }
        final int actionHeight = openGroupDropdown == GroupDropdown.FILTER ? DROPDOWN_ACTION_HEIGHT : 0;
        return new DropdownGeometry(
            x, dropdownTriggerY(), dropdownWidth, dropdownTriggerY() + TOOLBAR_HEIGHT,
            dropdownVisibleRows(), actionHeight
        );
    }

    private int dropdownTriggerY() {
        return openGroupDropdown == GroupDropdown.FILTER ? filterDropdownY : moveDropdownY;
    }

    private void selectGroupDropdownOption(final GroupRef option) {
        if (openGroupDropdown == GroupDropdown.FILTER) {
            if (tab == Tab.LOCAL) {
                selectedLocalGroupFilter = option;
            } else {
                selectedSharedGroupFilter = option;
            }
            scrollOffset = 0;
            selectedWaypointIds.clear();
        } else if (tab == Tab.LOCAL) {
            localMoveTarget = option == null ? GroupRef.ungrouped(false) : option;
        } else {
            sharedMoveTarget = option == null ? GroupRef.ungrouped(false) : option;
        }
        clearPendingActions();
        closeGroupDropdown();
        rebuild();
    }

    private void normalizeGroupState(final WaypointListModel model) {
        final List<GroupRef> groups = model.groupsForDisplay();
        if (tab == Tab.LOCAL) {
            selectedLocalGroupFilter = resolveGroup(groups, selectedLocalGroupFilter);
            localMoveTarget = resolveGroup(groups, localMoveTarget);
            if (localMoveTarget == null) {
                localMoveTarget = GroupRef.ungrouped(false);
            }
        } else {
            selectedSharedGroupFilter = resolveGroup(groups, selectedSharedGroupFilter);
            sharedMoveTarget = resolveGroup(groups, sharedMoveTarget);
            if (sharedMoveTarget == null) {
                sharedMoveTarget = GroupRef.ungrouped(false);
            }
        }
        if (openGroupDropdown == null) {
            return;
        }
        final List<GroupRef> options = groupDropdownOptions();
        if (options.isEmpty()) {
            closeGroupDropdown();
            return;
        }
        dropdownKeyboardIndex = MathHelper.clamp(dropdownKeyboardIndex, 0, options.size() - 1);
        dropdownScrollOffset = DropdownScroll.keepVisible(
            dropdownScrollOffset,
            dropdownKeyboardIndex,
            options.size(),
            dropdownVisibleRows()
        );
    }

    private static GroupRef resolveGroup(final List<GroupRef> groups, final GroupRef candidate) {
        if (candidate == null) {
            return null;
        }
        for (final GroupRef group : groups) {
            if (group.key().equals(candidate.key())) {
                return group;
            }
        }
        return null;
    }

    /** The real group behind the filter, when one is selected; null for "All" and ungrouped. */
    private GroupRef selectedGroup() {
        final GroupRef filter = groupFilter();
        return filter == null || filter.isUngrouped() ? null : filter;
    }

    private void activateGroupDropdownAction(final int actionIndex) {
        if (!groupDropdownActionActive(actionIndex)) {
            return;
        }
        closeGroupDropdown();
        if (actionIndex == 0) {
            openGroupNamingScreen(null);
        } else if (actionIndex == 1) {
            openGroupNamingScreen(selectedGroup());
        } else {
            openGroupDeleteConfirm(selectedGroup());
        }
    }

    private boolean groupDropdownActionActive(final int actionIndex) {
        if (!batchMoveReady()) {
            return false;
        }
        final WaypointListModel model = activeModel();
        if (actionIndex == 0) {
            return model.canCreateGroup().granted();
        }
        final GroupRef selected = selectedGroup();
        return selected != null && (actionIndex == 1
            ? model.canRenameGroup(selected).granted()
            : model.canDeleteGroup(selected).granted());
    }

    private Text groupDropdownActionLabel(final int actionIndex) {
        return switch (actionIndex) {
            case 0 -> Texts.translatable(tab == Tab.LOCAL
                ? "confluxmap.screen.waypoints.set_new"
                : "confluxmap.screen.waypoints.group_new");
            case 1 -> Texts.translatable(tab == Tab.LOCAL
                ? "confluxmap.screen.waypoints.set_rename"
                : "confluxmap.screen.waypoints.group_rename");
            default -> Texts.translatable(
                tab == Tab.LOCAL
                    ? "confluxmap.screen.waypoints.set_delete"
                    : "confluxmap.screen.waypoints.group_delete",
                selectedGroupMemberCount()
            );
        };
    }

    private int selectedGroupMemberCount() {
        final GroupRef selected = selectedGroup();
        return selected == null ? 0 : activeModel().memberCount(selected.key());
    }

    private void openGroupNamingScreen(final GroupRef existingGroup) {
        MinecraftAccess.setScreen(MinecraftClient.getInstance(), new WaypointGroupNamingScreen(
            this,
            activeModel(),
            this::batchMoveReady,
            existingGroup,
            name -> {
                if (tab == Tab.LOCAL) {
                    final GroupRef named = activeModel().groupByKey(name);
                    selectedLocalGroupFilter = named;
                    localMoveTarget = named == null ? GroupRef.ungrouped(false) : named;
                    selectedWaypointIds.clear();
                }
                clearPendingActions();
            }
        ));
    }

    private void openGroupDeleteConfirm(final GroupRef group) {
        if (group == null || !groupDropdownActionActive(2)) {
            return;
        }
        MinecraftAccess.setScreen(MinecraftClient.getInstance(), new WaypointGroupDeleteConfirmScreen(
            this,
            activeModel(),
            this::batchMoveReady,
            group,
            () -> {
                if (tab == Tab.LOCAL) {
                    selectedLocalGroupFilter = null;
                    localMoveTarget = GroupRef.ungrouped(false);
                } else {
                    selectedSharedGroupFilter = null;
                    sharedMoveTarget = GroupRef.ungrouped(false);
                }
                selectedWaypointIds.clear();
                scrollOffset = 0;
                clearPendingActions();
            }
        ));
    }

    private void addRowWidgets(final RowInfo info, final boolean indented) {
        final WaypointListModel model = activeModel();
        final WaypointRow row = info.row();
        final int selectionY = info.y() + 3;
        final int actionY = selectionY;
        final WaypointRowActionLayout actions = rowActionLayout();
        final int leftInset = indented ? SET_MEMBER_INDENT : 0;

        if (!row.guest()) {
            final WaypointListModel.Permission move = model.canMoveWaypoint(row);
            final ButtonWidget selected = addDrawableChild(Widgets.button(
                contentLeft() + ROW_PADDING + leftInset,
                selectionY,
                CHECK_WIDTH,
                20,
                Text.of(selectedWaypointIds.contains(row.id()) ? "\u2713" : ""),
                button -> toggleSelectedRow(model, row)
            ));
            selected.active = move.granted() && batchMoveReady();
            setDisabledTooltip(selected, batchMoveDisabledReasonKey() != null
                ? batchMoveDisabledReasonKey()
                : move.reasonKey());

            final WaypointListModel.Permission toggleHidden = model.canToggleHidden(row);
            final ButtonWidget visibility = addIconAction(
                actions,
                0,
                actionY,
                Texts.translatable(
                    row.hidden()
                        ? "confluxmap.screen.waypoints.show"
                        : "confluxmap.screen.waypoints.hide"
                ),
                row.hidden() ? HIDDEN_ICON : VISIBLE_ICON,
                button -> toggleHidden(model, row)
            );
            visibility.active = toggleHidden.granted();
            setDisabledTooltip(visibility, toggleHidden.reasonKey());
            addIconAction(
                actions,
                1,
                actionY,
                Texts.translatable("confluxmap.screen.waypoints.share"),
                SHARE_ICON,
                button -> openShare(row)
            );
            final WaypointListModel.Permission edit = model.canEditWaypoint(row);
            final ButtonWidget editButton = addDrawableChild(Widgets.button(
                actions.x(2),
                actionY,
                actions.width(2),
                20,
                fitButtonLabel(
                    Texts.translatable("confluxmap.screen.waypoints.edit"),
                    actions.width(2)
                ),
                button -> openEdit(model, row)
            ));
            editButton.active = edit.granted();
            setDisabledTooltip(editButton, edit.reasonKey());
            final WaypointListModel.Permission delete = model.canDeleteWaypoint(row);
            final ButtonWidget deleteButton = addDrawableChild(Widgets.button(
                actions.x(3),
                actionY,
                actions.width(3),
                20,
                fitButtonLabel(Texts.translatable(
                    row.id().equals(pendingDeleteId)
                        ? "confluxmap.screen.waypoints.confirm"
                        : "confluxmap.screen.waypoints.delete"
                ), actions.width(3)),
                button -> delete(model, info)
            ));
            deleteButton.active = delete.granted();
            setDisabledTooltip(deleteButton, delete.reasonKey());
        }
        // Sibling rows are read-only guests from a seed-sibling world: no selection, eye,
        // share, edit or delete - locate and teleport stay, since the coordinates are
        // real terrain in this world too.
        addDrawableChild(Widgets.button(
            actions.x(4),
            actionY,
            actions.width(4),
            20,
            fitButtonLabel(
                Texts.translatable("confluxmap.screen.waypoints.locate"),
                actions.width(4)
            ),
            ignored -> locate(info)
        ));
        final TeleportCommandAccess.Result access = teleportAccess(info);
        final ButtonWidget teleport = addDrawableChild(Widgets.button(
            actions.x(5),
            actionY,
            actions.width(5),
            20,
            fitButtonLabel(
                Texts.translatable("confluxmap.screen.waypoints.teleport"),
                actions.width(5)
            ),
            ignored -> teleport(info)
        ));
        teleport.active = access.available();
        setDisabledTooltip(teleport, access.reasonKey());
    }

    private ButtonWidget addIconAction(
        final WaypointRowActionLayout actions,
        final int index,
        final int y,
        final Text label,
        final Identifier icon,
        final ButtonWidget.PressAction action
    ) {
        final ButtonWidget button = addDrawableChild(new ConfluxTextButton(
            actions.x(index), y, actions.width(index), 20, label, icon, action
        ));
        iconActions.add(button);
        return button;
    }

    private TeleportCommandAccess.Result teleportAccess(final RowInfo row) {
        final var entry = row.renderEntry();
        final var live = gameBridge.session();
        return TeleportCommandAccess.evaluate(
            MinecraftClient.getInstance(),
            config.teleportCommand,
            live,
            live.world(),
            entry.dimensionId(),
            true
        );
    }

    private void locate(final RowInfo row) {
        final var entry = row.renderEntry();
        MinecraftAccess.setScreen(
            MinecraftClient.getInstance(),
            FullscreenMapScreen.focusedOnWaypoint(entry.dimensionId(), entry.x(), entry.z())
        );
    }

    private void teleport(final RowInfo row) {
        final TeleportCommandAccess.Result access = teleportAccess(row);
        if (!access.available()) {
            return;
        }
        final var entry = row.renderEntry();
        groundTeleport.teleportExact(
            entry.x(), entry.y(), entry.z(), entry.dimensionId(), gameBridge.session().world()
        );
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
        closeDimensionDropdown();
        closeGroupDropdown();
        rebuild();
    }

    private void openCreate(final WaypointListModel model) {
        if (!model.canCreateWaypoint().granted()) {
            return;
        }
        final Optional<PlayerView> viewpoint = gameBridge.viewpoint();
        final DimensionId dimension = gameBridge.session().dimension();
        final double x = Math.floor(viewpoint.map(PlayerView::x).orElse(0.0));
        final double y = Math.floor(viewpoint.map(PlayerView::y).orElse(64.0));
        final double z = Math.floor(viewpoint.map(PlayerView::z).orElse(0.0));
        final GroupRef filter = groupFilter();
        MinecraftAccess.setScreen(MinecraftClient.getInstance(),
            tab == Tab.PUBLIC
                ? WaypointEditScreen.forPublicCreate(this, dimension, x, y, z)
                : WaypointEditScreen.forCreate(
                    this, dimension, x, y, z,
                    filter == null ? GroupRef.UNGROUPED_KEY : filter.key()
                )
        );
    }

    private void openEdit(final WaypointListModel model, final WaypointRow row) {
        if (!model.canEditWaypoint(row).granted()) {
            return;
        }
        if (tab == Tab.PUBLIC) {
            MinecraftAccess.setScreen(
                MinecraftClient.getInstance(), WaypointEditScreen.forPublicEdit(this, row)
            );
            return;
        }
        // The local form prefills from the store waypoint - creation time and the
        // cross-dimension Y override are fields the unified row does not carry.
        final Waypoint waypoint = findLocalWaypoint(row.id());
        if (waypoint != null) {
            MinecraftAccess.setScreen(MinecraftClient.getInstance(), WaypointEditScreen.forEdit(this, waypoint));
        }
    }

    private void openImport() {
        final WaypointStore store = waypointService.current();
        if (store == null || !store.persistenceWritable()) {
            return;
        }
        MinecraftAccess.setScreen(MinecraftClient.getInstance(), new WaypointImportScreen(this, store));
    }

    private void openShare(final WaypointRow row) {
        if (row.guest()) {
            return;
        }
        if (tab == Tab.PUBLIC) {
            MinecraftAccess.setScreen(
                MinecraftClient.getInstance(),
                WaypointShareConfirmScreen.forSharedWaypoint(this, row)
            );
            return;
        }
        // Publishing a local row carries the waypoint's personal cross-dimension overrides
        // into the pending create; the row projection does not include the Y value, so the
        // share boundary still receives the store's own waypoint.
        final Waypoint waypoint = findLocalWaypoint(row.id());
        if (waypoint == null) {
            return;
        }
        MinecraftAccess.setScreen(MinecraftClient.getInstance(), new WaypointShareConfirmScreen(
            this, waypoint, WaypointShareConfirmScreen.Target.CHAT
        ));
    }

    private Waypoint findLocalWaypoint(final UUID id) {
        final WaypointStore store = waypointService.current();
        if (store == null) {
            return null;
        }
        for (final Waypoint waypoint : store.list()) {
            if (waypoint.id.equals(id)) {
                return waypoint;
            }
        }
        return null;
    }

    /** Folds or unfolds one group; the model persists the state with its backing store. */
    private void toggleGroupCollapsed(final GroupRef group) {
        activeModel().toggleGroupCollapsed(group);
        rebuild();
    }

    private void toggleHidden(final WaypointListModel model, final WaypointRow row) {
        if (!model.canToggleHidden(row).granted()) {
            return;
        }
        model.toggleWaypointHidden(row);
        clearPendingActions();
        rebuild();
    }

    private void delete(final WaypointListModel model, final RowInfo info) {
        final WaypointRow row = info.row();
        if (row.guest() || !model.canDeleteWaypoint(row).granted()) {
            return;
        }
        if (!row.id().equals(pendingDeleteId)) {
            pendingDeleteId = row.id();
            rebuild();
            return;
        }
        model.deleteWaypoint(row);
        selectedWaypointIds.remove(row.id());
        pendingDeleteId = null;
        rebuild();
    }

    private void toggleSelectedRow(final WaypointListModel model, final WaypointRow row) {
        if (!model.canMoveWaypoint(row).granted() || !batchMoveReady()) {
            return;
        }
        if (!selectedWaypointIds.add(row.id())) {
            selectedWaypointIds.remove(row.id());
        }
        clearPendingActions();
        rebuild();
    }

    private void clearSelection() {
        selectedWaypointIds.clear();
        clearPendingActions();
        closeGroupDropdown();
        rebuild();
    }

    private void clearPendingActions() {
        pendingDeleteId = null;
    }

    private void resetLocalUiState() {
        selectedLocalGroupFilter = null;
        dimensionFilter = WaypointDimensionFilter.current();
        localMoveTarget = GroupRef.ungrouped(false);
        selectedWaypointIds.clear();
        pendingDeleteId = null;
        scrollOffset = 0;
        closeDimensionDropdown();
        closeGroupDropdown();
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
        final DropdownGeometry dimensionDropdown = dimensionDropdownGeometry();
        if (amount != 0 && dimensionDropdown != null
            && (dimensionDropdown.containsPopup(mouseX, mouseY)
                || dimensionDropdown.containsTrigger(mouseX, mouseY))) {
            dimensionDropdownScrollOffset = DropdownScroll.afterWheel(
                dimensionDropdownScrollOffset,
                amount,
                dimensionDropdownOptions.size(),
                dimensionDropdown.visibleRows()
            );
            return true;
        }
        final DropdownGeometry groupDropdown = groupDropdownGeometry();
        if (amount != 0 && groupDropdown != null
            && (groupDropdown.containsPopup(mouseX, mouseY)
                || groupDropdown.containsTrigger(mouseX, mouseY))) {
            dropdownScrollOffset = DropdownScroll.afterWheel(
                dropdownScrollOffset,
                amount,
                groupDropdownOptions().size(),
                groupDropdown.visibleRows()
            );
            return true;
        }
        final boolean overList = mouseX >= contentLeft() && mouseX <= contentRight() + 6
            && mouseY >= listTop() && mouseY <= height - BOTTOM_MARGIN;
        if (amount != 0 && overList) {
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
        final DropdownGeometry dimensionDropdown = dimensionDropdownGeometry();
        if (button == MouseButtons.LEFT && dimensionDropdown != null) {
            if (dimensionDropdown.containsPopup(mouseX, mouseY)) {
                if (dimensionDropdownOptions.size() > dimensionDropdown.visibleRows()
                    && mouseX >= dimensionDropdown.x() + dimensionDropdown.width()
                        - DROPDOWN_SCROLLBAR_WIDTH) {
                    draggingDropdownScrollbar = true;
                    dimensionDropdownScrollOffset = dropdownScrollFromMouse(
                        mouseY,
                        dimensionDropdown,
                        dimensionDropdownOptions.size()
                    );
                    return true;
                }
                final int visibleIndex = (int) (
                    (mouseY - dimensionDropdown.popupY()) / DROPDOWN_ROW_HEIGHT
                );
                final int optionIndex = dimensionDropdownScrollOffset + visibleIndex;
                if (optionIndex >= 0 && optionIndex < dimensionDropdownOptions.size()) {
                    selectDimensionFilter(dimensionDropdownOptions.get(optionIndex));
                }
                return true;
            }
            if (!dimensionDropdown.containsTrigger(mouseX, mouseY)) {
                closeDimensionDropdown();
            }
        }
        final DropdownGeometry groupDropdown = groupDropdownGeometry();
        if (button == MouseButtons.LEFT && groupDropdown != null) {
            if (groupDropdown.containsPopup(mouseX, mouseY)) {
                final List<GroupRef> options = groupDropdownOptions();
                final int optionBottom = groupDropdown.popupY() + groupDropdown.optionHeight();
                if (mouseY < optionBottom
                    && options.size() > groupDropdown.visibleRows()
                    && mouseX >= groupDropdown.x() + groupDropdown.width() - DROPDOWN_SCROLLBAR_WIDTH) {
                    draggingDropdownScrollbar = true;
                    dropdownScrollOffset = dropdownScrollFromMouse(
                        mouseY,
                        groupDropdown,
                        options.size()
                    );
                    return true;
                }
                if (mouseY >= optionBottom && groupDropdown.actionHeight() > 0) {
                    activateGroupDropdownAction(dropdownActionAt(mouseX, groupDropdown));
                    return true;
                }
                final int visibleIndex = (int) ((mouseY - groupDropdown.popupY()) / DROPDOWN_ROW_HEIGHT);
                final int optionIndex = dropdownScrollOffset + visibleIndex;
                if (optionIndex >= 0 && optionIndex < options.size()) {
                    selectGroupDropdownOption(options.get(optionIndex));
                }
                return true;
            }
            if (!groupDropdown.containsTrigger(mouseX, mouseY)) {
                closeGroupDropdown();
            }
        }
        if (button == MouseButtons.LEFT) {
            for (final ListRow row : rows) {
                if (row.isHeader()
                    && mouseX >= contentLeft() && mouseX <= contentRight()
                    && mouseY >= row.y() && mouseY < row.y() + ROW_HEIGHT) {
                    toggleGroupCollapsed(row.header().group());
                    return true;
                }
            }
        }
        //#if MC>=12109
        //$$ if (super.mouseClicked(click, doubledClick)) {
        //#else
        if (super.mouseClicked(mouseX, mouseY, button)) {
        //#endif
            return true;
        }
        return false;
    }

    @Override
    //#if MC>=12109
    //$$ public boolean mouseDragged(
    //$$     final Click click,
    //$$     final double deltaX,
    //$$     final double deltaY
    //$$ ) {
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
        if (button == MouseButtons.LEFT && draggingDropdownScrollbar) {
            final DropdownGeometry dimensionDropdown = dimensionDropdownGeometry();
            if (dimensionDropdown != null) {
                dimensionDropdownScrollOffset = dropdownScrollFromMouse(
                    mouseY,
                    dimensionDropdown,
                    dimensionDropdownOptions.size()
                );
                return true;
            }
            final DropdownGeometry groupDropdown = groupDropdownGeometry();
            if (groupDropdown != null) {
                dropdownScrollOffset = dropdownScrollFromMouse(
                    mouseY,
                    groupDropdown,
                    groupDropdownOptions().size()
                );
                return true;
            }
            draggingDropdownScrollbar = false;
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
        if (button == MouseButtons.LEFT && draggingDropdownScrollbar) {
            draggingDropdownScrollbar = false;
            return true;
        }
        //#if MC>=12109
        //$$ return super.mouseReleased(click);
        //#else
        return super.mouseReleased(mouseX, mouseY, button);
        //#endif
    }

    @Override
    //#if MC>=12109
    //$$ public boolean keyPressed(final KeyInput input) {
    //$$     final int keyCode = input.key();
    //#else
    public boolean keyPressed(final int keyCode, final int scanCode, final int modifiers) {
    //#endif
        if (dimensionDropdownOpen) {
            if (keyCode == 256) {
                closeDimensionDropdown();
                rebuild();
                return true;
            }
            if (!dimensionDropdownOptions.isEmpty()
                && (keyCode == 264 || keyCode == 265 || keyCode == 268 || keyCode == 269)) {
                dimensionDropdownKeyboardIndex = switch (keyCode) {
                    case 264 -> Math.min(
                        dimensionDropdownOptions.size() - 1,
                        dimensionDropdownKeyboardIndex + 1
                    );
                    case 265 -> Math.max(0, dimensionDropdownKeyboardIndex - 1);
                    case 268 -> 0;
                    case 269 -> dimensionDropdownOptions.size() - 1;
                    default -> dimensionDropdownKeyboardIndex;
                };
                dimensionDropdownScrollOffset = DropdownScroll.keepVisible(
                    dimensionDropdownScrollOffset,
                    dimensionDropdownKeyboardIndex,
                    dimensionDropdownOptions.size(),
                    dimensionDropdownVisibleRows()
                );
                return true;
            }
            if (!dimensionDropdownOptions.isEmpty() && (keyCode == 257 || keyCode == 335)) {
                selectDimensionFilter(
                    dimensionDropdownOptions.get(dimensionDropdownKeyboardIndex)
                );
                return true;
            }
        }
        if (openGroupDropdown != null) {
            final List<GroupRef> options = groupDropdownOptions();
            if (keyCode == 256) {
                closeGroupDropdown();
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
                    dropdownVisibleRows()
                );
                return true;
            }
            if (!options.isEmpty() && (keyCode == 257 || keyCode == 335)) {
                selectGroupDropdownOption(options.get(dropdownKeyboardIndex));
                return true;
            }
        }
        //#if MC>=12109
        //$$ return super.keyPressed(input);
        //#else
        return super.keyPressed(keyCode, scanCode, modifiers);
        //#endif
    }

    private int dropdownActionAt(final double mouseX, final DropdownGeometry dropdown) {
        final double relativeX = MathHelper.clamp(mouseX - dropdown.x(), 0.0, dropdown.width() - 1.0);
        return Math.min(2, (int) (relativeX * 3.0 / dropdown.width()));
    }

    private int dropdownScrollFromMouse(
        final double mouseY,
        final DropdownGeometry dropdown,
        final int optionCount
    ) {
        // The track deliberately spans only the option rows, never the action strip below them.
        final int trackTop = dropdown.popupY() + 1;
        final int trackHeight = Math.max(1, dropdown.optionHeight() - 2);
        final int thumbHeight = dropdownThumbHeight(trackHeight, optionCount, dropdown.visibleRows());
        return DropdownScroll.fromThumbPosition(
            mouseY,
            trackTop,
            trackHeight,
            thumbHeight,
            optionCount,
            dropdown.visibleRows()
        );
    }

    @Override
    protected void renderContents(final GuiDraw draw, final int mouseX, final int mouseY, final float tickDelta) {
        final MatrixStack matrices = draw.matrices();
        draw.renderBackground(this, mouseX, mouseY, tickDelta);
        final String title = getTitle().getString();
        draw.drawTextWithShadow(
            this.textRenderer, title, width / 2f - this.textRenderer.getWidth(title) / 2f, 10, 0xFFFFFFFF
        );

        final boolean compact = compactRows();
        final boolean searchActive = isSearchActive();
        for (final ListRow row : rows) {
            final boolean hovered = mouseX >= contentLeft() && mouseX <= contentRight()
                && mouseY >= row.y() && mouseY < row.y() + ROW_HEIGHT;
            if (row.isHeader()) {
                renderHeaderRow(draw, matrices, row.header(), hovered, searchActive);
                continue;
            }
            final RowInfo info = row.waypoint();
            GuiShapes.fillRect(
                matrices,
                contentLeft(),
                info.y() + 1,
                contentWidth(),
                ROW_HEIGHT - 2,
                hovered ? 0x55333333 : 0x33202020
            );
            final int markerLeft = markerX(row.indented());
            WaypointMarkerRenderer.draw(
                draw,
                this.textRenderer,
                info.renderEntry(),
                markerLeft + MARKER_SIZE / 2f,
                info.y() + 13f,
                MARKER_SIZE / 2f - 1f,
                1f,
                hovered,
                WaypointVerticalRelation.NONE
            );
            final int maxNameWidth = Math.max(24, nameRight() - nameX(row.indented()));
            final String name = this.textRenderer.trimToWidth(info.name(), maxNameWidth);
            draw.drawTextWithShadow(this.textRenderer, name, nameX(row.indented()), info.y() + 4, 0xFFFFFFFF);
            // The preprocessor's method mapping for this call misses chained accessor
            // arguments, so keep the argument a plain local like every other call site.
            final String secondaryText = info.row().secondaryText();
            final String secondary = this.textRenderer.trimToWidth(secondaryText, maxNameWidth);
            draw.drawTextWithShadow(this.textRenderer, secondary, nameX(row.indented()), info.y() + 15, 0xFFAAAAAA);
            if (!compact) {
                final String distance = this.textRenderer.trimToWidth(formatDistance(info.distance()), DIST_WIDTH);
                draw.drawTextWithShadow(this.textRenderer, distance, distanceX(), info.y() + 10, 0xFFCCCCCC);
                //#if MC>=260100
                //$$ final String dimension = this.font.plainSubstrByWidth(info.dimensionText(), DIM_WIDTH);
                //#else
                final String dimension = this.textRenderer.trimToWidth(info.dimensionText(), DIM_WIDTH);
                //#endif
                draw.drawTextWithShadow(this.textRenderer, dimension, dimensionX(), info.y() + 10, 0xFFCCCCCC);
            }
        }
        if (rows.isEmpty()) {
            final String empty = this.textRenderer.trimToWidth(
                Texts.translatable(emptyKey()).getString(), Math.max(40, contentWidth() - 16)
            );
            draw.drawTextWithShadow(
                this.textRenderer, empty, width / 2f - this.textRenderer.getWidth(empty) / 2f, listTop() + 6, 0xFFAAAAAA
            );
        }
        final int visibleRowCount = Math.max(1, (height - BOTTOM_MARGIN - listTop()) / ROW_HEIGHT);
        drawListScrollbar(
            draw,
            contentRight() + 3,
            listTop(),
            visibleRowCount * ROW_HEIGHT - 4,
            totalRowCount,
            visibleRowCount,
            scrollOffset
        );
        // The model cannot distinguish an unwritable store from a missing session; the
        // banner is about the store's format, so the store answers it directly.
        if (tab == Tab.LOCAL && lastLocalStore != null && !lastLocalStore.persistenceWritable()) {
            final String readOnly = this.textRenderer.trimToWidth(
                Texts.translatable("confluxmap.screen.waypoints.read_only").getString(), contentWidth()
            );
            draw.drawTextWithShadow(
                this.textRenderer, readOnly, width / 2f - this.textRenderer.getWidth(readOnly) / 2f,
                height - BOTTOM_MARGIN - 10, 0xFFFF7777
            );
        }

    }

    /**
     * Header rows carry the whole row's click target (see mouseClicked): group
     * name flush left, member count right-aligned, arrow reflecting the
     * effective state - a search keeps folded groups expanded, so the arrow must
     * not claim folded while their members are on screen.
     */
    private void renderHeaderRow(
        final GuiDraw draw,
        final MatrixStack matrices,
        final HeaderRow header,
        final boolean hovered,
        final boolean searchActive
    ) {
        GuiShapes.fillRect(
            matrices,
            contentLeft(),
            header.y() + 1,
            contentWidth(),
            ROW_HEIGHT - 2,
            hovered ? 0x66333333 : 0x44282828
        );
        final boolean effectivelyCollapsed = header.group().collapsed() && !searchActive;
        final String arrow = effectivelyCollapsed ? COLLAPSED_ARROW : EXPANDED_ARROW;
        final int arrowX = contentLeft() + ROW_PADDING;
        draw.drawTextWithShadow(this.textRenderer, arrow, arrowX, header.y() + 10, 0xFFCCCCCC);
        final String count = Integer.toString(header.visibleCount());
        final int countWidth = this.textRenderer.getWidth(count);
        draw.drawTextWithShadow(
            this.textRenderer, count, contentRight() - ROW_PADDING - countWidth, header.y() + 10, 0xFFAAAAAA
        );
        final int nameX = arrowX + this.textRenderer.getWidth(EXPANDED_ARROW) + GAP;
        final String label = this.textRenderer.trimToWidth(
            groupDisplayName(header.group()),
            Math.max(24, contentRight() - ROW_PADDING - countWidth - GAP - nameX)
        );
        draw.drawTextWithShadow(this.textRenderer, label, nameX, header.y() + 10, 0xFFE0E0E0);
    }

    @Override
    protected void renderAfterWidgets(
        final GuiDraw draw,
        final int mouseX,
        final int mouseY,
        final float tickDelta
    ) {
        renderDimensionDropdown(draw, mouseX, mouseY);
        renderGroupDropdown(draw, mouseX, mouseY);
        renderActionTooltip(draw, mouseX, mouseY);
    }

    private void renderActionTooltip(final GuiDraw draw, final int mouseX, final int mouseY) {
        for (final ButtonWidget button : iconActions) {
            if (button.isHovered()) {
                draw.drawTooltip(this, this.textRenderer, button.getMessage(), mouseX, mouseY);
                return;
            }
        }
    }

    private void renderDimensionDropdown(final GuiDraw draw, final int mouseX, final int mouseY) {
        final DropdownGeometry dropdown = dimensionDropdownGeometry();
        if (dropdown == null || dropdown.visibleRows() <= 0) {
            return;
        }
        dimensionDropdownScrollOffset = DropdownScroll.clamp(
            dimensionDropdownScrollOffset,
            dimensionDropdownOptions.size(),
            dropdown.visibleRows()
        );
        final boolean hasScrollbar = dimensionDropdownOptions.size() > dropdown.visibleRows();
        final int textRightPadding = hasScrollbar ? DROPDOWN_SCROLLBAR_WIDTH + 5 : 5;
        final DimensionId currentDimension = gameBridge.session().dimension();

        draw.fill(
            dropdown.x() - 1,
            dropdown.popupY() - 1,
            dropdown.x() + dropdown.width() + 1,
            dropdown.popupY() + dropdown.popupHeight() + 1,
            0xFF000000
        );
        for (int visibleIndex = 0; visibleIndex < dropdown.visibleRows(); visibleIndex++) {
            final int optionIndex = dimensionDropdownScrollOffset + visibleIndex;
            if (optionIndex >= dimensionDropdownOptions.size()) {
                break;
            }
            final int rowY = dropdown.popupY() + visibleIndex * DROPDOWN_ROW_HEIGHT;
            final boolean hovered = mouseX >= dropdown.x()
                && mouseX < dropdown.x() + dropdown.width()
                    - (hasScrollbar ? DROPDOWN_SCROLLBAR_WIDTH : 0)
                && mouseY >= rowY
                && mouseY < rowY + DROPDOWN_ROW_HEIGHT;
            final WaypointDimensionFilter option = dimensionDropdownOptions.get(optionIndex);
            final boolean selected = option.equals(dimensionFilter);
            final boolean keyboardFocused = optionIndex == dimensionDropdownKeyboardIndex;
            draw.fill(
                dropdown.x(),
                rowY,
                dropdown.x() + dropdown.width(),
                rowY + DROPDOWN_ROW_HEIGHT,
                hovered || keyboardFocused ? 0xFF6E6E6E : selected ? 0xFF505050 : 0xFF2A2A2A
            );
            if (selected) {
                draw.fill(
                    dropdown.x(),
                    rowY + 2,
                    dropdown.x() + 2,
                    rowY + DROPDOWN_ROW_HEIGHT - 2,
                    0xFFFFFFFF
                );
            }
            //#if MC>=260100
            //$$ final String label = this.font.plainSubstrByWidth(
            //$$     dimensionFilterLabel(option, currentDimension),
            //$$     Math.max(8, dropdown.width() - textRightPadding - 5)
            //$$ );
            //#else
            final String label = this.textRenderer.trimToWidth(
                dimensionFilterLabel(option, currentDimension),
                Math.max(8, dropdown.width() - textRightPadding - 5)
            );
            //#endif
            draw.drawTextWithShadow(
                this.textRenderer,
                label,
                dropdown.x() + 5,
                rowY + (DROPDOWN_ROW_HEIGHT - this.textRenderer.fontHeight) / 2f,
                selected ? 0xFFFFFFFF : 0xFFE0E0E0
            );
        }

        if (hasScrollbar) {
            renderDropdownScrollbar(
                draw,
                dropdown,
                dimensionDropdownOptions.size(),
                dimensionDropdownScrollOffset
            );
        }
    }

    private void renderGroupDropdown(final GuiDraw draw, final int mouseX, final int mouseY) {
        final DropdownGeometry dropdown = groupDropdownGeometry();
        if (dropdown == null || dropdown.visibleRows() <= 0) {
            return;
        }
        final List<GroupRef> options = groupDropdownOptions();
        dropdownScrollOffset = DropdownScroll.clamp(
            dropdownScrollOffset, options.size(), dropdown.visibleRows()
        );
        final boolean hasScrollbar = options.size() > dropdown.visibleRows();
        final int textRightPadding = hasScrollbar ? DROPDOWN_SCROLLBAR_WIDTH + 5 : 5;

        draw.fill(
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
            final GroupRef option = options.get(optionIndex);
            final boolean selected = Objects.equals(
                option,
                openGroupDropdown == GroupDropdown.FILTER ? groupFilter() : moveTarget()
            );
            final boolean keyboardFocused = optionIndex == dropdownKeyboardIndex;
            draw.fill(
                dropdown.x(),
                rowY,
                dropdown.x() + dropdown.width(),
                rowY + DROPDOWN_ROW_HEIGHT,
                hovered || keyboardFocused ? 0xFF6E6E6E : selected ? 0xFF505050 : 0xFF2A2A2A
            );
            if (selected) {
                draw.fill(dropdown.x(), rowY + 2, dropdown.x() + 2, rowY + DROPDOWN_ROW_HEIGHT - 2, 0xFFFFFFFF);
            }
            final String count = Integer.toString(option == null
                ? activeModel().waypoints().size()
                : activeModel().memberCount(option.key()));
            final int textRight = dropdown.x() + dropdown.width() - textRightPadding;
            final int countWidth = this.textRenderer.getWidth(count);
            //#if MC>=260100
            //$$ final String label = this.font.plainSubstrByWidth(
            //$$     groupDropdownDisplayName(option), Math.max(8, textRight - countWidth - GAP - dropdown.x() - 5)
            //$$ );
            //#else
            final String label = this.textRenderer.trimToWidth(
                groupDropdownDisplayName(option), Math.max(8, textRight - countWidth - GAP - dropdown.x() - 5)
            );
            //#endif
            draw.drawTextWithShadow(
                this.textRenderer,
                label,
                dropdown.x() + 5,
                rowY + (DROPDOWN_ROW_HEIGHT - this.textRenderer.fontHeight) / 2f,
                selected ? 0xFFFFFFFF : 0xFFE0E0E0
            );
            draw.drawTextWithShadow(
                this.textRenderer,
                count,
                textRight - countWidth,
                rowY + (DROPDOWN_ROW_HEIGHT - this.textRenderer.fontHeight) / 2f,
                0xFFAAAAAA
            );
        }

        if (hasScrollbar) {
            renderDropdownScrollbar(draw, dropdown, options.size(), dropdownScrollOffset);
        }

        if (dropdown.actionHeight() > 0) {
            renderGroupDropdownActions(draw, mouseX, mouseY, dropdown);
        }
    }

    private void renderGroupDropdownActions(
        final GuiDraw draw,
        final int mouseX,
        final int mouseY,
        final DropdownGeometry dropdown
    ) {
        final int actionY = dropdown.popupY() + dropdown.optionHeight();
        draw.fill(
            dropdown.x(),
            actionY,
            dropdown.x() + dropdown.width(),
            actionY + 1,
            0xFF111111
        );
        for (int actionIndex = 0; actionIndex < 3; actionIndex++) {
            final int actionLeft = dropdown.x() + dropdown.width() * actionIndex / 3;
            final int actionRight = dropdown.x() + dropdown.width() * (actionIndex + 1) / 3;
            final boolean active = groupDropdownActionActive(actionIndex);
            final boolean hovered = active
                && mouseX >= actionLeft
                && mouseX < actionRight
                && mouseY >= actionY
                && mouseY < actionY + dropdown.actionHeight();
            draw.fill(
                actionLeft,
                actionY,
                actionRight,
                actionY + dropdown.actionHeight(),
                actionIndex == 2 && active
                    ? hovered ? 0xFF8A3A3A : 0xFF5A2A2A
                    : hovered ? 0xFF6E6E6E : active ? 0xFF383838 : 0xFF202020
            );
            if (actionIndex > 0) {
                draw.fill(actionLeft, actionY + 3, actionLeft + 1, actionY + dropdown.actionHeight() - 3, 0xFF101010);
            }
            final String label = this.textRenderer.trimToWidth(
                groupDropdownActionLabel(actionIndex).getString(), Math.max(8, actionRight - actionLeft - 6)
            );
            draw.drawTextWithShadow(
                this.textRenderer,
                label,
                (actionLeft + actionRight - this.textRenderer.getWidth(label)) / 2f,
                actionY + (dropdown.actionHeight() - this.textRenderer.fontHeight) / 2f,
                active ? 0xFFFFFFFF : 0xFF777777
            );
        }
    }

    private String groupDropdownDisplayName(final GroupRef option) {
        if (option == null) {
            return Texts.translatable(tab == Tab.LOCAL
                ? "confluxmap.screen.waypoints.set_all"
                : "confluxmap.screen.waypoints.group_all").getString();
        }
        return groupDisplayName(option);
    }

    private String groupDisplayName(final GroupRef group) {
        if (group.isUngrouped()) {
            return Texts.translatable(tab == Tab.LOCAL
                ? "confluxmap.screen.waypoints.set_unassigned"
                : "confluxmap.screen.waypoints.group_ungrouped").getString();
        }
        return group.displayName();
    }

    private void renderDropdownScrollbar(
        final GuiDraw draw,
        final DropdownGeometry dropdown,
        final int optionCount,
        final int offset
    ) {
        final int trackX = dropdown.x() + dropdown.width() - DROPDOWN_SCROLLBAR_WIDTH;
        final int trackTop = dropdown.popupY() + 1;
        final int trackHeight = Math.max(1, dropdown.optionHeight() - 2);
        final int thumbHeight = dropdownThumbHeight(trackHeight, optionCount, dropdown.visibleRows());
        final int maxOffset = DropdownScroll.maxOffset(optionCount, dropdown.visibleRows());
        final int thumbTravel = Math.max(0, trackHeight - thumbHeight);
        final int thumbTop = trackTop + (maxOffset == 0
            ? 0
            : Math.round(thumbTravel * (offset / (float) maxOffset)));
        draw.fill(
            trackX,
            dropdown.popupY(),
            dropdown.x() + dropdown.width(),
            dropdown.popupY() + dropdown.optionHeight(),
            0xFF151515
        );
        draw.fill(
            trackX + 1,
            thumbTop,
            dropdown.x() + dropdown.width() - 1,
            thumbTop + thumbHeight,
            draggingDropdownScrollbar ? 0xFFFFFFFF : 0xFFAAAAAA
        );
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
            return groupFilter() == null
                ? "confluxmap.screen.waypoints.empty_local"
                : "confluxmap.screen.waypoints.empty_set";
        }
        return sharedModel.syncPending()
            ? "confluxmap.shared_waypoints.status.syncing"
            : "confluxmap.screen.waypoints.empty_public";
    }

    private Text filterLabel() {
        final GroupRef filter = groupFilter();
        return Texts.translatable(
            tab == Tab.LOCAL
                ? "confluxmap.screen.waypoints.set_filter"
                : "confluxmap.screen.waypoints.group_filter",
            filter == null ? groupDropdownDisplayName(null) : groupDisplayName(filter),
            filteredOwnedIds.size()
        );
    }

    private Text moveTargetLabel() {
        return Texts.translatable(
            "confluxmap.screen.waypoints.selection_target",
            groupDisplayName(moveTarget())
        );
    }

    private Text selectionClearLabel() {
        return Text.of(
            Texts.translatable("confluxmap.screen.waypoints.selection_clear").getString()
                + " (" + selectedWaypointIds.size() + ")"
        );
    }

    private Text fitButtonLabel(final Text label, final int buttonWidth) {
        return Text.of(this.textRenderer.trimToWidth(label.getString(), Math.max(8, buttonWidth - 8)));
    }

    private int listTop() {
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

    private int headerControlWidth() {
        return Math.min(
            HEADER_CONTROL_MAX_WIDTH,
            Math.max(120, width - MIN_SIDE_MARGIN * 2)
        );
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

    private int dimensionX() {
        return rowActionLayout().x(0) - GAP - DIM_WIDTH;
    }

    private int distanceX() {
        return dimensionX() - GAP - DIST_WIDTH;
    }

    private int markerX(final boolean indented) {
        return contentLeft() + ROW_PADDING + (indented ? SET_MEMBER_INDENT : 0) + CHECK_WIDTH + GAP;
    }

    private int nameX(final boolean indented) {
        return markerX(indented) + MARKER_SIZE + GAP;
    }

    private int nameRight() {
        return compactRows() ? rowActionLayout().x(0) - GAP : distanceX() - GAP;
    }

    private WaypointRowActionLayout rowActionLayout() {
        if (actionLayout == null) {
            actionLayout = createRowActionLayout();
        }
        return actionLayout;
    }

    private WaypointRowActionLayout createRowActionLayout() {
        return WaypointRowActionLayout.create(
            contentLeft(),
            contentRight(),
            ROW_PADDING,
            GAP,
            ROW_ACTION_WIDTHS
        );
    }

    private static double distance(
        final WaypointRow row,
        final DimensionId currentDimension,
        final double px,
        final double py,
        final double pz
    ) {
        if (!row.dimensionId().equals(currentDimension)
            && (!row.crossDimensionVisible()
                || !DimensionScale.isVisibleFrom(row.dimensionId(), currentDimension))) {
            return Double.POSITIVE_INFINITY;
        }
        final double dx = DimensionScale.convertHorizontal(row.x(), row.dimensionId(), currentDimension) - px;
        final double dz = DimensionScale.convertHorizontal(row.z(), row.dimensionId(), currentDimension) - pz;
        final double dy = row.y() - py;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static String tabKey(final Tab tab) {
        return switch (tab) {
            case LOCAL -> "confluxmap.screen.waypoints.tab.local";
            case PUBLIC -> "confluxmap.screen.waypoints.tab.public";
        };
    }

    private static String formatDistance(final double distance) {
        return Double.isFinite(distance)
            ? Texts.translatable("confluxmap.value.blocks", Math.round(distance)).getString()
            : "-";
    }

    private static String dimensionLabel(final DimensionId dimension) {
        if (dimension.equals(DimensionId.OVERWORLD)) {
            return Texts.translatable("confluxmap.dimension.overworld").getString();
        }
        if (dimension.equals(DimensionId.NETHER)) {
            return Texts.translatable("confluxmap.dimension.the_nether").getString();
        }
        if (dimension.equals(DimensionId.END)) {
            return Texts.translatable("confluxmap.dimension.the_end").getString();
        }
        return dimension.path();
    }
}
