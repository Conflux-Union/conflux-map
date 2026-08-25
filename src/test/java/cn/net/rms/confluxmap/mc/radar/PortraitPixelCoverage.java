package cn.net.rms.confluxmap.mc.radar;

/** CPU-side raster coverage used by portrait geometry regression tests. */
final class PortraitPixelCoverage {
    private PortraitPixelCoverage() {
    }

    static int occupiedPixels(final float[] geometry) {
        final boolean[][] occupied = occupiedMask(geometry);
        int count = 0;
        for (final boolean[] row : occupied) {
            for (final boolean pixel : row) {
                if (pixel) {
                    count++;
                }
            }
        }
        return count;
    }

    static int longestOccupiedSpan(final float[] geometry) {
        final boolean[][] occupied = occupiedMask(geometry);
        int minX = 32;
        int minY = 32;
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < occupied.length; y++) {
            for (int x = 0; x < occupied[y].length; x++) {
                if (occupied[y][x]) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        return maxX < 0 ? 0 : Math.max(maxX - minX + 1, maxY - minY + 1);
    }

    private static boolean[][] occupiedMask(final float[] geometry) {
        final boolean[][] occupied = new boolean[32][32];
        for (int quad = 0; quad < geometry.length; quad += 20) {
            for (int y = 0; y < 32; y++) {
                for (int x = 0; x < 32; x++) {
                    final float px = x + 0.5f;
                    final float py = y + 0.5f;
                    if (insideTriangle(geometry, quad, 0, 1, 2, px, py)
                        || insideTriangle(geometry, quad, 0, 2, 3, px, py)) {
                        occupied[y][x] = true;
                    }
                }
            }
        }
        return occupied;
    }

    private static boolean insideTriangle(
        final float[] geometry,
        final int quad,
        final int first,
        final int second,
        final int third,
        final float px,
        final float py
    ) {
        final float triangleArea = sign(geometry, quad, first, second,
            geometry[quad + third * 5], geometry[quad + third * 5 + 1]);
        if (Math.abs(triangleArea) < 0.0001f) {
            return false;
        }
        final float firstSign = sign(geometry, quad, first, second, px, py);
        final float secondSign = sign(geometry, quad, second, third, px, py);
        final float thirdSign = sign(geometry, quad, third, first, px, py);
        final boolean hasNegative = firstSign < 0f || secondSign < 0f || thirdSign < 0f;
        final boolean hasPositive = firstSign > 0f || secondSign > 0f || thirdSign > 0f;
        return !(hasNegative && hasPositive);
    }

    private static float sign(
        final float[] geometry,
        final int quad,
        final int first,
        final int second,
        final float px,
        final float py
    ) {
        final int firstOffset = quad + first * 5;
        final int secondOffset = quad + second * 5;
        return (px - geometry[secondOffset]) * (geometry[firstOffset + 1] - geometry[secondOffset + 1])
            - (geometry[firstOffset] - geometry[secondOffset]) * (py - geometry[secondOffset + 1]);
    }
}
