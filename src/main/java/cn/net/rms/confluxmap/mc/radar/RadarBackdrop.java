package cn.net.rms.confluxmap.mc.radar;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.TileKey;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.predict.PredictedTileKeys;
import cn.net.rms.confluxmap.core.util.Argb;
import cn.net.rms.confluxmap.core.util.TileMath;
import cn.net.rms.confluxmap.mc.render.TileTextureManager;

/**
 * Per-frame lookup of the map color a rendering surface just drew beneath a world position, so
 * radar marker contours can flip between black and white for contrast. Reads the render-thread
 * CPU copies of the same tile textures the surface binds ({@link TileTextureManager#sampleArgb}),
 * composed the way the surface composes them: real tile over (optional) tinted predicted
 * underlay over the surface's own background fill. Each surface constructs one per radar pass
 * with its current layer/LOD/prediction state. Render thread only.
 */
public final class RadarBackdrop {
    private final TileTextureManager textures;
    private final WorldIdentity world;
    private final DimensionId dimension;
    private final String layerId;
    private final int lod;
    private final boolean predictionActive;
    private final int predictionTint;
    private final int backgroundArgb;

    public RadarBackdrop(
        final TileTextureManager textures,
        final WorldIdentity world,
        final DimensionId dimension,
        final String layerId,
        final int lod,
        final boolean predictionActive,
        final int predictionTint,
        final int backgroundArgb
    ) {
        this.textures = textures;
        this.world = world;
        this.dimension = dimension;
        this.layerId = layerId;
        this.lod = lod;
        this.predictionActive = predictionActive;
        this.predictionTint = predictionTint;
        this.backgroundArgb = backgroundArgb;
    }

    /** The composed, opaque map color currently shown at this world position. */
    public int argbAt(final double blockX, final double blockZ) {
        final int bx = (int) Math.floor(blockX);
        final int bz = (int) Math.floor(blockZ);
        final TileKey key = new TileKey(
            world, dimension, layerId, lod,
            TileMath.blockToTile(bx, lod), TileMath.blockToTile(bz, lod)
        );
        final int px = TileMath.blockToPixelInTile(bx, lod);
        final int py = TileMath.blockToPixelInTile(bz, lod);
        int under = backgroundArgb;
        if (predictionActive) {
            final int predicted = textures.sampleArgb(PredictedTileKeys.toPredicted(key), px, py);
            if (Argb.alpha(predicted) != 0) {
                under = Argb.over(Argb.multiply(predicted, predictionTint), under);
            }
        }
        return Argb.over(textures.sampleArgb(key, px, py), under);
    }
}
