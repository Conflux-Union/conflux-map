package cn.net.rms.confluxmap.mc.render;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.TileKey;
import cn.net.rms.confluxmap.core.tile.TileService;
import cn.net.rms.confluxmap.core.tile.TileUpdate;
import cn.net.rms.confluxmap.core.util.Argb;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;

/**
 * Render-thread-only cache of {@link TileKey} to GPU texture, an LRU capped at
 * {@link ConfluxConfig#gpuTileCacheLimit}. Pulls finished compositions off
 * {@link TileService}'s upload queue and writes their pixels into a
 * {@link NativeImageBackedTexture} (which stores ABGR, hence {@link Argb#toAbgr}).
 */
public final class TileTextureManager {
    private static final int UPLOADS_PER_FRAME = 4;
    private static final int TILE_SIZE = 256;

    private final ConfluxConfig config;
    private final TileService tiles;
    /** Access-order so the least-recently-bound tile is always first (LRU eviction). */
    private final LinkedHashMap<TileKey, NativeImageBackedTexture> textures = new LinkedHashMap<>(64, 0.75f, true);

    public TileTextureManager(final ConfluxConfig config, final TileService tiles) {
        this.config = config;
        this.tiles = tiles;
    }

    /** Render thread: drains a handful of freshly-composed tiles and uploads them to the GPU. */
    public void beginFrame() {
        assert RenderSystem.isOnRenderThread() : "TileTextureManager.beginFrame() must run on the render thread";
        final List<TileUpdate> updates = tiles.drainUploads(UPLOADS_PER_FRAME);
        for (final TileUpdate update : updates) {
            upload(update);
        }
        evictOverLimit();
    }

    private void upload(final TileUpdate update) {
        NativeImageBackedTexture texture = textures.get(update.key());
        if (texture == null) {
            texture = new NativeImageBackedTexture(TILE_SIZE, TILE_SIZE, false);
            textures.put(update.key(), texture);
        }
        final NativeImage image = texture.getImage();
        if (image == null) {
            return;
        }
        final int[] pixels = update.argbPixels();
        for (int y = update.changedY(); y < update.changedY() + update.changedHeight(); y++) {
            final int row = y * TILE_SIZE;
            for (int x = update.changedX(); x < update.changedX() + update.changedWidth(); x++) {
                image.setColor(x, y, Argb.toAbgr(pixels[row + x]));
            }
        }
        texture.upload();
    }

    private void evictOverLimit() {
        final int limit = config.gpuTileCacheLimit;
        final Iterator<Map.Entry<TileKey, NativeImageBackedTexture>> it = textures.entrySet().iterator();
        while (textures.size() > limit && it.hasNext()) {
            final Map.Entry<TileKey, NativeImageBackedTexture> oldest = it.next();
            it.remove();
            oldest.getValue().close();
        }
    }

    /** Binds the tile's texture for drawing; returns false (and requests it) if not yet cached. */
    public boolean bind(final TileKey key) {
        final NativeImageBackedTexture texture = textures.get(key);
        if (texture == null) {
            tiles.requestTile(key);
            return false;
        }
        RenderUtil.bindTexture(texture.getGlId());
        return true;
    }

    /** Render thread, session end: drop every cached tile texture. */
    public void releaseAll() {
        assert RenderSystem.isOnRenderThread() : "TileTextureManager.releaseAll() must run on the render thread";
        for (final NativeImageBackedTexture texture : textures.values()) {
            texture.close();
        }
        textures.clear();
        ConfluxMapMod.LOGGER.debug("TileTextureManager: released all tile textures");
    }
}
