package cn.net.rms.confluxmap.mc.radar;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.core.radar.IconOutliner;
import cn.net.rms.confluxmap.core.util.Argb;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.IOException;
import java.io.InputStream;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

/**
 * Lazily-baked GPU texture holding the per-cell silhouette outline mask for the bundled
 * entity-icon sheet. Every 16x16 sheet cell becomes an 18x18 padded cell (a 1px apron on each
 * side, {@link IconOutliner#PAD}) outlined in isolation, so sprites that touch their cell edge
 * keep a full ring and never bleed an outline into a neighboring cell. Padding scales the grid
 * uniformly, so a cell's fractional sheet UV rect addresses the same cell here - callers reuse
 * the sheet UVs and only enlarge the on-screen quad by {@link #PADDED_CELL_PX}/{@link #CELL_PX}.
 *
 * <p>The mask is white where the outline sits and transparent elsewhere; the draw-time tint
 * picks the actual contour color. Render thread only (baking needs the GL context, and a
 * resource reload - a resource pack can override the sheet - re-bakes via {@link #invalidate}).
 */
public final class EntityIconOutlineTexture {
    public static final int CELL_PX = 16;
    public static final int PADDED_CELL_PX = CELL_PX + 2 * IconOutliner.PAD;

    private final Identifier sheetId;
    private NativeImageBackedTexture texture;
    private boolean buildFailed;

    public EntityIconOutlineTexture(final Identifier sheetId) {
        this.sheetId = sheetId;
    }

    /** GL id of the baked mask, building it on first use; -1 if the sheet can't be read. */
    public int glId(final MinecraftClient client) {
        assert RenderSystem.isOnRenderThread() : "EntityIconOutlineTexture.glId() must run on the render thread";
        if (texture != null) {
            return texture.getGlId();
        }
        if (buildFailed) {
            return -1;
        }
        try {
            texture = build(client);
        } catch (final IOException | RuntimeException e) {
            // Fail once per (re)load: callers fall back to the plain square frame.
            buildFailed = true;
            ConfluxMapMod.LOGGER.warn("Failed to bake radar icon outline mask from {}", sheetId, e);
            return -1;
        }
        return texture.getGlId();
    }

    /** Resource reload: drop the baked mask so the next frame re-bakes from the current sheet. */
    public void invalidate() {
        assert RenderSystem.isOnRenderThread() : "EntityIconOutlineTexture.invalidate() must run on the render thread";
        if (texture != null) {
            texture.close();
            texture = null;
        }
        buildFailed = false;
    }

    private NativeImageBackedTexture build(final MinecraftClient client) throws IOException {
        final NativeImage sheet;
        try (InputStream in = MinecraftAccess.openResource(client, sheetId)) {
            sheet = NativeImage.read(in);
        }
        try (sheet) {
            final int cols = sheet.getWidth() / CELL_PX;
            final int rows = sheet.getHeight() / CELL_PX;
            if (cols == 0 || rows == 0) {
                throw new IOException("icon sheet smaller than one cell: " + sheet.getWidth() + "x" + sheet.getHeight());
            }
            final NativeImage mask = new NativeImage(cols * PADDED_CELL_PX, rows * PADDED_CELL_PX, false);
            final int[] cell = new int[CELL_PX * CELL_PX];
            for (int row = 0; row < rows; row++) {
                for (int col = 0; col < cols; col++) {
                    for (int y = 0; y < CELL_PX; y++) {
                        for (int x = 0; x < CELL_PX; x++) {
                            cell[y * CELL_PX + x] = Argb.toAbgr(sheet.getColor(col * CELL_PX + x, row * CELL_PX + y));
                        }
                    }
                    final int[] outline = IconOutliner.outlineMask(cell, CELL_PX, CELL_PX);
                    // OUTLINE is white and 0 is transparent in ARGB and ABGR alike, so no conversion.
                    for (int y = 0; y < PADDED_CELL_PX; y++) {
                        for (int x = 0; x < PADDED_CELL_PX; x++) {
                            mask.setColor(col * PADDED_CELL_PX + x, row * PADDED_CELL_PX + y, outline[y * PADDED_CELL_PX + x]);
                        }
                    }
                }
            }
            final NativeImageBackedTexture built = new NativeImageBackedTexture(mask);
            built.upload();
            configureSampling(built);
            return built;
        }
    }

    /** Same nearest+clamp state as tile textures: repeat state leaking in would bleed opposite cell edges. */
    private static void configureSampling(final NativeImageBackedTexture texture) {
        texture.setFilter(false, false);
        GlStateManager._bindTexture(texture.getGlId());
        GlStateManager._texParameter(3553, 10242, 33071);
        GlStateManager._texParameter(3553, 10243, 33071);
    }
}
