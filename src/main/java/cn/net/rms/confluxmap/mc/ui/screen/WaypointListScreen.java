package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.bridge.GameBridge;
import cn.net.rms.confluxmap.bridge.PlayerView;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.waypoint.DimensionScale;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import cn.net.rms.confluxmap.core.waypoint.WaypointService;
import cn.net.rms.confluxmap.core.waypoint.WaypointStore;
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

/**
 * Management list for every waypoint in the current world/server (all
 * dimensions - the dimension column exists precisely so this screen isn't
 * restricted to the one the player currently happens to be in). Sorted by
 * 3-D straight-line distance from the player, coordinates converted into the
 * player's current dimension via {@link DimensionScale} for that purpose.
 * Opened by the {@code key.confluxmap.waypoints} keybind (U by default).
 */
public final class WaypointListScreen extends Screen {
    private static final int ROW_HEIGHT = 22;
    private static final int LIST_TOP = 34;
    private static final int BOTTOM_MARGIN = 30;
    private static final int MARGIN = 8;
    private static final int SWATCH_SIZE = 12;
    private static final int DELETE_WIDTH = 46;
    private static final int TOGGLE_WIDTH = 56;
    private static final int DIM_WIDTH = 66;
    private static final int DIST_WIDTH = 56;
    private static final int GAP = 4;

    private record RowInfo(Waypoint waypoint, int y, double distance, String dimensionText) {
    }

    private final GameBridge gameBridge;
    private final WaypointService waypointService;

    private int scrollOffset;
    private UUID pendingDeleteId;
    private List<RowInfo> rows = new ArrayList<>();

    public WaypointListScreen() {
        super(new TranslatableText("confluxmap.screen.waypoints.title"));
        final ConfluxMapClient app = ConfluxMapClient.get();
        this.gameBridge = app.gameBridge();
        this.waypointService = app.waypointService();
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
        rows.clear();

        final int bottom = height - BOTTOM_MARGIN;
        final int visibleRowCount = Math.max(1, (bottom - LIST_TOP) / ROW_HEIGHT);

        final List<Waypoint> all = waypointService.list();
        final Optional<PlayerView> playerView = gameBridge.player();
        final DimensionId currentDimension = gameBridge.session().dimension();
        final double px = playerView.map(PlayerView::x).orElse(0.0);
        final double py = playerView.map(PlayerView::y).orElse(0.0);
        final double pz = playerView.map(PlayerView::z).orElse(0.0);

        final List<RowInfo> sorted = new ArrayList<>(all.size());
        for (final Waypoint waypoint : all) {
            final double dx = DimensionScale.convertHorizontal(waypoint.x, waypoint.dimensionId, currentDimension) - px;
            final double dz = DimensionScale.convertHorizontal(waypoint.z, waypoint.dimensionId, currentDimension) - pz;
            final double dy = waypoint.y - py;
            final double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            sorted.add(new RowInfo(waypoint, 0, distance, dimensionLabel(waypoint.dimensionId)));
        }
        sorted.sort((a, b) -> Double.compare(a.distance(), b.distance()));

        scrollOffset = MathHelper.clamp(scrollOffset, 0, Math.max(0, sorted.size() - visibleRowCount));

        final int end = Math.min(sorted.size(), scrollOffset + visibleRowCount);
        for (int i = scrollOffset; i < end; i++) {
            final RowInfo info = sorted.get(i);
            final int y = LIST_TOP + (i - scrollOffset) * ROW_HEIGHT;
            rows.add(new RowInfo(info.waypoint(), y, info.distance(), info.dimensionText()));
            addRowWidgets(info.waypoint(), y);
        }

        addDrawableChild(new ButtonWidget(
            MARGIN, 8, 90, 18, new TranslatableText("confluxmap.screen.waypoints.create"), b -> openCreate()
        ));
        addDrawableChild(new ButtonWidget(
            width - MARGIN - 80, height - 24, 80, 18, new TranslatableText("confluxmap.screen.waypoint.done"), b -> onClose()
        ));
    }

    private void addRowWidgets(final Waypoint waypoint, final int y) {
        final int rowRight = width - MARGIN;
        final int deleteX = rowRight - DELETE_WIDTH;
        final int toggleX = deleteX - GAP - TOGGLE_WIDTH;
        final int dimX = toggleX - GAP - DIM_WIDTH;
        final int distX = dimX - GAP - DIST_WIDTH;
        final int nameX = MARGIN + SWATCH_SIZE + GAP;
        final int nameWidth = Math.max(20, distX - GAP - nameX);

        addDrawableChild(new ButtonWidget(
            nameX, y, nameWidth, ROW_HEIGHT - 2, Text.of(rowLabel(waypoint)), b -> openEdit(waypoint)
        ));

        final boolean pendingThis = waypoint.id.equals(pendingDeleteId);
        addDrawableChild(new ButtonWidget(
            toggleX, y, TOGGLE_WIDTH, ROW_HEIGHT - 2,
            new TranslatableText(waypoint.visible ? "confluxmap.screen.waypoints.hide" : "confluxmap.screen.waypoints.show"),
            b -> toggleVisible(waypoint)
        ));
        addDrawableChild(new ButtonWidget(
            deleteX, y, DELETE_WIDTH, ROW_HEIGHT - 2,
            new TranslatableText(pendingThis ? "confluxmap.screen.waypoints.confirm" : "confluxmap.screen.waypoints.delete"),
            b -> delete(waypoint)
        ));
    }

    private static String rowLabel(final Waypoint waypoint) {
        return waypoint.group.isEmpty() ? waypoint.name : "[" + waypoint.group + "] " + waypoint.name;
    }

    private void openCreate() {
        final Optional<PlayerView> playerView = gameBridge.player();
        final DimensionId dimension = gameBridge.session().dimension();
        final double x = playerView.map(PlayerView::x).orElse(0.0);
        final double y = playerView.map(PlayerView::y).orElse(64.0);
        final double z = playerView.map(PlayerView::z).orElse(0.0);
        MinecraftClient.getInstance().setScreen(WaypointEditScreen.forCreate(
            this, dimension, Math.floor(x), Math.floor(y), Math.floor(z)
        ));
    }

    private void openEdit(final Waypoint waypoint) {
        MinecraftClient.getInstance().setScreen(WaypointEditScreen.forEdit(this, waypoint));
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

    private void delete(final Waypoint waypoint) {
        if (waypoint.id.equals(pendingDeleteId)) {
            final WaypointStore store = waypointService.current();
            if (store != null) {
                store.remove(waypoint.id);
            }
            pendingDeleteId = null;
        } else {
            pendingDeleteId = waypoint.id;
        }
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
        textRenderer.drawWithShadow(matrices, title, width / 2f - textRenderer.getWidth(title) / 2f, 12, 0xFFFFFFFF);

        for (final RowInfo row : rows) {
            RenderUtil.fillRect(matrices, MARGIN, row.y() + 3, SWATCH_SIZE, SWATCH_SIZE, row.waypoint().colorArgb | 0xFF000000);
            final int rowRight = width - MARGIN;
            final int deleteX = rowRight - DELETE_WIDTH;
            final int toggleX = deleteX - GAP - TOGGLE_WIDTH;
            final int dimX = toggleX - GAP - DIM_WIDTH;
            final int distX = dimX - GAP - DIST_WIDTH;
            final String distText = formatDistance(row.distance());
            textRenderer.drawWithShadow(matrices, distText, distX, row.y() + 5, 0xFFCCCCCC);
            textRenderer.drawWithShadow(matrices, row.dimensionText(), dimX, row.y() + 5, 0xFFCCCCCC);
        }
        if (rows.isEmpty()) {
            final String empty = new TranslatableText("confluxmap.screen.waypoints.empty").getString();
            textRenderer.drawWithShadow(matrices, empty, width / 2f - textRenderer.getWidth(empty) / 2f, LIST_TOP, 0xFFAAAAAA);
        }

        super.render(matrices, mouseX, mouseY, tickDelta);
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
