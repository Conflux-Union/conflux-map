package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.model.TileKey;

/**
 * The {@code "!pred"} {@link TileKey#layerId()} suffix marking a predicted-underlay tile, and
 * the tiny bit of string surgery needed to move between a real key and its predicted twin. Per
 * the plan's routing note: a predicted key must never reach {@code
 * cn.net.rms.confluxmap.core.tile.TileService#requestTile} - {@code MapLayer.parse} doesn't know
 * the suffix and would throw - so {@code mc.render.TileTextureManager#bind} intercepts it first.
 */
public final class PredictedTileKeys {
    public static final String SUFFIX = "!pred";

    private PredictedTileKeys() {
    }

    public static boolean isPredicted(final TileKey key) {
        return key.layerId().endsWith(SUFFIX);
    }

    /** The predicted twin of a real tile key: same everything, {@link #SUFFIX} appended to the layer id. */
    public static TileKey toPredicted(final TileKey real) {
        return new TileKey(real.world(), real.dimension(), real.layerId() + SUFFIX, real.lod(), real.tileX(), real.tileZ());
    }

    /** The real layer's {@code cacheId()}, given a predicted key's {@code layerId()} (strips {@link #SUFFIX}). */
    public static String realLayerId(final String predictedLayerId) {
        return predictedLayerId.substring(0, predictedLayerId.length() - SUFFIX.length());
    }
}
