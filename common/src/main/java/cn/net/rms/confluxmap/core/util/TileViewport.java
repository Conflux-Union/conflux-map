package cn.net.rms.confluxmap.core.util;

/**
 * Inclusive tile bounds covering one screen-space map viewport at a fixed LOD. Keeping this
 * calculation outside the Minecraft screen makes the amount of work requested by a zoom level
 * explicit and directly testable.
 */
public record TileViewport(int minTileX, int maxTileX, int minTileZ, int maxTileZ) {
    public static TileViewport covering(
        final double centerX,
        final double centerZ,
        final int screenWidth,
        final int screenHeight,
        final double blocksPerScreenPixel,
        final int lod
    ) {
        final double halfWidthBlocks = screenWidth / 2.0 * blocksPerScreenPixel;
        final double halfHeightBlocks = screenHeight / 2.0 * blocksPerScreenPixel;
        return new TileViewport(
            TileMath.blockToTile((int) Math.floor(centerX - halfWidthBlocks), lod),
            TileMath.blockToTile((int) Math.ceil(centerX + halfWidthBlocks), lod),
            TileMath.blockToTile((int) Math.floor(centerZ - halfHeightBlocks), lod),
            TileMath.blockToTile((int) Math.ceil(centerZ + halfHeightBlocks), lod)
        );
    }

    public int tileCount() {
        return Math.multiplyExact(maxTileX - minTileX + 1, maxTileZ - minTileZ + 1);
    }
}
