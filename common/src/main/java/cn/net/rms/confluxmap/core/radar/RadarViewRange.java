package cn.net.rms.confluxmap.core.radar;

/**
 * The player-centered world-block radius required to cover whichever map surface is active
 * (the always-on minimap, or the fullscreen map while it's open) - {@code EntityRadarScanner}
 * scans exactly this far instead of a fixed configured range. Zero means no map surface is
 * visible, so the radar has nothing to project onto and scans nothing.
 *
 * <p>Client thread only: whichever surface renders this frame writes it once, and the
 * scanner reads it once per tick on the same thread, so no synchronization is needed.
 */
public final class RadarViewRange {
    private double radiusBlocks;

    public void set(final double radiusBlocks) {
        this.radiusBlocks = radiusBlocks;
    }

    /**
     * Updates the player-centered scan radius to cover every corner of an axis-aligned map
     * viewport, including when the viewport has been panned away from the player.
     */
    public void setForAxisAlignedViewport(
        final double observerX,
        final double observerZ,
        final double centerX,
        final double centerZ,
        final double widthPixels,
        final double heightPixels,
        final double blocksPerPixel
    ) {
        final double halfWidthBlocks = widthPixels / 2.0 * blocksPerPixel;
        final double halfHeightBlocks = heightPixels / 2.0 * blocksPerPixel;
        final double farthestX = Math.abs(centerX - observerX) + halfWidthBlocks;
        final double farthestZ = Math.abs(centerZ - observerZ) + halfHeightBlocks;
        set(Math.hypot(farthestX, farthestZ));
    }

    public double radius() {
        return radiusBlocks;
    }
}
