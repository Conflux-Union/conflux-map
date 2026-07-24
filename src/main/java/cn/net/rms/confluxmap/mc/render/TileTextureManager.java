package cn.net.rms.confluxmap.mc.render;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.compat.NativeImages;
import cn.net.rms.confluxmap.core.color.DaylightModel;
import cn.net.rms.confluxmap.core.color.ShadingPipeline;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.TileKey;
import cn.net.rms.confluxmap.core.predict.PredictedTileKeys;
import cn.net.rms.confluxmap.core.predict.PredictionTileService;
import cn.net.rms.confluxmap.core.tile.TileService;
import cn.net.rms.confluxmap.core.tile.TileUpdate;
import cn.net.rms.confluxmap.core.util.Argb;
//#if MC<12105
import com.mojang.blaze3d.platform.GlStateManager;
//#endif
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
 * {@link NativeImageBackedTexture}; {@link NativeImages} normalizes the version-specific
 * native pixel representation to the core's ARGB colors.
 *
 * <p>Each texture also remembers its {@link TileUpdate.Relight} inputs. Tiles whose backing
 * regions are still in the in-memory store recompose through {@code TileService#markSurfaceRelit}
 * when the day/night factor moves, but a region evicted to disk can't recompose - its texture
 * would keep the daylight baked at its last compose forever. {@link #relightStale} closes that
 * gap: it rewrites such textures in place with {@link ShadingPipeline#relightRatios}, on the
 * same quantized-bucket cadence the recompose path uses, a bounded number of tiles per frame.
 */
public final class TileTextureManager {
    // Real and predicted tiles share one upload queue; a slow predicted underlay leaves the real
    // tile's transparent (unexplored) pixels showing the screen background, so drain generously.
    private static final int UPLOADS_PER_FRAME = 8;
    /** Full-tile pixel rewrites are ~0.2ms each; 4 keeps a worst-case 256-tile sweep near a second. */
    private static final int RELIGHTS_PER_FRAME = 4;
    private static final int TILE_SIZE = 256;

    private final ConfluxConfig config;
    private final TileService tiles;
    private final PredictionTileService predictionTiles;
    private final DaylightModel daylightModel;
    /** Access-order so the least-recently-bound tile is always first (LRU eviction). */
    private final LinkedHashMap<TileKey, TileTexture> textures = new LinkedHashMap<>(64, 0.75f, true);

    /**
     * A cached tile texture plus the daylight its pixels currently embody: {@code appliedDaylight}
     * starts as the compose-time factor and advances on every in-place re-light; {@code lightLevels}
     * is the per-pixel 0-15 block-light plane. Both stay null/NaN for tiles daylight never touches.
     */
    private static final class TileTexture {
        final NativeImageBackedTexture texture;
        float appliedDaylight = Float.NaN;
        byte[] lightLevels;

        TileTexture(final NativeImageBackedTexture texture) {
            this.texture = texture;
        }
    }

    public TileTextureManager(
        final ConfluxConfig config,
        final TileService tiles,
        final PredictionTileService predictionTiles,
        final DaylightModel daylightModel
    ) {
        this.config = config;
        this.tiles = tiles;
        this.predictionTiles = predictionTiles;
        this.daylightModel = daylightModel;
    }

    /** Render thread: drains a handful of freshly-composed tiles and uploads them to the GPU. */
    public void beginFrame() {
        assert RenderSystem.isOnRenderThread() : "TileTextureManager.beginFrame() must run on the render thread";
        final List<TileUpdate> updates = tiles.drainUploads(UPLOADS_PER_FRAME);
        for (final TileUpdate update : updates) {
            upload(update);
        }
        evictOverLimit();
        relightStale();
    }

    private void upload(final TileUpdate update) {
        TileTexture entry = textures.get(update.key());
        if (entry == null) {
            //#if MC>=12105
            //$$ entry = new TileTexture(new NativeImageBackedTexture("Conflux Map tile", TILE_SIZE, TILE_SIZE, false));
            //#else
            entry = new TileTexture(new NativeImageBackedTexture(TILE_SIZE, TILE_SIZE, false));
            //#endif
            textures.put(update.key(), entry);
        }
        final TileUpdate.Relight relight = update.relight();
        entry.appliedDaylight = relight == null ? Float.NaN : relight.composedDaylight();
        entry.lightLevels = relight == null ? null : relight.lightLevels();
        final NativeImage image = entry.texture.getImage();
        if (image == null) {
            return;
        }
        final int[] pixels = update.argbPixels();
        for (int y = update.changedY(); y < update.changedY() + update.changedHeight(); y++) {
            final int row = y * TILE_SIZE;
            for (int x = update.changedX(); x < update.changedX() + update.changedWidth(); x++) {
                NativeImages.setArgb(image, x, y, pixels[row + x]);
            }
        }
        entry.texture.upload();
        configureSampling(entry.texture);
    }

    /**
     * Rewrites up to {@link #RELIGHTS_PER_FRAME} resident SURFACE textures whose baked daylight
     * has drifted a full quantization bucket away from the model's current factor. Tiles the
     * recompose path still reaches get the same value re-baked moments later (the fresh upload
     * simply overwrites this rewrite); tiles it can't reach - the evicted-region case - only
     * ever update through here.
     */
    private void relightStale() {
        final float target = daylightModel.factor();
        int budget = RELIGHTS_PER_FRAME;
        for (final Map.Entry<TileKey, TileTexture> mapEntry : textures.entrySet()) {
            if (budget == 0) {
                return;
            }
            final TileTexture entry = mapEntry.getValue();
            if (entry.lightLevels == null || DaylightModel.sameBucket(entry.appliedDaylight, target)) {
                continue;
            }
            final NativeImage image = entry.texture.getImage();
            if (image == null) {
                continue;
            }
            final float[] ratios = ShadingPipeline.relightRatios(entry.appliedDaylight, target);
            final byte[] light = entry.lightLevels;
            for (int y = 0; y < TILE_SIZE; y++) {
                final int row = y * TILE_SIZE;
                for (int x = 0; x < TILE_SIZE; x++) {
                    final float ratio = ratios[Math.min(15, light[row + x] & 0xFF)];
                    if (ratio != 1f) {
                        final int argb = NativeImages.getArgb(image, x, y);
                        NativeImages.setArgb(image, x, y, ShadingPipeline.applyBrightnessMultiplier(argb, ratio));
                    }
                }
            }
            entry.appliedDaylight = target;
            entry.texture.upload();
            budget--;
        }
    }

    /** Keep tile edges independent: repeat/linear state can leak from another texture or shader. */
    private static void configureSampling(final NativeImageBackedTexture texture) {
        //#if MC>=12111
        // Sampling is selected explicitly when the render pass binds the texture.
        //#else
        texture.setFilter(false, false);
        //#if MC>=12105
        //$$ texture.setClamp(true);
        //#else
        GlStateManager._bindTexture(texture.getGlId());
        GlStateManager._texParameter(3553, 10242, 33071);
        GlStateManager._texParameter(3553, 10243, 33071);
        //#endif
        //#endif
    }

    private void evictOverLimit() {
        final int limit = config.gpuTileCacheLimit;
        final Iterator<Map.Entry<TileKey, TileTexture>> it = textures.entrySet().iterator();
        while (textures.size() > limit && it.hasNext()) {
            final Map.Entry<TileKey, TileTexture> oldest = it.next();
            it.remove();
            oldest.getValue().texture.close();
        }
    }

    /**
     * Binds the tile's texture for drawing; returns false (and requests it) if not yet cached.
     * A {@link PredictedTileKeys#isPredicted predicted} key is routed to {@link
     * PredictionTileService} instead of {@link TileService} - {@code TileService.requestTile}
     * would throw on a {@code "!pred"} layer id, since {@code MapLayer.parse} doesn't know it.
     */
    public boolean bind(final TileKey key) {
        final TileTexture entry = textures.get(key);
        if (entry == null) {
            if (PredictedTileKeys.isPredicted(key)) {
                predictionTiles.requestTile(key);
            } else {
                tiles.requestTile(key);
            }
            return false;
        }
        //#if MC>=12108
        //$$ RenderUtil.bindTexture(entry.texture.getGlTextureView());
        //#elseif MC>=12105
        //$$ RenderUtil.bindTexture(entry.texture.getGlTexture());
        //#else
        RenderUtil.bindTexture(entry.texture.getGlId());
        //#endif
        return true;
    }

    /**
     * Render thread: the CPU-side composed color (ARGB) of one cached tile pixel, or transparent
     * if the tile isn't resident. A pure read - unlike {@link #bind} it never requests
     * composition, so probing (radar contour contrast) doesn't queue work for absent tiles.
     */
    public int sampleArgb(final TileKey key, final int px, final int py) {
        final TileTexture entry = textures.get(key);
        if (entry == null) {
            return Argb.TRANSPARENT;
        }
        final NativeImage image = entry.texture.getImage();
        if (image == null) {
            return Argb.TRANSPARENT;
        }
        return NativeImages.getArgb(image, px, py);
    }

    /** Render thread, session end: drop every cached tile texture. */
    public void releaseAll() {
        assert RenderSystem.isOnRenderThread() : "TileTextureManager.releaseAll() must run on the render thread";
        for (final TileTexture entry : textures.values()) {
            entry.texture.close();
        }
        textures.clear();
        ConfluxMapMod.LOGGER.debug("TileTextureManager: released all tile textures");
    }

    /** Render thread: drop only predicted textures, leaving captured map tiles intact. */
    public void releasePredicted() {
        assert RenderSystem.isOnRenderThread() : "TileTextureManager.releasePredicted() must run on the render thread";
        final Iterator<Map.Entry<TileKey, TileTexture>> it = textures.entrySet().iterator();
        int released = 0;
        while (it.hasNext()) {
            final Map.Entry<TileKey, TileTexture> entry = it.next();
            if (PredictedTileKeys.isPredicted(entry.getKey())) {
                entry.getValue().texture.close();
                it.remove();
                released++;
            }
        }
        if (released > 0) {
            ConfluxMapMod.LOGGER.info("TileTextureManager: released {} predicted tile textures", released);
        }
    }
}
