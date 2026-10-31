package cn.net.rms.confluxmap.mc.ui.hud;

import cn.net.rms.confluxmap.bridge.PlayerView;
import cn.net.rms.confluxmap.compat.Regs;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.config.MinimapInformationLayout;
import cn.net.rms.confluxmap.mc.render.GuiShapes;
import cn.net.rms.confluxmap.mc.render.TextureBinds;
import cn.net.rms.confluxmap.mc.ui.BiomeLabels;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import cn.net.rms.confluxmap.mc.ui.MapLayerText;
import cn.net.rms.confluxmap.mc.ui.UiResourceTheme;
import cn.net.rms.confluxmap.mc.ui.UiTextureRegion;
import cn.net.rms.confluxmap.mc.world.ClientChunkLookup;
import cn.net.rms.confluxmap.mc.world.LayerSelector;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/** The minimap's chrome: frame/border, cardinal letters, and the info text block. */
final class MinimapChromePainter {
    private static final int BORDER_THICKNESS = 1;
    private static final int BORDER_COLOR = 0xB0FFFFFF;
    private static final int TEXT_COLOR = 0xFFFFFFFF;

    private final MinecraftClient client;
    private final ConfluxConfig config;
    private final LayerSelector layerSelector;
    private final BooleanSupplier liveTerrainPaused;

    MinimapChromePainter(
        final MinecraftClient client,
        final ConfluxConfig config,
        final LayerSelector layerSelector,
        final BooleanSupplier liveTerrainPaused
    ) {
        this.client = client;
        this.config = config;
        this.layerSelector = layerSelector;
        this.liveTerrainPaused = liveTerrainPaused;
    }

    /** Cardinal letters sit on the (possibly rotated) compass ring but are always drawn upright. */
    void drawCardinals(final GuiDraw draw, final float centerX, final float centerY, final int size, final float mapAngle) {
        final float radius = size / 2f - 7f;
        final double rad = Math.toRadians(mapAngle);
        final float cos = (float) Math.cos(rad);
        final float sin = (float) Math.sin(rad);
        drawCardinal(draw, "N", centerX, centerY, 0f, -radius, cos, sin);
        drawCardinal(draw, "E", centerX, centerY, radius, 0f, cos, sin);
        drawCardinal(draw, "S", centerX, centerY, 0f, radius, cos, sin);
        drawCardinal(draw, "W", centerX, centerY, -radius, 0f, cos, sin);
    }

    private void drawCardinal(
        final GuiDraw draw,
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
        draw.drawTextWithShadow(client.textRenderer, letter, x - width / 2f, y - 4f, TEXT_COLOR);
    }

    void drawInfoText(final GuiDraw draw, final PlayerView player, final int x0, final int y0, final int size) {
        final boolean terrainPaused = liveTerrainPaused.getAsBoolean();
        if (!config.showCoordinates && !config.showBiome && !config.showLayerIndicator
            && !terrainPaused) {
            return;
        }
        final int lineHeight = MinimapInformationLayout.LINE_HEIGHT;
        int lines = 0;
        if (config.showCoordinates) {
            lines++;
        }
        if (config.showBiome) {
            lines++;
        }
        if (config.showLayerIndicator) {
            lines++;
        }
        if (terrainPaused) {
            lines++;
        }
        final float belowY = y0 + size + MinimapInformationLayout.GAP;
        final float yAfterBelowLines = belowY + lines * lineHeight;
        float y = yAfterBelowLines <= client.getWindow().getScaledHeight()
            ? belowY
            : Math.max(0, y0 - lines * lineHeight - MinimapInformationLayout.GAP);
        final float centerX = x0 + size / 2f;

        if (config.showCoordinates) {
            final String coords = player.blockX() + ", " + player.blockY() + ", " + player.blockZ();
            drawCenteredLine(draw, coords, centerX, y);
            y += lineHeight;
        }
        if (config.showBiome) {
            final String biome = biomeName(player);
            if (!biome.isEmpty()) {
                drawCenteredLine(draw, biome, centerX, y);
            }
            y += lineHeight;
        }
        if (config.showLayerIndicator) {
            drawCenteredLine(draw, layerIndicatorText(), centerX, y);
            y += lineHeight;
        }
        if (terrainPaused) {
            drawCenteredLine(
                draw,
                Texts.translatable("confluxmap.live_terrain.paused").getString(),
                centerX,
                y
            );
        }
    }

    /** cave-nether-layers.md-driven layer name, keyed off {@link MapLayerText} (e.g. "confluxmap.layer.cave"). */
    private String layerIndicatorText() {
        final LayerSelector.Decision decision = layerSelector.current();
        return MapLayerText.label(decision.layer(), decision.pivotY());
    }

    private void drawCenteredLine(final GuiDraw draw, final String text, final float centerX, final float y) {
        final int width = client.textRenderer.getWidth(text);
        final float x = Math.max(
            0,
            Math.min(client.getWindow().getScaledWidth() - width, centerX - width / 2f)
        );
        draw.drawTextWithShadow(client.textRenderer, text, x, y, TEXT_COLOR);
    }

    private String biomeName(final PlayerView player) {
        if (client.world == null
            || !ClientChunkLookup.isLoaded(client.world, player.blockX(), player.blockZ())) {
            return "";
        }
        final Identifier id = Regs.biomeIdAt(
            client.world, new BlockPos(player.blockX(), player.blockY(), player.blockZ())
        );
        if (id == null) {
            return "";
        }
        // Translated → datapack-synced → registry id; a datapack biome with no client-side
        // translation must degrade to the id like vanilla F3, never the raw translation key.
        return BiomeLabels.displayName(id);
    }

    private void drawBorder(final MatrixStack matrices, final int x0, final int y0, final int size) {
        GuiShapes.fillRect(matrices, x0, y0, size, BORDER_THICKNESS, BORDER_COLOR);
        GuiShapes.fillRect(matrices, x0, y0 + size - BORDER_THICKNESS, size, BORDER_THICKNESS, BORDER_COLOR);
        GuiShapes.fillRect(matrices, x0, y0, BORDER_THICKNESS, size, BORDER_COLOR);
        GuiShapes.fillRect(matrices, x0 + size - BORDER_THICKNESS, y0, BORDER_THICKNESS, size, BORDER_COLOR);
    }

    void drawFrame(
        final MatrixStack matrices,
        final int x0,
        final int y0,
        final int size,
        final boolean circle,
        final Optional<UiResourceTheme.MinimapFrame> selected
    ) {
        if (selected.isEmpty()) {
            if (circle) {
                GuiShapes.drawRing(
                    matrices, x0 + size / 2f, y0 + size / 2f,
                    size / 2f, BORDER_THICKNESS, BORDER_COLOR
                );
            } else {
                drawBorder(matrices, x0, y0, size);
            }
            return;
        }

        final UiResourceTheme.MinimapFrame frame = selected.get();
        final UiTextureRegion texture = frame.texture();
        TextureBinds.bindTexture(client, texture.texture());
        if (frame.layout() == UiResourceTheme.Layout.OVERLAY) {
            GuiShapes.drawTintedQuad(
                matrices, x0, y0, size, size,
                texture.u0(), texture.v0(), texture.u1(), texture.v1(), 0xFFFFFFFF
            );
        } else if (frame.layout() == UiResourceTheme.Layout.XAERO_CIRCLE) {
            GuiShapes.drawTexturedRing(
                matrices, x0 + size / 2f, y0 + size / 2f, size / 2f,
                4f, texture.u0(), texture.v0(), texture.u1(), texture.v1(), 0xFFFFFFFF
            );
        } else {
            XaeroMinimapFrameRenderer.drawSquare(matrices, x0, y0, size);
        }
    }
}
