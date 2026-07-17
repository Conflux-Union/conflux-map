package cn.net.rms.confluxmap.mc.ui.hud;

import cn.net.rms.confluxmap.bridge.GameBridge;
import cn.net.rms.confluxmap.bridge.PlayerView;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.TileKey;
import cn.net.rms.confluxmap.core.tile.TileService;
import cn.net.rms.confluxmap.core.util.TileMath;
import cn.net.rms.confluxmap.mc.render.RenderUtil;
import cn.net.rms.confluxmap.mc.render.TileTextureManager;
import java.util.Optional;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3f;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.biome.Biome;

/**
 * Renders the always-on minimap HUD showing {@link MapLayer#SURFACE}.
 * Supports zoom (0.5/1/2/4 blocks per pixel), player-facing-up rotation
 * (map rotates, arrow stays up) or north-locked mode (arrow rotates),
 * square scissor or circular alpha-mask clipping, a center arrow,
 * upright cardinal letters, and a coordinates/biome info line.
 * Render thread only (it's an {@link HudRenderCallback}).
 */
public final class MinimapHudRenderer {
    private static final int MARGIN = 4;
    private static final int BORDER_THICKNESS = 1;
    private static final int BORDER_COLOR = 0xB0FFFFFF;
    private static final int BACKGROUND_COLOR = 0x80101018;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int ARROW_OUTLINE = 0xFF101010;
    private static final int ARROW_FILL = 0xFFFFFFFF;
    private static final float[] BLOCKS_PER_PIXEL = {0.5f, 1f, 2f, 4f};

    private final MinecraftClient client;
    private final ConfluxConfig config;
    private final GameBridge gameBridge;
    private final TileService tiles;
    private final TileTextureManager textures;

    public MinimapHudRenderer(
        final MinecraftClient client,
        final ConfluxConfig config,
        final GameBridge gameBridge,
        final TileService tiles,
        final TileTextureManager textures
    ) {
        this.client = client;
        this.config = config;
        this.gameBridge = gameBridge;
        this.tiles = tiles;
        this.textures = textures;
    }

    public void register() {
        HudRenderCallback.EVENT.register(this::render);
    }

    private void render(final MatrixStack matrices, final float tickDelta) {
        if (!config.minimapEnabled || !gameBridge.session().active()) {
            return;
        }
        final Optional<PlayerView> playerView = gameBridge.player(tickDelta);
        if (playerView.isEmpty()) {
            return;
        }
        final PlayerView player = playerView.get();

        textures.beginFrame();
        tiles.setViewpoint(player.blockX(), player.blockZ());

        final int size = config.minimapSize;
        final int x0 = originX(client.getWindow().getScaledWidth(), size);
        final int y0 = originY(client.getWindow().getScaledHeight(), size);
        final float centerX = x0 + size / 2f;
        final float centerY = y0 + size / 2f;
        final boolean circle = config.minimapShape == ConfluxConfig.Shape.CIRCLE;
        final boolean rotate = config.minimapRotate;
        final float mapAngle = rotate ? 180f - player.yawDegrees() : 0f;

        if (circle) {
            RenderUtil.stampCircleAlpha(matrices, centerX, centerY, size / 2f);
            RenderUtil.beginMaskedQuads();
        } else {
            RenderUtil.fillRect(matrices, x0, y0, size, size, BACKGROUND_COLOR);
            RenderUtil.enableScissor(client, x0, y0, size, size);
            RenderUtil.beginTexturedQuads();
        }

        matrices.push();
        matrices.translate(centerX, centerY, 0);
        if (rotate) {
            matrices.multiply(Vec3f.POSITIVE_Z.getDegreesQuaternion(mapAngle));
        }
        drawTiles(matrices, size, rotate, player);
        matrices.pop();

        if (circle) {
            RenderUtil.endMaskedQuads(matrices, x0, y0, size, size);
            RenderUtil.drawRing(matrices, centerX, centerY, size / 2f, BORDER_THICKNESS, BORDER_COLOR);
        } else {
            RenderUtil.disableScissor();
            drawBorder(matrices, x0, y0, size);
        }

        drawCardinals(matrices, centerX, centerY, size, mapAngle);
        drawPlayerArrow(matrices, centerX, centerY, rotate ? 0f : player.yawDegrees() + 180f);
        drawInfoText(matrices, player, x0, y0, size);
    }

    /**
     * Tiles are drawn as full 256-block quads positioned relative to the player,
     * in a coordinate space whose origin is the minimap center (the caller has
     * already translated/rotated the matrix). Clipping crops the excess.
     */
    private void drawTiles(final MatrixStack matrices, final int size, final boolean rotate, final PlayerView player) {
        final float blocksPerPixel = BLOCKS_PER_PIXEL[config.minimapZoomIndex];
        final float pxPerBlock = 1f / blocksPerPixel;
        final float coverRadius = size / 2f * blocksPerPixel * (rotate ? 1.4143f : 1f) + 8f;

        final int firstTileX = TileMath.blockToTile((int) Math.floor(player.x() - coverRadius));
        final int lastTileX = TileMath.blockToTile((int) Math.ceil(player.x() + coverRadius));
        final int firstTileZ = TileMath.blockToTile((int) Math.floor(player.z() - coverRadius));
        final int lastTileZ = TileMath.blockToTile((int) Math.ceil(player.z() + coverRadius));

        for (int tileX = firstTileX; tileX <= lastTileX; tileX++) {
            for (int tileZ = firstTileZ; tileZ <= lastTileZ; tileZ++) {
                final TileKey key = new TileKey(
                    gameBridge.session().world(), gameBridge.session().dimension(),
                    MapLayer.SURFACE.cacheId(), 0, tileX, tileZ
                );
                if (!textures.bind(key)) {
                    continue;
                }
                final float screenX = (float) ((key.originBlockX() - player.x()) * pxPerBlock);
                final float screenY = (float) ((key.originBlockZ() - player.z()) * pxPerBlock);
                final float quadSize = TileMath.TILE_SIZE * pxPerBlock;
                RenderUtil.drawQuad(matrices, screenX, screenY, quadSize, quadSize, 0f, 0f, 1f, 1f);
            }
        }
    }

    private void drawPlayerArrow(final MatrixStack matrices, final float centerX, final float centerY, final float angle) {
        matrices.push();
        matrices.translate(centerX, centerY, 0);
        matrices.multiply(Vec3f.POSITIVE_Z.getDegreesQuaternion(angle));
        RenderUtil.fillTriangle(matrices, 0f, -6.5f, -5f, 5.5f, 5f, 5.5f, ARROW_OUTLINE);
        RenderUtil.fillTriangle(matrices, 0f, -5f, -3.5f, 4f, 3.5f, 4f, ARROW_FILL);
        matrices.pop();
    }

    /** Cardinal letters sit on the (possibly rotated) compass ring but are always drawn upright. */
    private void drawCardinals(final MatrixStack matrices, final float centerX, final float centerY, final int size, final float mapAngle) {
        final float radius = size / 2f - 7f;
        final double rad = Math.toRadians(mapAngle);
        final float cos = (float) Math.cos(rad);
        final float sin = (float) Math.sin(rad);
        drawCardinal(matrices, "N", centerX, centerY, 0f, -radius, cos, sin);
        drawCardinal(matrices, "E", centerX, centerY, radius, 0f, cos, sin);
        drawCardinal(matrices, "S", centerX, centerY, 0f, radius, cos, sin);
        drawCardinal(matrices, "W", centerX, centerY, -radius, 0f, cos, sin);
    }

    private void drawCardinal(
        final MatrixStack matrices,
        final String letter,
        final float centerX,
        final float centerY,
        final float dirX,
        final float dirY,
        final float cos,
        final float sin
    ) {
        final float x = centerX + dirX * cos - dirY * sin;
        final float y = centerY + dirX * sin + dirY * cos;
        final int width = client.textRenderer.getWidth(letter);
        client.textRenderer.drawWithShadow(matrices, letter, x - width / 2f, y - 4f, TEXT_COLOR);
    }

    private void drawInfoText(final MatrixStack matrices, final PlayerView player, final int x0, final int y0, final int size) {
        if (!config.showCoordinates && !config.showBiome) {
            return;
        }
        final boolean topCorner = config.minimapCorner == ConfluxConfig.Corner.TOP_LEFT
            || config.minimapCorner == ConfluxConfig.Corner.TOP_RIGHT;
        final int lineHeight = 10;
        int lines = 0;
        if (config.showCoordinates) {
            lines++;
        }
        if (config.showBiome) {
            lines++;
        }
        float y = topCorner ? y0 + size + 3 : y0 - lines * lineHeight - 3;
        final float centerX = x0 + size / 2f;

        if (config.showCoordinates) {
            final String coords = player.blockX() + ", " + player.blockY() + ", " + player.blockZ();
            drawCenteredLine(matrices, coords, centerX, y);
            y += lineHeight;
        }
        if (config.showBiome) {
            final String biome = biomeName(player);
            if (!biome.isEmpty()) {
                drawCenteredLine(matrices, biome, centerX, y);
            }
        }
    }

    private void drawCenteredLine(final MatrixStack matrices, final String text, final float centerX, final float y) {
        final int width = client.textRenderer.getWidth(text);
        client.textRenderer.drawWithShadow(matrices, text, centerX - width / 2f, y, TEXT_COLOR);
    }

    private String biomeName(final PlayerView player) {
        if (client.world == null) {
            return "";
        }
        final Biome biome = client.world.getBiome(new BlockPos(player.blockX(), player.blockY(), player.blockZ()));
        final Identifier id = client.world.getRegistryManager().get(Registry.BIOME_KEY).getId(biome);
        if (id == null) {
            return "";
        }
        final Text name = new TranslatableText("biome." + id.getNamespace() + "." + id.getPath());
        return name.getString();
    }

    private void drawBorder(final MatrixStack matrices, final int x0, final int y0, final int size) {
        RenderUtil.fillRect(matrices, x0, y0, size, BORDER_THICKNESS, BORDER_COLOR);
        RenderUtil.fillRect(matrices, x0, y0 + size - BORDER_THICKNESS, size, BORDER_THICKNESS, BORDER_COLOR);
        RenderUtil.fillRect(matrices, x0, y0, BORDER_THICKNESS, size, BORDER_COLOR);
        RenderUtil.fillRect(matrices, x0 + size - BORDER_THICKNESS, y0, BORDER_THICKNESS, size, BORDER_COLOR);
    }

    private int originX(final int screenWidth, final int size) {
        switch (config.minimapCorner) {
            case TOP_LEFT:
            case BOTTOM_LEFT:
                return MARGIN;
            default:
                return screenWidth - size - MARGIN;
        }
    }

    private int originY(final int screenHeight, final int size) {
        switch (config.minimapCorner) {
            case TOP_LEFT:
            case TOP_RIGHT:
                return MARGIN;
            default:
                return screenHeight - size - MARGIN;
        }
    }
}
