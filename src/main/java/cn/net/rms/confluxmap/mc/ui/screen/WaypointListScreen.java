package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.bridge.GameBridge;
import cn.net.rms.confluxmap.bridge.PlayerView;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointClientState;
import cn.net.rms.confluxmap.core.shared.SharedWaypoint;
import cn.net.rms.confluxmap.core.waypoint.DimensionScale;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import cn.net.rms.confluxmap.core.waypoint.WaypointService;
import cn.net.rms.confluxmap.core.waypoint.WaypointStore;
import cn.net.rms.confluxmap.mc.net.shared.SharedWaypointClient;
import cn.net.rms.confluxmap.mc.render.RenderUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.math.MathHelper;

/** Management UI with separate local, unlocked-public, and operator-locked views. */
public final class WaypointListScreen extends Screen {
    public enum Tab { LOCAL, PUBLIC, LOCKED }

    private static final int ROW_HEIGHT = 22;
    private static final int LIST_TOP = 56;
    private static final int BOTTOM_MARGIN = 34;
    private static final int MARGIN = 8;
    private static final int SWATCH_SIZE = 12;
    private static final int DELETE_WIDTH = 52;
    private static final int ACTION_WIDTH = 58;
    private static final int DIM_WIDTH = 66;
    private static final int DIST_WIDTH = 58;
    private static final int GAP = 4;

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

        String label() {
            if (local != null) {
                return local.group.isEmpty() ? local.name : "[" + local.group + "] " + local.name;
            }
            return shared.name() + " - " + shared.publisherName();
        }

        int color() {
            return local != null ? local.colorArgb : shared.colorArgb();
        }
    }

    private final GameBridge gameBridge;
    private final WaypointService waypointService;
    private final SharedWaypointClient sharedWaypoints;
    private final Screen parent;
    private Tab tab;

    private int scrollOffset;
    private UUID pendingDeleteId;
    private List<RowInfo> rows = new ArrayList<>();
    private long lastSharedRevision = Long.MIN_VALUE;
    private SharedWaypointClientState.State lastSharedState;
    private boolean lastSharedSynchronized;
    private boolean lastOperator;

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
        if (tab != Tab.LOCAL
            && (lastSharedRevision != sharedWaypoints.revision()
                || lastSharedState != sharedWaypoints.state()
                || lastSharedSynchronized != sharedWaypoints.isSynchronized()
                || lastOperator != sharedWaypoints.isOperator())) {
            rebuild();
        }
    }

    private void rebuild() {
        clearChildren();
        rows.clear();
        addTabs();

        final int bottom = height - BOTTOM_MARGIN;
        final int visibleRowCount = Math.max(1, (bottom - LIST_TOP) / ROW_HEIGHT);
        final Optional<PlayerView> playerView = gameBridge.player();
        final DimensionId currentDimension = gameBridge.session().dimension();
        final double px = playerView.map(PlayerView::x).orElse(0.0);
        final double py = playerView.map(PlayerView::y).orElse(0.0);
        final double pz = playerView.map(PlayerView::z).orElse(0.0);

        final List<RowInfo> sorted = buildRows(currentDimension, px, py, pz);
        sorted.sort((a, b) -> Double.compare(a.distance(), b.distance()));
        scrollOffset = MathHelper.clamp(scrollOffset, 0, Math.max(0, sorted.size() - visibleRowCount));

        final int end = Math.min(sorted.size(), scrollOffset + visibleRowCount);
        for (int i = scrollOffset; i < end; i++) {
            final RowInfo source = sorted.get(i);
            final RowInfo row = new RowInfo(
                source.local(), source.shared(), LIST_TOP + (i - scrollOffset) * ROW_HEIGHT,
                source.distance(), source.dimensionText()
            );
            rows.add(row);
            addRowWidgets(row);
        }

        if (tab != Tab.LOCKED) {
            final ButtonWidget create = addDrawableChild(new ButtonWidget(
                MARGIN,
                height - 24,
                104,
                18,
                new TranslatableText(
                    tab == Tab.LOCAL
                        ? "confluxmap.screen.waypoints.create_local"
                        : "confluxmap.screen.waypoints.create_public"
                ),
                button -> openCreate()
            ));
            if (tab == Tab.PUBLIC) {
                create.active = sharedWaypoints.state() == SharedWaypointClientState.State.ENABLED
                    && sharedWaypoints.isSynchronized();
            }
        }
        addDrawableChild(new ButtonWidget(
            width - MARGIN - 80,
            height - 24,
            80,
            18,
            new TranslatableText("confluxmap.screen.waypoint.done"),
            button -> onClose()
        ));

        lastSharedRevision = sharedWaypoints.revision();
        lastSharedState = sharedWaypoints.state();
        lastSharedSynchronized = sharedWaypoints.isSynchronized();
        lastOperator = sharedWaypoints.isOperator();
    }

    private void addTabs() {
        final int tabWidth = Math.max(64, Math.min(100, (width - MARGIN * 2 - GAP * 2) / 3));
        final int left = width / 2 - (tabWidth * 3 + GAP * 2) / 2;
        final Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            final Tab candidate = tabs[i];
            final ButtonWidget button = addDrawableChild(new ButtonWidget(
                left + i * (tabWidth + GAP),
                28,
                tabWidth,
                20,
                new TranslatableText(tabKey(candidate)),
                ignored -> selectTab(candidate)
            ));
            button.active = candidate != tab;
        }
    }

    private List<RowInfo> buildRows(
        final DimensionId currentDimension,
        final double px,
        final double py,
        final double pz
    ) {
        final List<RowInfo> result = new ArrayList<>();
        if (tab == Tab.LOCAL) {
            for (final Waypoint waypoint : waypointService.list()) {
                result.add(new RowInfo(
                    waypoint,
                    null,
                    0,
                    distance(waypoint.dimensionId, waypoint.x, waypoint.y, waypoint.z, currentDimension, px, py, pz),
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
                    currentDimension, px, py, pz
                ),
                dimensionLabel(waypoint.dimensionId())
            ));
        }
        return result;
    }

    private void addRowWidgets(final RowInfo row) {
        final int rowRight = width - MARGIN;
        final int deleteX = rowRight - DELETE_WIDTH;
        final int secondaryX = deleteX - GAP - ACTION_WIDTH;
        final int primaryX = secondaryX - GAP - ACTION_WIDTH;
        final boolean compact = width < 520;
        final int metadataWidth = compact ? 0 : DIST_WIDTH + DIM_WIDTH + GAP * 2;
        final int nameX = MARGIN + SWATCH_SIZE + GAP;
        final int nameWidth = Math.max(24, primaryX - GAP - metadataWidth - nameX);

        if (row.local() != null) {
            final Waypoint waypoint = row.local();
            addDrawableChild(new ButtonWidget(
                nameX, row.y(), nameWidth, ROW_HEIGHT - 2, Text.of(row.label()), button -> openEdit(waypoint)
            ));
            addDrawableChild(new ButtonWidget(
                primaryX,
                row.y(),
                ACTION_WIDTH,
                ROW_HEIGHT - 2,
                new TranslatableText(
                    waypoint.visible
                        ? "confluxmap.screen.waypoints.hide"
                        : "confluxmap.screen.waypoints.show"
                ),
                button -> toggleVisible(waypoint)
            ));
            addDrawableChild(new ButtonWidget(
                secondaryX,
                row.y(),
                ACTION_WIDTH,
                ROW_HEIGHT - 2,
                new TranslatableText("confluxmap.screen.waypoints.share"),
                button -> MinecraftClient.getInstance().setScreen(new WaypointShareMenuScreen(this, waypoint))
            ));
        } else {
            final SharedWaypoint waypoint = row.shared();
            addDrawableChild(new ButtonWidget(
                primaryX,
                row.y(),
                ACTION_WIDTH,
                ROW_HEIGHT - 2,
                new TranslatableText("confluxmap.screen.waypoints.chat"),
                button -> openSharedChat(waypoint)
            ));
            final ButtonWidget lock = addDrawableChild(new ButtonWidget(
                secondaryX,
                row.y(),
                ACTION_WIDTH,
                ROW_HEIGHT - 2,
                new TranslatableText(
                    waypoint.locked()
                        ? "confluxmap.screen.waypoints.unlock"
                        : "confluxmap.screen.waypoints.lock"
                ),
                button -> setLocked(waypoint, !waypoint.locked())
            ));
            lock.active = sharedWaypoints.isOperator();
        }

        final boolean pendingThis = row.id().equals(pendingDeleteId);
        final ButtonWidget delete = addDrawableChild(new ButtonWidget(
            deleteX,
            row.y(),
            DELETE_WIDTH,
            ROW_HEIGHT - 2,
            new TranslatableText(
                pendingThis
                    ? "confluxmap.screen.waypoints.confirm"
                    : "confluxmap.screen.waypoints.delete"
            ),
            button -> delete(row)
        ));
        delete.active = row.shared() == null || !row.shared().locked() || sharedWaypoints.isOperator();
    }

    private void selectTab(final Tab selected) {
        tab = selected;
        scrollOffset = 0;
        pendingDeleteId = null;
        rebuild();
    }

    private void openCreate() {
        final Optional<PlayerView> playerView = gameBridge.player();
        final DimensionId dimension = gameBridge.session().dimension();
        final double x = Math.floor(playerView.map(PlayerView::x).orElse(0.0));
        final double y = Math.floor(playerView.map(PlayerView::y).orElse(64.0));
        final double z = Math.floor(playerView.map(PlayerView::z).orElse(0.0));
        MinecraftClient.getInstance().setScreen(
            tab == Tab.PUBLIC
                ? WaypointEditScreen.forPublicCreate(this, dimension, x, y, z)
                : WaypointEditScreen.forCreate(this, dimension, x, y, z)
        );
    }

    private void openEdit(final Waypoint waypoint) {
        MinecraftClient.getInstance().setScreen(WaypointEditScreen.forEdit(this, waypoint));
    }

    private void openSharedChat(final SharedWaypoint waypoint) {
        MinecraftClient.getInstance().setScreen(new WaypointShareConfirmScreen(
            this,
            displayCopy(waypoint),
            WaypointShareConfirmScreen.Target.CHAT
        ));
    }

    private void toggleVisible(final Waypoint waypoint) {
        final WaypointStore store = waypointService.current();
        if (store == null) {
            return;
        }
        final Waypoint updated = waypoint.copy();
        updated.visible = !updated.visible;
        store.update(updated);
        pendingDeleteId = null;
        rebuild();
    }

    private void setLocked(final SharedWaypoint waypoint, final boolean locked) {
        pendingDeleteId = null;
        sharedWaypoints.setLocked(waypoint, locked);
    }

    private void delete(final RowInfo row) {
        if (!row.id().equals(pendingDeleteId)) {
            pendingDeleteId = row.id();
            rebuild();
            return;
        }
        if (row.local() != null) {
            final WaypointStore store = waypointService.current();
            if (store != null) {
                store.remove(row.local().id);
            }
        } else {
            sharedWaypoints.delete(row.shared());
        }
        pendingDeleteId = null;
        rebuild();
    }

    @Override
    public boolean mouseScrolled(final double mouseX, final double mouseY, final double amount) {
        if (amount != 0) {
            scrollOffset -= (int) Math.signum(amount);
            rebuild();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public void render(final MatrixStack matrices, final int mouseX, final int mouseY, final float tickDelta) {
        renderBackground(matrices);
        final String title = getTitle().getString();
        textRenderer.drawWithShadow(
            matrices, title, width / 2f - textRenderer.getWidth(title) / 2f, 10, 0xFFFFFFFF
        );

        final boolean compact = width < 520;
        for (final RowInfo row : rows) {
            RenderUtil.fillRect(
                matrices, MARGIN, row.y() + 3, SWATCH_SIZE, SWATCH_SIZE, row.color() | 0xFF000000
            );
            if (row.shared() != null) {
                final int nameX = MARGIN + SWATCH_SIZE + GAP;
                final int actionsWidth = DELETE_WIDTH + ACTION_WIDTH * 2 + GAP * 3;
                final int metadataWidth = compact ? 0 : DIST_WIDTH + DIM_WIDTH + GAP * 2;
                final int maxNameWidth = Math.max(24, width - MARGIN - actionsWidth - metadataWidth - nameX);
                final String label = textRenderer.trimToWidth(row.label(), maxNameWidth);
                textRenderer.drawWithShadow(matrices, label, nameX, row.y() + 6, 0xFFFFFFFF);
            }
            if (!compact) {
                final int rowRight = width - MARGIN;
                final int deleteX = rowRight - DELETE_WIDTH;
                final int secondaryX = deleteX - GAP - ACTION_WIDTH;
                final int primaryX = secondaryX - GAP - ACTION_WIDTH;
                final int dimX = primaryX - GAP - DIM_WIDTH;
                final int distX = dimX - GAP - DIST_WIDTH;
                final String distance = textRenderer.trimToWidth(formatDistance(row.distance()), DIST_WIDTH);
                textRenderer.drawWithShadow(matrices, distance, distX, row.y() + 6, 0xFFCCCCCC);
                final String dimension = textRenderer.trimToWidth(row.dimensionText(), DIM_WIDTH);
                textRenderer.drawWithShadow(
                    matrices, dimension, dimX, row.y() + 6, 0xFFCCCCCC
                );
            }
        }
        if (rows.isEmpty()) {
            final String empty = textRenderer.trimToWidth(
                new TranslatableText(emptyKey()).getString(), Math.max(40, width - 24)
            );
            textRenderer.drawWithShadow(
                matrices, empty, width / 2f - textRenderer.getWidth(empty) / 2f, LIST_TOP + 4, 0xFFAAAAAA
            );
        }

        super.render(matrices, mouseX, mouseY, tickDelta);
    }

    private String emptyKey() {
        if (tab == Tab.LOCAL) {
            return "confluxmap.screen.waypoints.empty_local";
        }
        return switch (sharedWaypoints.state()) {
            case UNKNOWN, HANDSHAKE -> "confluxmap.shared_waypoints.status.connecting";
            case UNSUPPORTED -> "confluxmap.shared_waypoints.status.unsupported";
            case SUPPORTED_DISABLED -> "confluxmap.shared_waypoints.status.disabled";
            case ENABLED -> sharedWaypoints.isSynchronized()
                ? "confluxmap.screen.waypoints.empty_public"
                : "confluxmap.shared_waypoints.status.syncing";
        };
    }

    private static double distance(
        final DimensionId waypointDimension,
        final double x,
        final double y,
        final double z,
        final DimensionId currentDimension,
        final double px,
        final double py,
        final double pz
    ) {
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
        return new TranslatableText("confluxmap.value.blocks", Math.round(distance)).getString();
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
