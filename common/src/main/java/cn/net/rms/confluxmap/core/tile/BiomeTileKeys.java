package cn.net.rms.confluxmap.core.tile;

import cn.net.rms.confluxmap.core.model.TileKey;
import cn.net.rms.confluxmap.core.predict.PredictedTileKeys;

/** Distinct texture-key namespace for flat biome tiles, leaving normal minimap tiles untouched. */
public final class BiomeTileKeys {
    public static final String SUFFIX = "!biome";

    private BiomeTileKeys() {
    }

    public static boolean isBiome(final TileKey key) {
        final String layerId = key.layerId();
        final String withoutPrediction = PredictedTileKeys.isPredicted(key)
            ? PredictedTileKeys.realLayerId(layerId)
            : layerId;
        return withoutPrediction.endsWith(SUFFIX);
    }

    public static TileKey toBiome(final TileKey key) {
        if (isBiome(key)) {
            return key;
        }
        if (PredictedTileKeys.isPredicted(key)) {
            final String realLayer = PredictedTileKeys.realLayerId(key.layerId());
            return new TileKey(
                key.world(), key.dimension(), realLayer + SUFFIX + PredictedTileKeys.SUFFIX,
                key.lod(), key.tileX(), key.tileZ()
            );
        }
        return new TileKey(
            key.world(), key.dimension(), key.layerId() + SUFFIX,
            key.lod(), key.tileX(), key.tileZ()
        );
    }

    /** Removes the biome suffix from a non-predicted layer id. */
    public static String realLayerId(final String biomeLayerId) {
        return biomeLayerId.endsWith(SUFFIX)
            ? biomeLayerId.substring(0, biomeLayerId.length() - SUFFIX.length())
            : biomeLayerId;
    }

    /** Removes optional prediction and biome suffixes from a tile key. */
    public static String baseLayerId(final TileKey key) {
        final String layerId = PredictedTileKeys.isPredicted(key)
            ? PredictedTileKeys.realLayerId(key.layerId())
            : key.layerId();
        return realLayerId(layerId);
    }
}
