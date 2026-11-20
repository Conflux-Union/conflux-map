package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.core.store.RegionColumns;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/** Real terrain sampled on a chunk-aligned grid, coarsened when chunks are subpixels. */
record ChunkLoadCoverage(Window window, BitSet loaded) {
    record Window(int x, int z, int width, int height, int stride) {
        static Window covering(double centerX, double centerZ, int width, int height, double scale) {
            int stride = 1;
            while (stride * 16 < scale) stride *= 2;
            final int blocks = stride * 16;
            final int x = (int) Math.floor((centerX - width * scale / 2) / blocks) - 1;
            final int z = (int) Math.floor((centerZ - height * scale / 2) / blocks) - 1;
            final int endX = (int) Math.ceil((centerX + width * scale / 2) / blocks) + 1;
            final int endZ = (int) Math.ceil((centerZ + height * scale / 2) / blocks) + 1;
            return new Window(x, z, endX - x, endZ - z, stride);
        }
    }

    /** The sign points inward along the axis perpendicular to the edge. */
    record Edge(boolean horizontal, int axis, int start, int end, int inward) {
    }

    ChunkLoadCoverage(final Window window) {
        this(window, new BitSet(window.width() * window.height()));
    }

    boolean containsChunk(final int chunkX, final int chunkZ) {
        final int x = Math.floorDiv(chunkX, window.stride()) - window.x();
        final int z = Math.floorDiv(chunkZ, window.stride()) - window.z();
        return occupied(x, z);
    }

    void addRegion(final RegionColumns region) {
        final int baseX = region.regionX * RegionColumns.CHUNKS;
        final int baseZ = region.regionZ * RegionColumns.CHUNKS;
        final int firstX = Math.max(0, window.x() * window.stride() - baseX);
        final int firstZ = Math.max(0, window.z() * window.stride() - baseZ);
        final int limitX = Math.min(RegionColumns.CHUNKS, (window.x() + window.width()) * window.stride() - baseX);
        final int limitZ = Math.min(RegionColumns.CHUNKS, (window.z() + window.height()) * window.stride() - baseZ);
        if (firstX >= limitX || firstZ >= limitZ) return;
        for (int z = firstZ; z < limitZ; z++) {
            for (int x = firstX; x < limitX; x++) {
                if (region.chunkSource(x, z).isExplored()) addChunkRun(baseX + x, baseX + x, baseZ + z);
            }
        }
    }

    void addChunkRun(final int firstChunkX, final int lastChunkX, final int chunkZ) {
        final int row = Math.floorDiv(chunkZ, window.stride()) - window.z();
        if (row < 0 || row >= window.height()) return;
        final int first = Math.max(0, Math.floorDiv(firstChunkX, window.stride()) - window.x());
        final int end = Math.min(window.width(), Math.floorDiv(lastChunkX, window.stride()) - window.x() + 1);
        if (first < end) loaded.set(row * window.width() + first, row * window.width() + end);
    }

    List<Edge> outline() {
        final List<Edge> edges = new ArrayList<>();
        appendEdges(true, window.height(), window.width(), edges);
        appendEdges(false, window.width(), window.height(), edges);
        return edges;
    }

    private void appendEdges(final boolean horizontal, final int axes, final int length, final List<Edge> edges) {
        for (int axis = 0; axis <= axes; axis++) {
            int start = 0;
            int inward = 0;
            for (int along = 0; along <= length; along++) {
                final int side = along == length ? 0 : boundarySide(horizontal, axis, along);
                if (side != inward) {
                    if (inward != 0) edges.add(new Edge(horizontal, axis, start, along, inward));
                    inward = side;
                    start = along;
                }
            }
        }
    }

    private int boundarySide(final boolean horizontal, final int axis, final int along) {
        final boolean before = horizontal ? occupied(along, axis - 1) : occupied(axis - 1, along);
        final boolean after = horizontal ? occupied(along, axis) : occupied(axis, along);
        return before == after ? 0 : after ? 1 : -1;
    }

    private boolean occupied(final int x, final int z) {
        return x >= 0 && x < window.width() && z >= 0 && z < window.height()
            && loaded.get(z * window.width() + x);
    }
}
