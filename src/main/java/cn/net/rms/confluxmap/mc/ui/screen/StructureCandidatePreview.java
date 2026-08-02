package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.core.predict.StructureIndex;
import java.util.List;

/**
 * Describes the camera shared by the fullscreen-map-backed candidate preview and its clickable
 * candidate overlays. Keeping the projection here ensures the markers, hit targets, player arrow,
 * and terrain all use the same north-up X/Z coordinates.
 */
final class StructureCandidatePreview {
    private static final int PADDING = 10;
    private static final double MIN_WORLD_SPAN = 64.0;

    private StructureCandidatePreview() {
    }

    static Layout layout(
        final int x,
        final int y,
        final int width,
        final int height,
        final int centerX,
        final int centerZ,
        final double playerX,
        final double playerZ,
        final List<StructureIndex.Marker> candidates
    ) {
        double minX = Math.min(centerX, playerX);
        double maxX = Math.max(centerX, playerX);
        double minZ = Math.min(centerZ, playerZ);
        double maxZ = Math.max(centerZ, playerZ);
        for (final StructureIndex.Marker marker : candidates) {
            minX = Math.min(minX, marker.blockX());
            maxX = Math.max(maxX, marker.blockX());
            minZ = Math.min(minZ, marker.blockZ());
            maxZ = Math.max(maxZ, marker.blockZ());
        }
        final int contentWidth = Math.max(1, width - PADDING * 2);
        final int contentHeight = Math.max(1, height - PADDING * 2);
        final double blocksPerPixel = Math.max(
            MIN_WORLD_SPAN / Math.min(contentWidth, contentHeight),
            Math.max((maxX - minX) / contentWidth, (maxZ - minZ) / contentHeight) * 1.20
        );
        return new Layout(
            x + PADDING,
            y + PADDING,
            contentWidth,
            contentHeight,
            (minX + maxX) / 2.0,
            (minZ + maxZ) / 2.0,
            blocksPerPixel
        );
    }

    static final class Layout {
        private final int x;
        private final int y;
        private final int width;
        private final int height;
        private final double centerX;
        private final double centerZ;
        private final double blocksPerPixel;

        private Layout(
            final int x,
            final int y,
            final int width,
            final int height,
            final double centerX,
            final double centerZ,
            final double blocksPerPixel
        ) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.blocksPerPixel = blocksPerPixel;
        }

        int screenX(final double blockX) {
            return projectX(blockX);
        }

        int screenY(final double blockZ) {
            return projectY(blockZ);
        }

        double centerBlockX() {
            return centerX;
        }

        double centerBlockZ() {
            return centerZ;
        }

        double blocksPerPixel() {
            return blocksPerPixel;
        }

        int candidateAt(
            final List<StructureIndex.Marker> candidates,
            final double mouseX,
            final double mouseY,
            final int radius
        ) {
            final int radiusSquared = radius * radius;
            int closest = -1;
            double closestDistanceSquared = radiusSquared + 1.0;
            for (int index = 0; index < candidates.size(); index++) {
                final StructureIndex.Marker marker = candidates.get(index);
                final double dx = mouseX - screenX(marker.blockX());
                final double dy = mouseY - screenY(marker.blockZ());
                final double distanceSquared = dx * dx + dy * dy;
                if (distanceSquared <= radiusSquared && distanceSquared < closestDistanceSquared) {
                    closest = index;
                    closestDistanceSquared = distanceSquared;
                }
            }
            return closest;
        }

        int x() {
            return x;
        }

        int y() {
            return y;
        }

        int width() {
            return width;
        }

        int height() {
            return height;
        }

        private int projectX(final double blockX) {
            return x + (int) Math.round(width / 2.0 + (blockX - centerX) / blocksPerPixel);
        }

        private int projectY(final double blockZ) {
            return y + (int) Math.round(height / 2.0 + (blockZ - centerZ) / blocksPerPixel);
        }
    }
}
