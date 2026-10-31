package cn.net.rms.confluxmap.mc.ui.hud;

import cn.net.rms.confluxmap.bridge.GameBridge;
import cn.net.rms.confluxmap.bridge.PlayerView;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.TileKey;
import cn.net.rms.confluxmap.core.tile.TileService;
import cn.net.rms.confluxmap.core.util.ChunkViewport;
import cn.net.rms.confluxmap.core.util.TileMath;
import cn.net.rms.confluxmap.mc.render.GuiShapes;
import cn.net.rms.confluxmap.mc.render.TileTextureManager;
import cn.net.rms.confluxmap.mc.world.LayerSelector;
import java.util.function.Consumer;
import net.minecraft.client.util.math.MatrixStack;

/**
 * Owns the minimap's tile state: the per-frame texture lifecycle, the capture
 * viewport publication that keeps chunk snapshots flowing for exactly the area
 * the minimap shows, and the tile quads themselves.
 */
final class MinimapTilePainter {
    private final TileService tiles;
    private final TileTextureManager textures;
    private final GameBridge gameBridge;
    private final LayerSelector layerSelector;
    private final Consumer<ChunkViewport> captureViewportPublisher;

    MinimapTilePainter(
        final TileService tiles,
        final TileTextureManager textures,
        final GameBridge gameBridge,
        final LayerSelector layerSelector,
        final Consumer<ChunkViewport> captureViewportPublisher
    ) {
        this.tiles = tiles;
        this.textures = textures;
        this.gameBridge = gameBridge;
        this.layerSelector = layerSelector;
        this.captureViewportPublisher = captureViewportPublisher;
    }

    /**
     * The HUD callback fires once per rendered frame regardless of whether a
     * {@link net.minecraft.client.gui.screen.Screen} is open (the HUD layer draws
     * before the screen layer, it just ends up covered). That makes this the single
     * per-frame call site for {@link TileTextureManager#beginFrame()} -
     * {@link cn.net.rms.confluxmap.mc.ui.screen.FullscreenMapScreen} relies on it
     * having already run this frame and never calls it itself, so it's never
     * invoked twice in one frame.
     */
    void beginFrame() {
        textures.beginFrame();
    }

    void setViewpoint(final int blockX, final int blockZ) {
        tiles.setViewpoint(blockX, blockZ);
    }

    /** No minimap surface this frame: release the GPU tile viewport and capture claim. */
    void clearViewport() {
        tiles.clearViewport();
    }

    /** Publishes an empty capture viewport so stale chunks stop flowing in. */
    void publishEmptyViewport() {
        captureViewportPublisher.accept(null);
    }

    /**
     * Tiles are drawn as full 256-block quads positioned relative to the player,
     * in a coordinate space whose origin is the minimap center (the caller has
     * already translated/rotated the matrix). Clipping crops the excess.
     */
    void drawTiles(
        final MatrixStack matrices,
        final int size,
        final boolean circle,
        final float mapAngle,
        final PlayerView player,
        final float blocksPerPixel
    ) {
        final float pxPerBlock = 1f / blocksPerPixel;
        final double coverageFactor = captureCoverageFactor(
            circle ? ConfluxConfig.Shape.CIRCLE : ConfluxConfig.Shape.SQUARE,
            mapAngle
        );
        final float coverRadius = (float) (size / 2f * blocksPerPixel * coverageFactor + 8f);
        captureViewportPublisher.accept(captureViewport(
            player.x(),
            player.z(),
            size,
            blocksPerPixel,
            circle ? ConfluxConfig.Shape.CIRCLE : ConfluxConfig.Shape.SQUARE,
            mapAngle
        ));

        final int firstTileX = TileMath.blockToTile((int) Math.floor(player.x() - coverRadius));
        final int lastTileX = TileMath.blockToTile((int) Math.ceil(player.x() + coverRadius));
        final int firstTileZ = TileMath.blockToTile((int) Math.floor(player.z() - coverRadius));
        final int lastTileZ = TileMath.blockToTile((int) Math.ceil(player.z() + coverRadius));
        final MapLayer layer = layerSelector.current().layer();
        final String layerId = layer.cacheId();
        tiles.setViewport(layer, 0, firstTileX, lastTileX, firstTileZ, lastTileZ);

        for (int tileZ = firstTileZ; tileZ <= lastTileZ; tileZ++) {
            for (int tileX = firstTileX; tileX <= lastTileX; tileX++) {
                final TileKey key = new TileKey(
                    gameBridge.session().world(), gameBridge.session().dimension(),
                    layerId, 0, tileX, tileZ
                );
                if (!textures.bind(key)) {
                    continue;
                }
                final float screenX = (float) ((key.originBlockX() - player.x()) * pxPerBlock);
                final float screenY = (float) ((key.originBlockZ() - player.z()) * pxPerBlock);
                final float quadSize = TileMath.TILE_SIZE * pxPerBlock;
                GuiShapes.drawQuad(matrices, screenX, screenY, quadSize, quadSize, 0f, 0f, 1f, 1f);
            }
        }
    }

    static ChunkViewport captureViewport(
        final double centerX,
        final double centerZ,
        final int size,
        final double blocksPerPixel,
        final ConfluxConfig.Shape shape,
        final double mapAngleDegrees
    ) {
        final double diameter = size * blocksPerPixel
            * captureCoverageFactor(shape, mapAngleDegrees)
            + 16.0;
        return ChunkViewport.covering(centerX, centerZ, 1, 1, diameter);
    }

    private static double captureCoverageFactor(
        final ConfluxConfig.Shape shape, final double mapAngleDegrees
    ) {
        if (shape == ConfluxConfig.Shape.CIRCLE) {
            return 1.0;
        }
        final double radians = Math.toRadians(mapAngleDegrees);
        return Math.abs(Math.cos(radians)) + Math.abs(Math.sin(radians));
    }
}
