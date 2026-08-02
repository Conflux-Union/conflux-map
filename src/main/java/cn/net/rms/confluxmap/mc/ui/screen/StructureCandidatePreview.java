package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.core.predict.StructureIndex;
import java.util.List;

/** Projects a candidate search's real X/Z coordinates into the compact clickable preview. */
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
        final List<StructureIndex.Marker> candidates
    ) {
        long minX = centerX;
        long maxX = centerX;
        long minZ = centerZ;
        long maxZ = centerZ;
        for (final StructureIndex.Marker marker : candidates) {
            minX = Math.min(minX, marker.blockX());
            maxX = Math.max(maxX, marker.blockX());
            minZ = Math.min(minZ, marker.blockZ());
            maxZ = Math.max(maxZ, marker.blockZ());
        }
        final double span = Math.max(
            MIN_WORLD_SPAN,
            Math.max(maxX - minX, maxZ - minZ) * 1.20
        );
        return new Layout(
            x + PADDING,
            y + PADDING,
            Math.max(1, width - PADDING * 2),
            Math.max(1, height - PADDING * 2),
            (minX + maxX) / 2.0,
            (minZ + maxZ) / 2.0,
            span
        );
    }

    static final class Layout {
        private final int x;
        private final int y;
        private final int width;
        private final int height;
        private final double centerX;
        private final double centerZ;
        private final double span;

        private Layout(
            final int x,
            final int y,
            final int width,
            final int height,
            final double centerX,
            final double centerZ,
            final double span
        ) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.span = span;
        }

        int centerScreenX(final int blockX) {
            return projectX(blockX);
        }

        int centerScreenY(final int blockZ) {
            return projectY(blockZ);
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
                final double dx = mouseX - projectX(marker.blockX());
                final double dy = mouseY - projectY(marker.blockZ());
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

        private int projectX(final int blockX) {
            return x + (int) Math.round(width / 2.0 + (blockX - centerX) * width / span);
        }

        private int projectY(final int blockZ) {
            return y + (int) Math.round(height / 2.0 - (blockZ - centerZ) * height / span);
        }
    }
}
