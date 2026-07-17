package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.bridge.GameBridge;
import cn.net.rms.confluxmap.bridge.PlayerView;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.TileKey;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.tile.TileService;
import cn.net.rms.confluxmap.core.util.TileMath;
import cn.net.rms.confluxmap.mc.render.RenderUtil;
import cn.net.rms.confluxmap.mc.render.TileTextureManager;
import java.util.Optional;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3f;

/**
 * Fullscreen, panning/zooming explorable map. Opened and closed by the
 * {@code open_map} keybind (M by default); always north-locked (no rotation).
 * View state is continuous: {@link #centerX}/{@link #centerZ} is the world
 * point at screen center, {@link #scale} is blocks-per-screen-pixel, clamped
 * to {@link #MIN_SCALE}-{@link #MAX_SCALE}. The displayed LOD is derived from
 * scale ({@link #currentLod()}) so zooming out smoothly walks up the tile
 * pyramid {@link TileService} composes in core/.
 *
 * <p>Does not call {@link TileTextureManager#beginFrame()} itself - see the
 * javadoc on {@code MinimapHudRenderer.render} for why that would double it up.
 */
public final class FullscreenMapScreen extends Screen {
    private static final double MIN_SCALE = 0.25;
    private static final double MAX_SCALE = 16.0;
    private static final double DEFAULT_SCALE = 2.0;
    private static final double ZOOM_STEP = 1.26;

    private static final int MARGIN = 6;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int BACKGROUND_COLOR = 0xFF101018;
    private static final int GRID_COLOR = 0x22FFFFFF;
    private static final int ARROW_OUTLINE = 0xFF101010;
    private static final int ARROW_FILL = 0xFFFFE066;
    private static final double MIN_GRID_SPACING_PX = 8.0;

    private final KeyBinding openMapKey;
    private final GameBridge gameBridge;
    private final TileService tiles;
    private final TileTextureManager textures;
    private final FullscreenMapViewState viewState;

    /** World point currently at screen center, and blocks-per-pixel; all mutable, panned/zoomed by input. */
    private double centerX;
    private double centerZ;
    private double scale;

    public FullscreenMapScreen(final KeyBinding openMapKey) {
        super(new TranslatableText("confluxmap.screen.map.title"));
        this.openMapKey = openMapKey;
        final ConfluxMapClient app = ConfluxMapClient.get();
        this.gameBridge = app.gameBridge();
        this.tiles = app.tileService();
        this.textures = app.tileTextureManager();
        this.viewState = app.fullscreenMapViewState();

        final DimensionId dimension = gameBridge.session().dimension();
        final FullscreenMapViewState.View remembered = viewState.get(dimension);
        if (remembered != null) {
            centerX = remembered.centerX();
            centerZ = remembered.centerZ();
            scale = remembered.scale();
        } else {
            final Optional<PlayerView> player = gameBridge.player();
            centerX = player.isPresent() ? player.get().x() : 0.0;
            centerZ = player.isPresent() ? player.get().z() : 0.0;
            scale = DEFAULT_SCALE;
        }
    }

    /** Keep the world (and this session's capture pipeline) running while the map is open. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Funnel point for every close path (ESC via the default {@code keyPressed}, or M below) so the view is always remembered. */
    @Override
    public void onClose() {
        viewState.put(gameBridge.session().dimension(), new FullscreenMapViewState.View(centerX, centerZ, scale));
        super.onClose();
    }

    @Override
    public boolean keyPressed(final int keyCode, final int scanCode, final int modifiers) {
        if (openMapKey.matchesKey(keyCode, scanCode)) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseDragged(final double mouseX, final double mouseY, final int button, final double deltaX, final double deltaY) {
        if (button == 0) {
            // Opposite the drag direction, 1:1 in world-space at the current scale (§4 pan mechanics).
            centerX -= deltaX * scale;
            centerZ -= deltaY * scale;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseScrolled(final double mouseX, final double mouseY, final double amount) {
        if (amount == 0) {
            return false;
        }
        final double oldScale = scale;
        final double newScale = MathHelper.clamp(oldScale * (amount > 0 ? 1.0 / ZOOM_STEP : ZOOM_STEP), MIN_SCALE, MAX_SCALE);
        if (newScale != oldScale) {
            // Cursor-anchored: keep the world point under the cursor fixed on screen.
            final double cursorWorldX = centerX + (mouseX - width / 2.0) * oldScale;
            final double cursorWorldZ = centerZ + (mouseY - height / 2.0) * oldScale;
            centerX = cursorWorldX - (mouseX - width / 2.0) * newScale;
            centerZ = cursorWorldZ - (mouseY - height / 2.0) * newScale;
            scale = newScale;
        }
        return true;
    }

    @Override
    public void render(final MatrixStack matrices, final int mouseX, final int mouseY, final float tickDelta) {
        tiles.setViewpoint((int) Math.floor(centerX), (int) Math.floor(centerZ));

        RenderUtil.fillRect(matrices, 0, 0, width, height, BACKGROUND_COLOR);
        drawGrid(matrices);

        RenderUtil.beginTexturedQuads();
        drawTiles(matrices);

        drawPlayerMarker(matrices, tickDelta);
        drawDimensionLabel(matrices);
        drawScaleLabel(matrices);
        drawCursorCoords(matrices, mouseX, mouseY);

        super.render(matrices, mouseX, mouseY, tickDelta);
    }

    private int currentLod() {
        return MathHelper.clamp((int) Math.floor(Math.log(scale) / Math.log(2)), 0, TileMath.MAX_LOD);
    }

    private void drawTiles(final MatrixStack matrices) {
        final int lod = currentLod();
        final double pxPerBlock = 1.0 / scale;
        final double blocksPerTile = TileMath.blocksPerTile(lod);
        final double halfWidthBlocks = width / 2.0 * scale;
        final double halfHeightBlocks = height / 2.0 * scale;

        final int firstTileX = TileMath.blockToTile((int) Math.floor(centerX - halfWidthBlocks), lod);
        final int lastTileX = TileMath.blockToTile((int) Math.ceil(centerX + halfWidthBlocks), lod);
        final int firstTileZ = TileMath.blockToTile((int) Math.floor(centerZ - halfHeightBlocks), lod);
        final int lastTileZ = TileMath.blockToTile((int) Math.ceil(centerZ + halfHeightBlocks), lod);

        final SessionGuard.Session session = gameBridge.session();
        for (int tileZ = firstTileZ; tileZ <= lastTileZ; tileZ++) {
            for (int tileX = firstTileX; tileX <= lastTileX; tileX++) {
                final TileKey key = new TileKey(session.world(), session.dimension(), MapLayer.SURFACE.cacheId(), lod, tileX, tileZ);
                if (!textures.bind(key)) {
                    continue;
                }
                final float screenX = (float) (width / 2.0 + (key.originBlockX() - centerX) * pxPerBlock);
                final float screenY = (float) (height / 2.0 + (key.originBlockZ() - centerZ) * pxPerBlock);
                final float quadSize = (float) (blocksPerTile * pxPerBlock);
                RenderUtil.drawQuad(matrices, screenX, screenY, quadSize, quadSize, 0f, 0f, 1f, 1f);
            }
        }
    }

    /** Faint lines on LOD-0 tile boundaries (256-block spacing), skipped once they'd be denser than {@link #MIN_GRID_SPACING_PX}. */
    private void drawGrid(final MatrixStack matrices) {
        final double pxPerBlock = 1.0 / scale;
        final double spacingPx = TileMath.TILE_SIZE * pxPerBlock;
        if (spacingPx < MIN_GRID_SPACING_PX) {
            return;
        }
        final int firstLineX = TileMath.blockToTile((int) Math.floor(centerX - width / 2.0 * scale));
        final int lastLineX = TileMath.blockToTile((int) Math.ceil(centerX + width / 2.0 * scale));
        for (int tx = firstLineX; tx <= lastLineX + 1; tx++) {
            final float screenX = (float) (width / 2.0 + (tx * (double) TileMath.TILE_SIZE - centerX) * pxPerBlock);
            RenderUtil.fillRect(matrices, screenX, 0f, 1f, height, GRID_COLOR);
        }
        final int firstLineZ = TileMath.blockToTile((int) Math.floor(centerZ - height / 2.0 * scale));
        final int lastLineZ = TileMath.blockToTile((int) Math.ceil(centerZ + height / 2.0 * scale));
        for (int tz = firstLineZ; tz <= lastLineZ + 1; tz++) {
            final float screenY = (float) (height / 2.0 + (tz * (double) TileMath.TILE_SIZE - centerZ) * pxPerBlock);
            RenderUtil.fillRect(matrices, 0f, screenY, width, 1f, GRID_COLOR);
        }
    }

    /** Always north-locked, so only the arrow itself rotates with the player's facing (mirrors {@code MinimapHudRenderer}'s north-locked mode). */
    private void drawPlayerMarker(final MatrixStack matrices, final float tickDelta) {
        final Optional<PlayerView> playerView = gameBridge.player(tickDelta);
        if (playerView.isEmpty()) {
            return;
        }
        final PlayerView player = playerView.get();
        final double pxPerBlock = 1.0 / scale;
        final float screenX = (float) (width / 2.0 + (player.x() - centerX) * pxPerBlock);
        final float screenY = (float) (height / 2.0 + (player.z() - centerZ) * pxPerBlock);
        matrices.push();
        matrices.translate(screenX, screenY, 0);
        matrices.multiply(Vec3f.POSITIVE_Z.getDegreesQuaternion(player.yawDegrees() + 180f));
        RenderUtil.fillTriangle(matrices, 0f, -7f, -5.5f, 6f, 5.5f, 6f, ARROW_OUTLINE);
        RenderUtil.fillTriangle(matrices, 0f, -5.5f, -4f, 4.5f, 4f, 4.5f, ARROW_FILL);
        matrices.pop();
    }

    private void drawDimensionLabel(final MatrixStack matrices) {
        final String text = dimensionDisplayName(gameBridge.session().dimension());
        textRenderer.drawWithShadow(matrices, text, MARGIN, MARGIN, TEXT_COLOR);
    }

    private void drawScaleLabel(final MatrixStack matrices) {
        final String text = new TranslatableText("confluxmap.map.scale", String.format("%.2f", scale)).getString();
        final int textWidth = textRenderer.getWidth(text);
        textRenderer.drawWithShadow(matrices, text, width - MARGIN - textWidth, MARGIN, TEXT_COLOR);
    }

    private void drawCursorCoords(final MatrixStack matrices, final int mouseX, final int mouseY) {
        final double worldX = centerX + (mouseX - width / 2.0) * scale;
        final double worldZ = centerZ + (mouseY - height / 2.0) * scale;
        final String text = (int) Math.floor(worldX) + ", " + (int) Math.floor(worldZ);
        final int textWidth = textRenderer.getWidth(text);
        textRenderer.drawWithShadow(matrices, text, width / 2f - textWidth / 2f, height - MARGIN - 10, TEXT_COLOR);
    }

    private static String dimensionDisplayName(final DimensionId dimension) {
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
