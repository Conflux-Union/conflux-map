package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.compat.*;
import cn.net.rms.confluxmap.core.model.*;
import cn.net.rms.confluxmap.core.waypoint.*;
import cn.net.rms.confluxmap.core.waypoint.library.*;
import cn.net.rms.confluxmap.core.waypoint.list.*;
import cn.net.rms.confluxmap.mc.teleport.TeleportCommandAccess;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import cn.net.rms.confluxmap.mc.ui.WaypointMarkerRenderer;
import cn.net.rms.confluxmap.mc.ui.widget.ConfluxTextButton;
import cn.net.rms.confluxmap.mc.ui.widget.WaypointStarButton;
import java.util.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
//#if MC>=12109
//$$ import net.minecraft.client.gui.Click;
//#endif

/** Native alternative layout for the waypoint list. Every gesture re-resolves world/source/UUID. */
final class CompactWaypointScreen extends ConfluxScreen {
    private static final int CELL_CONTROL_WIDTH = 12;
    private static final int ACTION_HEIGHT = 16;
    private static final int ACTION_GAP = 2;
    private static final int PANEL_PADDING = 4;
    private static final int SHORT_PANEL_HEIGHT = 104;
    private static final int FULL_PANEL_HEIGHT = 116;

    private record Entry(WaypointRow row, boolean shared, double distance) {
        WaypointMediaService.Key key(WorldIdentity world) { return new WaypointMediaService.Key(world, shared, row.id()); }
        WaypointRenderEntry renderEntry() { return new WaypointRenderEntry(row.id(), row.name(), row.dimensionId(), row.x(), row.y(), row.z(), row.colorArgb(), row.iconItemId(), row.markerLabel(), row.type(), shared ? WaypointRenderEntry.Source.SHARED : WaypointRenderEntry.Source.LOCAL, row.crossDimensionVisible(), row.worldLabel()); }
    }
    private record GroupChoice(boolean shared, GroupRef group) {}
    private record Action(int x, int y, int width, int height, Text text, boolean active, Runnable run) {
        boolean contains(double mx, double my) { return mx >= x && mx < x + width && my >= y && my < y + height; }
    }
    private final ConfluxMapClient app = ConfluxMapClient.get();
    private final WaypointListScreen normal;
    private final Screen parent;
    private final WorldIdentity world;
    private final WaypointListMemory memory = app.waypointListMemory();
    private final WaypointListMemory.Options options = memory.options();
    private final Set<WaypointMediaService.Key> selected = new LinkedHashSet<>();
    private final WaypointPhotoPreview preview = new WaypointPhotoPreview();
    private final List<Action> actions = new ArrayList<>();
    private List<Entry> entries = List.of();
    private List<GroupChoice> groups = List.of();
    private WaypointGridLayout layout;
    private WaypointMediaService.Key hovered, pendingDelete;
    private TextFieldWidget search;
    private String query;
    private WaypointDimensionFilter dimension;
    private String filterGroup;
    private boolean shared, filterShared;
    private int scroll, moveTarget;
    private CompactWaypointPanelLayout panel;
    private WaypointGridLayout.Cell panelAnchor;
    private int hoverX, hoverY;
    private long localRevision, publicRevision;
    private DimensionId lastDimension;
    private long hoverUntil;
    private boolean pinnedPanel;

    CompactWaypointScreen(WaypointListScreen normal, Screen parent, WaypointListMemory.State state) {
        super(Texts.translatable("confluxmap.screen.waypoints.title"));
        this.normal = normal; this.parent = parent; world = app.gameBridge().session().world();
        shared = "PUBLIC".equals(state.tab()); filterShared = shared;
        query = state.search(); scroll = state.compactScroll(); filterGroup = state.set();
        dimension = "ALL".equals(state.dimensionMode()) ? WaypointDimensionFilter.all()
            : "ONLY".equals(state.dimensionMode()) && state.dimension() != null ? WaypointDimensionFilter.only(DimensionId.parse(state.dimension())) : WaypointDimensionFilter.current();
    }
    private static Text label(String key) { return Texts.translatable("confluxmap.compact." + key); }
    private boolean valid() { return world.equals(app.gameBridge().session().world()) && world.equals(app.waypointLibraryService().world()) && app.companionSession().waypointsAllowed(); }
    private WaypointListModel model(boolean source) { return source ? app.sharedWaypointListModel() : app.localWaypointListModel(); }
    private WaypointMetadata extra(Entry entry) { return app.waypointLibraryService().library().get(entry.row.id(), entry.shared); }
    private Entry resolve(WaypointMediaService.Key key) {
        if (!valid() || key == null || !world.equals(key.world()) || key.shared() && !app.sharedWaypoints().availability().enabled()) return null;
        WaypointRow row = model(key.shared()).rowById(key.waypoint());
        return row == null || row.guest() ? null : new Entry(row, key.shared(), 0);
    }
    @Override protected void init() {
        hovered = null; pendingDelete = null; pinnedPanel = false; preview.show(null);
        final int headerWidth = WaypointListChrome.create(width, height, shared).headerWidth();
        search = new TextFieldWidget(this.textRenderer, (width - headerWidth) / 2, 52, headerWidth, 20, Texts.translatable("confluxmap.screen.waypoints.search"));
        search.setMaxLength(64); search.setText(query); rebuild(); setInitialFocus(search);
    }
    private void rebuild() {
        if (!valid()) return;
        clearChildren(); actions.clear(); panel = null;
        addDrawableChild(search);
        if (!app.sharedWaypoints().availability().enabled()) shared = false;
        List<Entry> next = new ArrayList<>(); List<GroupChoice> groupOptions = new ArrayList<>();
        DimensionId current = app.gameBridge().session().dimension();
        var player = app.gameBridge().player();
        for (boolean source : new boolean[]{false, true}) {
            if (source && !app.sharedWaypoints().availability().enabled()) continue;
            for (GroupRef group : model(source).groupsForDisplay()) groupOptions.add(new GroupChoice(source, group));
            if (!options.both && source != shared) continue;
            for (WaypointRow row : model(source).waypoints()) {
                if (row.guest() || !dimension.matches(row.dimensionId(), current)) continue;
                if (filterGroup != null && (filterShared != source || !filterGroup.equals(row.groupKey()))) continue;
                double dx = row.x() - player.map(p -> p.x()).orElse(0.0), dy = row.y() - player.map(p -> p.y()).orElse(0.0), dz = row.z() - player.map(p -> p.z()).orElse(0.0);
                Entry entry = new Entry(row, source, row.dimensionId().equals(current) ? Math.sqrt(dx * dx + dy * dy + dz * dz) : Double.POSITIVE_INFINITY);
                if (WaypointDetailsText.matches(extra(entry), query) || WaypointSearch.matches(query, row.name(), row.secondaryText(), row.dimensionId().toString(), row.x(), row.y(), row.z())) next.add(entry);
            }
        }
        next.sort(Comparator.<Entry, Boolean>comparing(e -> !(options.favorites && extra(e).starred())).thenComparing(e -> options.sourcePriority && (options.localFirst ? e.shared : !e.shared)).thenComparingDouble(Entry::distance).thenComparing(e -> e.row.name()).thenComparing(e -> e.row.id()));
        entries = List.copyOf(next); groups = List.copyOf(groupOptions);
        selected.removeIf(key -> resolve(key) == null);
        final WaypointListChrome chrome = WaypointListChrome.create(width, height, shared);
        final int listTop = selected.isEmpty() ? 128 : 152;
        layout = WaypointGridLayout.create(entries.size(), chrome.left(), listTop, chrome.right() - chrome.left(),
            Math.max(0, height - 34 - listTop), true, this.textRenderer.fontHeight, scroll,
            options.compactWidthPercent(), options.compactHeightPercent());
        scroll = layout.scroll(); moveTarget = Math.min(moveTarget, Math.max(0, groups.size() - 1));
        final boolean publicVisible = app.sharedWaypoints().availability().visible();
        int count = publicVisible ? 2 : 1;
        int tabWidth = Math.max(80, Math.min(140, (chrome.right() - chrome.left() - 4 * (count - 1)) / count));
        int tabX = (width - count * tabWidth - 4 * (count - 1)) / 2;
        for (int i = 0; i < count; i++) {
            final boolean source = i == 1;
            String text = Texts.translatable("confluxmap.screen.waypoints.tab." + (source ? "public" : "local")).getString();
            addDrawableChild(Widgets.button(tabX + i * (tabWidth + 4), 28, tabWidth, 20,
                Texts.literal(source == shared ? "[" + text + "]" : text), b -> { shared = source; filterGroup = null; selected.clear(); reset(); }))
                .active = !source || app.sharedWaypoints().availability().enabled();
        }
        int hx = (width - chrome.headerWidth()) / 2;
        String dimensionName = dimension.mode() == WaypointDimensionFilter.Mode.ONLY ? dimension.dimension().toString()
            : Texts.translatable(dimension.mode() == WaypointDimensionFilter.Mode.ALL ? "confluxmap.screen.waypoints.dimension_all" : "confluxmap.compact.current_dimension").getString();
        addDrawableChild(Widgets.button(hx, 76, chrome.headerWidth(), 20,
            Texts.translatable("confluxmap.screen.waypoints.dimension_filter", dimensionName, entries.size()), b -> cycleDimension()));
        String groupName = filterGroup == null ? Texts.translatable("confluxmap.screen.waypoints.set_all").getString()
            : groups.stream().filter(g -> g.shared == filterShared && g.group.key().equals(filterGroup)).map(g -> groupLabel(g.group)).findFirst().orElse(filterGroup);
        addDrawableChild(Widgets.button(hx, 100, chrome.headerWidth() - 88, 20,
            Texts.translatable("confluxmap.screen.waypoints.set_filter", groupName, entries.size()), b -> cycleGroup()));
        addDrawableChild(Widgets.button(hx + chrome.headerWidth() - 84, 100, 84, 20,
            Texts.translatable("confluxmap.screen.waypoints.selection_all"), b -> {
                List<WaypointMediaService.Key> selectable = entries.stream().filter(e -> model(e.shared).canMoveWaypoint(e.row).granted()).map(e -> e.key(world)).toList();
                if (!selectable.isEmpty() && selected.containsAll(selectable)) selected.removeAll(selectable); else selected.addAll(selectable);
                rebuild();
            }));
        if (!selected.isEmpty() && !groups.isEmpty()) {
            String target = sourceName(groups.get(moveTarget).shared) + ": " + groupLabel(groups.get(moveTarget).group);
            addDrawableChild(Widgets.button(hx, 124, chrome.headerWidth() - 88, 20,
                Texts.translatable("confluxmap.screen.waypoints.selection_target", target), b -> { moveTarget = (moveTarget + 1) % groups.size(); rebuild(); }));
            addDrawableChild(Widgets.button(hx + chrome.headerWidth() - 84, 124, 84, 20,
                Texts.translatable("confluxmap.screen.waypoints.selection_move", selected.size()), b -> moveSelection()));
        }
        for (WaypointGridLayout.Cell cell : layout.cells()) {
            Entry entry = entries.get(cell.index()); var key = entry.key(world);
            int size = controlSize(cell);
            int controlY = cell.y() + (cell.height() - size) / 2;
            String check = selected.contains(key) ? "\u2713" : "";
            addDrawableChild(new ConfluxTextButton(cell.x() + 1, controlY, size, size, Texts.literal(check), b -> {
                if (resolve(key) != null) { if (!selected.remove(key)) selected.add(key); rebuild(); }
            }) {
                @Override protected void drawForeground(GuiDraw draw) {
                    if (!check.isEmpty()) {
                        drawCellText(draw, check, cell.x() + 2, controlY + 1, size - 2, size - 2,
                            active ? 0xFFFFFFFF : 0xFF888888, true);
                    }
                }
            }).active = model(entry.shared).canMoveWaypoint(entry.row).granted();
            if (options.favorites) addDrawableChild(new WaypointStarButton(cell.x() + size + 2, controlY,
                size, size, extra(entry).starred(), b -> {
                Entry currentEntry = resolve(key); if (!options.favorites || currentEntry == null || !app.waypointLibraryService().writable()) return;
                app.waypointLibraryService().library().setStarred(key.waypoint(), key.shared(), !extra(currentEntry).starred()); app.waypointLibraryService().save(); reset();
            })).active = app.waypointLibraryService().writable();
        }
        addDrawableChild(Widgets.button(chrome.left(), height - 24, chrome.createWidth(), 20,
            Texts.translatable(shared ? "confluxmap.screen.waypoints.create_public" : "confluxmap.screen.waypoints.create_local"), b -> create())).active = model(shared).canCreateWaypoint().granted();
        if (!shared) addDrawableChild(Widgets.button(chrome.left() + chrome.createWidth() + 4, height - 24, chrome.createWidth(), 20,
            Texts.translatable("confluxmap.screen.waypoints.import_open"), b -> {
                var store = app.waypointService().current(); if (store != null && store.persistenceWritable())
                    MinecraftAccess.setScreen(MinecraftClient.getInstance(), new WaypointImportScreen(this, store));
            }));
        addDrawableChild(Widgets.button(chrome.settingsX(), chrome.settingsY(), chrome.settingsWidth(), 20,
            Texts.translatable("confluxmap.enhancements.settings"), b -> MinecraftAccess.setScreen(MinecraftClient.getInstance(), ConfigScreen.waypointEnhancements(this))));
        addDrawableChild(Widgets.button(chrome.right() - 80, height - 24, 80, 20,
            Texts.translatable("confluxmap.screen.waypoint.done"), b -> onClose()));
        localRevision = model(false).stateVersion(); publicRevision = model(true).stateVersion(); lastDimension = current;
    }
    private void reset() { scroll = 0; pinnedPanel = false; hovered = null; pendingDelete = null; preview.show(null); rebuild(); }
    private void cycleDimension() {
        LinkedHashSet<DimensionId> dimensions = new LinkedHashSet<>(); dimensions.add(app.gameBridge().session().dimension());
        for (boolean source : new boolean[]{false, true}) for (WaypointRow row : model(source).waypoints()) dimensions.add(row.dimensionId());
        List<WaypointDimensionFilter> choices = WaypointDimensionFilter.options(app.gameBridge().session().dimension(), List.copyOf(dimensions));
        dimension = choices.get((Math.max(0, choices.indexOf(dimension)) + 1) % choices.size()); reset();
    }
    private void cycleGroup() {
        List<GroupChoice> choices = groups.stream().filter(g -> options.both || g.shared == shared).toList();
        int index = -1; for (int i = 0; i < choices.size(); i++) if (choices.get(i).shared == filterShared && choices.get(i).group.key().equals(filterGroup)) index = i;
        if (index + 1 >= choices.size()) filterGroup = null;
        else { GroupChoice next = choices.get(index + 1); filterGroup = next.group.key(); filterShared = next.shared; }
        reset();
    }
    private String groupLabel(GroupRef group) { return group.isUngrouped() ? Texts.translatable("confluxmap.screen.waypoints.group_ungrouped").getString() : group.displayName(); }
    private String sourceName(boolean source) { return label(source ? "public" : "local").getString(); }
    private void create() {
        boolean source = (filterGroup == null ? shared : filterShared) && app.sharedWaypoints().availability().enabled();
        if (!model(source).canCreateWaypoint().granted()) return;
        var p = app.gameBridge().player(); double x = p.map(v -> v.x()).orElse(0.0), y = p.map(v -> v.y()).orElse(64.0), z = p.map(v -> v.z()).orElse(0.0);
        MinecraftAccess.setScreen(MinecraftClient.getInstance(), source ? WaypointEditScreen.forPublicCreate(this, app.gameBridge().session().dimension(), x, y, z) : WaypointEditScreen.forCreate(this, app.gameBridge().session().dimension(), x, y, z, filterGroup == null ? GroupRef.UNGROUPED_KEY : filterGroup));
    }
    private void moveSelection() {
        if (groups.isEmpty() || !valid()) return;
        GroupChoice target = groups.get(moveTarget); WaypointListModel model = model(target.shared);
        List<WaypointRow> rows = selected.stream().filter(key -> key.shared() == target.shared).map(this::resolve).filter(Objects::nonNull).map(Entry::row).filter(row -> model.canMoveWaypoint(row).granted()).toList();
        if (!rows.isEmpty() && model.supportsBatchMove() && model.moveWaypoints(rows, target.group)) selected.removeIf(key -> key.shared() == target.shared);
        rebuild();
    }
    @Override public void tick() {
        super.tick();
        if (isClosing() || MinecraftAccess.screen(MinecraftClient.getInstance()) != this) return;
        if (!valid()) { requestClose(null); return; }
        if (!options.compact) {
            MinecraftAccess.setScreen(MinecraftClient.getInstance(), layoutAfterSettings()); return;
        }
        Widgets.tick(search);
        if (!query.equals(search.getText())) { query = search.getText(); reset(); }
        else if (localRevision != model(false).stateVersion() || publicRevision != model(true).stateVersion() || !Objects.equals(lastDimension, app.gameBridge().session().dimension())) rebuild();
    }
    @Override protected void renderContents(GuiDraw draw, int mx, int my, float delta) {
        draw.renderBackground(this, mx, my, delta);
        if (!valid() || layout == null) return;
        final String title = getTitle().getString();
        draw.drawTextWithShadow(this.textRenderer, title, (width - this.textRenderer.getWidth(title)) / 2f, 10, 0xFFFFFFFF);
        if (options.hoverActions && !pinnedPanel) {
            long now = System.nanoTime();
            if (insidePanel(mx, my)) {
                hoverUntil = now + 250_000_000L;
            } else {
                var cell = layout.at(mx, my);
                if (cell != null && entries.get(cell.index()).key(world).equals(hovered)) {
                    rememberHover(mx, my, now);
                } else if (panel == null || now > hoverUntil || !panel.onApproach(panelAnchor, hoverX, hoverY, mx, my)) {
                    hover(cell == null ? null : entries.get(cell.index()).key(world));
                    if (cell != null) rememberHover(mx, my, now);
                }
            }
        }
        for (var cell : layout.cells()) {
            Entry entry = entries.get(cell.index()); boolean active = entry.key(world).equals(hovered);
            draw.fill(cell.x(), cell.y(), cell.x() + cell.width(), cell.y() + cell.height(), active ? 0xAA454545 : 0x66202020);
            int size = controlSize(cell);
            int markerHalf = Math.min(4, Math.min((cell.height() - 2) / 2, cell.width() / 8));
            int markerX = (size + 1) * (options.favorites ? 2 : 1) + markerHalf + 2;
            if (markerHalf > 0 && markerX + markerHalf + 1 < cell.width()) {
                WaypointMarkerRenderer.draw(draw, this.textRenderer, entry.renderEntry(), cell.x() + markerX,
                    cell.y() + cell.height() / 2f, markerHalf, 1, active, WaypointVerticalRelation.NONE);
            }
            int nameX = markerX + markerHalf + 3;
            drawCellText(draw, entry.row.name(), cell.x() + nameX, cell.y(), cell.width() - nameX - 2,
                cell.height(), entry.row.hidden() ? 0xFF888888 : 0xFFFFFFFF, false);
        }
    }
    private static int controlSize(WaypointGridLayout.Cell cell) {
        return Math.max(1, Math.min(CELL_CONTROL_WIDTH, Math.min(cell.height() - 2, cell.width() / 5)));
    }

    private void drawCellText(GuiDraw draw, String text, int x, int y, int w, int h, int color, boolean centered) {
        if (w <= 0 || h <= 1) return;
        int fontHeight = this.textRenderer.fontHeight;
        float scale = Math.min(1f, (h - 1f) / fontHeight);
        if (centered) scale = Math.min(scale, w / (float) Math.max(1, this.textRenderer.getWidth(text)));
        String fitted = trim(text, (int) (w / scale));
        if (fitted.isEmpty()) return;
        float left = centered ? x + (w - this.textRenderer.getWidth(fitted) * scale) / 2f : x;
        float top = y + (h - fontHeight * scale) / 2f;
        if (scale == 1f) {
            draw.drawTextWithShadow(this.textRenderer, fitted, left, top, color);
            return;
        }
        draw.pushTransform();
        draw.translate(left, top);
        draw.scale(scale, scale);
        draw.drawTextWithShadow(this.textRenderer, fitted, 0, 0, color);
        draw.popTransform();
    }

    private void rememberHover(int mx, int my, long now) {
        hoverX = mx;
        hoverY = my;
        hoverUntil = now + 250_000_000L;
    }

    private void hover(WaypointMediaService.Key key) {
        if (Objects.equals(hovered, key)) return;
        hovered = key;
        pendingDelete = null;
        panel = null;
        actions.clear();
    }

    private boolean insidePanel(double x, double y) { return panel != null && panel.contains(x, y); }

    @Override protected void renderAfterWidgets(GuiDraw draw, int mx, int my, float delta) {
        actions.clear();
        panel = null;
        if (!valid() || layout == null || hovered == null || !options.hoverActions && !pinnedPanel) {
            preview.show(null);
            return;
        }
        var anchor = layout.cells().stream()
            .filter(cell -> entries.get(cell.index()).key(world).equals(hovered))
            .findFirst().orElse(null);
        Entry entry = resolve(hovered);
        if (anchor == null || entry == null) {
            pinnedPanel = false;
            hover(null);
            preview.show(null);
            return;
        }

        if (height >= 300) panel = CompactWaypointPanelLayout.place(anchor, width, height, FULL_PANEL_HEIGHT);
        if (panel == null) panel = CompactWaypointPanelLayout.place(anchor, width, height, SHORT_PANEL_HEIGHT);
        if (panel == null) {
            preview.show(null);
            return;
        }
        panelAnchor = anchor;
        int x = panel.x() + PANEL_PADDING;
        int y = panel.y() + PANEL_PADDING;
        int contentWidth = panel.width() - 2 * PANEL_PADDING;
        boolean small = panel.height() == SHORT_PANEL_HEIGHT;
        draw.fill(panel.x(), panel.y(), panel.x() + panel.width(), panel.y() + panel.height(), 0xF0202020);
        double distance = entries.get(anchor.index()).distance;
        String info = sourceName(entry.shared) + (options.dimension ? "  " + entry.row.dimensionId() : "")
            + (options.distance ? "  " + (Double.isFinite(distance) ? Math.round(distance) + "m" : "--") : "");
        draw.drawTextWithShadow(this.textRenderer, trim(entry.row.name(), contentWidth), x, y, 0xFFFFFFFF);
        draw.drawTextWithShadow(this.textRenderer, trim(info, contentWidth), x, y + 11, 0xFFBBBBBB);

        WaypointMetadata metadata = extra(entry);
        preview.show(options.images && options.listPhotoPreview ? hovered : null);
        int previewWidth = preview.ready() ? 54 : 0;
        int detailWidth = contentWidth - (previewWidth == 0 ? 0 : previewWidth + 4);
        String summary = WaypointDetailsText.summary(metadata);
        if (small && options.details && !metadata.note().isBlank()) summary += "  " + metadata.note();
        draw.drawTextWithShadow(this.textRenderer, trim(summary, detailWidth), x, y + 22, 0xFFDDCC88);
        if (!small) draw.drawTextWithShadow(this.textRenderer, trim(options.details ? metadata.note() : "", detailWidth), x, y + 33, 0xFFDDDDDD);
        draw.drawTextWithShadow(this.textRenderer, trim(entry.row.x() + ", " + entry.row.y() + ", " + entry.row.z(), detailWidth),
            x, y + (small ? 33 : 44), 0xFFBBBBBB);
        if (previewWidth > 0) preview.draw(draw, x + contentWidth - previewWidth, y + 22, previewWidth, small ? 21 : 33);
        WaypointListModel model = model(entry.shared); var key = hovered;
        action(0, "edit", model.canEditWaypoint(entry.row).granted(), () -> edit(key));
        action(1, entry.row.hidden() ? "show" : "hide", model.canToggleHidden(entry.row).granted(), () -> mutate(key, false));
        action(2, key.equals(pendingDelete) ? "confirm" : "delete", model.canDeleteWaypoint(entry.row).granted(), () -> {
            if (key.equals(pendingDelete)) mutate(key, true); else pendingDelete = key;
        });
        action(3, "locate", true, () -> { Entry live = resolve(key); if (live != null) MinecraftAccess.setScreen(MinecraftClient.getInstance(), FullscreenMapScreen.focusedOnWaypoint(live.row.dimensionId(), live.row.x(), live.row.z())); });
        action(4, "teleport", teleportAllowed(entry), () -> teleport(key));
        if (options.images) action(5, 1, WaypointImagesScreen.label("title"), true,
            () -> MinecraftAccess.setScreen(MinecraftClient.getInstance(), new WaypointImagesScreen(this, key, () -> resolve(key) != null)));
        action(6, "share", true, () -> share(key));
        Waypoint local = entry.shared ? null : local(entry.row.id());
        boolean publish = options.quickPublish && local != null && app.sharedWaypoints().canCreate() && !app.sharedWaypoints().isLocationShared(local) && !app.sharedWaypoints().isCreatePending(local);
        if (options.quickPublish && !entry.shared) action(7, 2, label("publish"), publish, () -> {
            Entry live = resolve(key); if (options.quickPublish && live != null && !live.shared) { Waypoint waypoint = local(key.waypoint()); if (waypoint != null) app.sharedWaypoints().create(waypoint); }
        });
        for (Action action : actions) {
            var button = new cn.net.rms.confluxmap.mc.ui.widget.ConfluxTextButton(action.x, action.y, action.width, action.height, action.text, ignored -> action.run.run());
            button.active = action.active;
            button.drawStandalone(draw, action.contains(mx, my));
        }

    }
    private String trim(String text, int limit) { return this.textRenderer.trimToWidth(text, Math.max(1, limit)); }
    private void action(int index, String name, boolean enabled, Runnable run) {
        action(index, 1, Texts.translatable("confluxmap.screen.waypoints." + name), enabled, run);
    }

    private void action(int index, int span, Text title, boolean enabled, Runnable run) {
        int stride = ACTION_HEIGHT + ACTION_GAP;
        int top = panel.y() + panel.height() - PANEL_PADDING - 3 * stride + ACTION_GAP;
        int columnWidth = (panel.width() - 2 * PANEL_PADDING + ACTION_GAP) / 3;
        actions.add(new Action(panel.x() + PANEL_PADDING + index % 3 * columnWidth, top + index / 3 * stride,
            span * columnWidth - ACTION_GAP, ACTION_HEIGHT, title, enabled, run));
    }
    private void mutate(WaypointMediaService.Key key, boolean delete) {
        Entry entry = resolve(key); if (entry == null) return; WaypointListModel model = model(entry.shared);
        if (delete ? model.canDeleteWaypoint(entry.row).granted() : model.canToggleHidden(entry.row).granted()) {
            if (delete) model.deleteWaypoint(entry.row); else model.toggleWaypointHidden(entry.row);
        }
        pendingDelete = null; rebuild();
    }
    private Waypoint local(UUID id) { var store = app.waypointService().current(); return store == null ? null : store.list().stream().filter(w -> w.id.equals(id)).findFirst().orElse(null); }
    private void edit(WaypointMediaService.Key key) {
        Entry entry = resolve(key); if (entry == null || !model(entry.shared).canEditWaypoint(entry.row).granted()) return;
        Screen screen = entry.shared ? WaypointEditScreen.forPublicEdit(this, entry.row) : local(key.waypoint()) == null ? null : WaypointEditScreen.forEdit(this, local(key.waypoint()));
        if (screen != null) MinecraftAccess.setScreen(MinecraftClient.getInstance(), screen);
    }
    private void share(WaypointMediaService.Key key) {
        Entry entry = resolve(key); if (entry == null) return;
        if (entry.shared) MinecraftAccess.setScreen(MinecraftClient.getInstance(), WaypointShareConfirmScreen.forSharedWaypoint(this, entry.row));
        else { Waypoint waypoint = local(key.waypoint()); if (waypoint != null) MinecraftAccess.setScreen(MinecraftClient.getInstance(), new WaypointShareConfirmScreen(this, waypoint, WaypointShareConfirmScreen.Target.CHAT)); }
    }
    private boolean teleportAllowed(Entry entry) { return TeleportCommandAccess.evaluate(MinecraftClient.getInstance(), app.config().teleportCommand, app.gameBridge().session(), world, entry.row.dimensionId(), true).available(); }
    private void teleport(WaypointMediaService.Key key) { Entry entry = resolve(key); if (entry != null && teleportAllowed(entry)) app.groundTeleportService().teleportExact(entry.row.x(), entry.row.y(), entry.row.z(), entry.row.dimensionId(), world); }
    @Override
    //#if MC>=12109
    //$$ public boolean mouseClicked(Click click, boolean doubledClick) { double mx = click.x(), my = click.y(); int button = click.button();
    //#else
    public boolean mouseClicked(double mx, double my, int button) {
    //#endif
        if (isClosing()) return true;
        if ((options.hoverActions || pinnedPanel) && insidePanel(mx, my)) {
            if (button == MouseButtons.LEFT) {
                for (Action action : List.copyOf(actions)) if (action.active && action.contains(mx, my)) { action.run.run(); break; }
            }
            return true;
        }
        if (button == MouseButtons.RIGHT && layout != null) {
            var cell = layout.at(mx, my);
            if (cell != null) {
                hover(entries.get(cell.index()).key(world));
                pinnedPanel = true;
                return true;
            }
        }
        if (pinnedPanel) { pinnedPanel = false; hover(null); }
        //#if MC>=12109
        //$$ return super.mouseClicked(click, doubledClick);
        //#else
        return super.mouseClicked(mx, my, button);
        //#endif
    }
    @Override
    //#if MC>=12002
    //$$ public boolean mouseScrolled(double mx, double my, double horizontal, double amount) {
    //#else
    public boolean mouseScrolled(double mx, double my, double amount) {
    //#endif
        if (isClosing()) return true;
        if (layout != null && layout.contains(mx, my) && !((options.hoverActions || pinnedPanel) && hovered != null && insidePanel(mx, my))) { scroll = Math.max(0, scroll - (int) Math.signum(amount)); pinnedPanel = false; hover(null); rebuild(); return true; }
        //#if MC>=12002
        //$$ return super.mouseScrolled(mx, my, horizontal, amount);
        //#else
        return super.mouseScrolled(mx, my, amount);
        //#endif
    }
    private WaypointListMemory.State browsingState() {
        return new WaypointListMemory.State((filterGroup == null ? shared : filterShared) ? "PUBLIC" : "LOCAL", dimension.mode().name(), dimension.dimension() == null ? null : dimension.dimension().toString(), filterGroup, search == null ? query : search.getText(), normal.browsingState().nativeScroll(), scroll);
    }
    Screen layoutAfterSettings() {
        if (options.compact) return this;
        normal.restoreBrowsingState(browsingState());
        return normal;
    }
    private void saveState() {
        memory.put(world, browsingState());
    }
    @Override public void removed() { if (search != null) query = search.getText(); normal.rememberCompactScroll(scroll); saveState(); preview.close(); super.removed(); }
    @Override public void onClose() { requestClose(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
