package cn.net.rms.confluxmap.core.model;

import cn.net.rms.confluxmap.core.util.TileMath;

/**
 * Identity of one map tile texture. All factories use floor semantics
 * ({@link TileMath}) so negative coordinates land in the correct tile.
 */
public record TileKey(
    WorldIdentity world,
    DimensionId dimension,
    String layerId,
    int lod,
    int tileX,
    int tileZ
) {
    public static TileKey ofBlock(
        final WorldIdentity world,
        final DimensionId dimension,
        final MapLayer layer,
        final int lod,
        final int blockX,
        final int blockZ
    ) {
        return new TileKey(
            world, dimension, layer.cacheId(), lod,
            TileMath.blockToTile(blockX, lod),
            TileMath.blockToTile(blockZ, lod)
        );
    }

    public static TileKey ofChunk(
        final WorldIdentity world,
        final DimensionId dimension,
        final MapLayer layer,
        final int chunkX,
        final int chunkZ
    ) {
        return new TileKey(
            world, dimension, layer.cacheId(), 0,
            TileMath.chunkToTile(chunkX),
            TileMath.chunkToTile(chunkZ)
        );
    }

    /** North-west corner block X of this tile. */
    public int originBlockX() {
        return tileX << (TileMath.TILE_SHIFT + lod);
    }

    /** North-west corner block Z of this tile. */
    public int originBlockZ() {
        return tileZ << (TileMath.TILE_SHIFT + lod);
    }
}
