package cn.net.rms.confluxmap.core.radar;

/**
 * The world-block radius currently visible on whichever map surface is active (the
 * always-on minimap, or the fullscreen map while it's open) - {@code EntityRadarScanner}
 * scans exactly this far instead of a fixed configured range, so opening a more zoomed-out
 * view immediately widens the scan. Zero means no map surface is visible, so the radar has
 * nothing to project onto and scans nothing.
 *
 * <p>Client thread only: whichever surface renders this frame writes it once, and the
 * scanner reads it once per tick on the same thread, so no synchronization is needed.
 */
public final class RadarViewRange {
    private double radiusBlocks;

    public void set(final double radiusBlocks) {
        this.radiusBlocks = radiusBlocks;
    }

    public double radius() {
        return radiusBlocks;
    }
}
