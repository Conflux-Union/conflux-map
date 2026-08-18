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
        final boolean fresh = entry == null;
        if (fresh) {
            //#if MC>=12105
            //$$ entry = new TileTexture(new NativeImageBackedTexture("Conflux Map tile", TILE_SIZE, TILE_SIZE, false));
            //#else
            entry = new TileTexture(new NativeImageBackedTexture(TILE_SIZE, TILE_SIZE, false));
            //#endif
            textures.put(update.key(), entry);
            if (!PredictedTileKeys.isPredicted(update.key())) {
                tiles.retainTile(update.key());
            }
        }
        final NativeImage image = entry.texture.getImage();
        if (image == null) {
            return;
        }
        final TileUpdate.Relight relight = update.relight();
        if (fresh) {
            // A NativeImage's content is undefined until written; with partial coverage the
            // unclaimed area must start transparent, not as whatever the allocation held.
            for (int y = 0; y < TILE_SIZE; y++) {
                for (int x = 0; x < TILE_SIZE; x++) {
                    NativeImages.setArgb(image, x, y, Argb.TRANSPARENT);
                }
            }
        } else if (relight != null && entry.lightLevels != null
            && !DaylightModel.sameBucket(entry.appliedDaylight, relight.composedDaylight())) {
            // Preserved pixels were darkened at an older daylight bucket than the rects about
            // to land; re-light them first so one tile never mixes two buckets.
            relightPixels(image, entry.lightLevels, entry.appliedDaylight, relight.composedDaylight());
        }
        if (relight == null) {
            entry.appliedDaylight = Float.NaN;
            entry.lightLevels = null;
        } else {
            entry.appliedDaylight = relight.composedDaylight();
            if (entry.lightLevels == null) {
                entry.lightLevels = new byte[TILE_SIZE * TILE_SIZE];
            }
        }
        final int[] pixels = update.argbPixels();
        for (final TileUpdate.Rect rect : update.changed()) {
            for (int y = rect.y(); y < rect.y() + rect.height(); y++) {
                final int row = y * TILE_SIZE;
                for (int x = rect.x(); x < rect.x() + rect.width(); x++) {
                    NativeImages.setArgb(image, x, y, pixels[row + x]);
                }
                if (relight != null) {
                    System.arraycopy(relight.lightLevels(), row + rect.x(), entry.lightLevels, row + rect.x(), rect.width());
                }
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
            relightPixels(image, entry.lightLevels, entry.appliedDaylight, target);
            entry.appliedDaylight = target;
            entry.texture.upload();
            budget--;
        }
    }

    /** Rewrites every pixel from the daylight it was darkened with to {@code to}, per its block light. */
    private static void relightPixels(final NativeImage image, final byte[] light, final float from, final float to) {
        final float[] ratios = ShadingPipeline.relightRatios(from, to);
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
    }

    /** Keep tile edges independent: repeat/linear state can leak from another texture or shader. */
    private static void configureSampling(final NativeImageBackedTexture texture) {
        //#if MC>=12111
        //$$ // Sampling is selected explicitly when the render pass binds the texture.
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
            // Nothing holds this tile's pixels any more, so the composer must stop refreshing it
            // and go back to composing it in full when bind() next asks for it.
            tiles.forgetTile(oldest.getKey());
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

    /** Render thread, session end: drop every cached tile texture. */
    public void releaseAll() {
        assert RenderSystem.isOnRenderThread() : "TileTextureManager.releaseAll() must run on the render thread";
        for (final Map.Entry<TileKey, TileTexture> entry : textures.entrySet()) {
            entry.getValue().texture.close();
            tiles.forgetTile(entry.getKey());
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
