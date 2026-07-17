package cn.net.rms.confluxmap.core.tile;

import cn.net.rms.confluxmap.core.model.TileKey;

/**
 * One freshly-composed tile, ready for the render thread to upload. {@code argbPixels}
 * is 256x256 (LOD0), row-major, {@code z * 256 + x}. {@code changedX/Y/Width/Height}
 * describes the sub-rect that actually changed; M1 always recomposes and reports the
 * whole tile, but the field exists so partial uploads can be added later without
 * changing this shape.
 */
public record TileUpdate(
    TileKey key,
    int[] argbPixels,
    int changedX,
    int changedY,
    int changedWidth,
    int changedHeight
) {
    public static TileUpdate fullTile(final TileKey key, final int[] argbPixels) {
        return new TileUpdate(key, argbPixels, 0, 0, 256, 256);
    }
}
