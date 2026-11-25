package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.core.config.ConfluxConfig.LoadedRangeStyle;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.TileKey;
import cn.net.rms.confluxmap.core.util.TileMath;
import cn.net.rms.confluxmap.core.util.TileViewport;
import cn.net.rms.confluxmap.mc.render.GuiShapes;
import cn.net.rms.confluxmap.mc.render.GuiShapes.ColoredRect;
import net.minecraft.client.util.math.MatrixStack;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/** Highlights provenance from the map's own captured and synchronized terrain. */
final class MapLoadedRangeOverlay {
    private static final int DASH_LENGTH = 4;
    private static final int DASH_PERIOD = 7;
    private static final int WHITE = 0xFFFFFFFF;

    private final FullscreenMapScreen map;
    private ChunkLoadCoverage coverage;
    private List<ChunkLoadCoverage.Edge> edges;

    MapLoadedRangeOverlay(final FullscreenMapScreen map) {
        this.map = map;
    }

    void refresh() {
        if (!map.config.fullscreenLoadedRangeVisible) {
            clear();
            return;
        }
        refresh(window());
    }

    private ChunkLoadCoverage.Window window() {
        return ChunkLoadCoverage.Window.covering(map.centerX, map.centerZ, map.width, map.height, map.scale);
    }

    private void refresh(final ChunkLoadCoverage.Window window) {
        final var next = new ChunkLoadCoverage(window);
        final var world = map.viewMapWorlds().current();
        final MapLayer layer = map.viewLayer();
        final var session = map.viewSession();
        if (world != null && world.session().world().equals(session.world())
            && world.session().dimension().equals(session.dimension())) {
            world.store(layer).allRegions().forEach(next::addRegion);
        }
        if (map.predictionActive(layer, session, false)) {
            final int lod = map.currentLod();
            final TileViewport tiles = TileViewport.covering(map.centerX, map.centerZ, map.width, map.height, map.scale, lod);
            for (int z = tiles.minTileZ(); z <= tiles.maxTileZ(); z++) {
                for (int x = tiles.minTileX(); x <= tiles.maxTileX(); x++) {
                    final var key = new TileKey(session.world(), session.dimension(), layer.cacheId(), lod, x, z);
                    final byte[] received = map.predictionTiles.synchronizedCoverage(key);
                    if (received != null) addSynchronized(next, key, BitSet.valueOf(received));
                }
            }
        }
        if (!next.equals(coverage)) {
            coverage = next;
            edges = null;
        }
    }

    static void addSynchronized(final ChunkLoadCoverage coverage, final TileKey key, final BitSet received) {
        final int size = TileMath.TILE_SIZE;
        final int blocksPerPixel = TileMath.blocksPerPixel(key.lod());
        for (int bit = received.nextSetBit(0); bit >= 0 && bit < size * size; ) {
            final int row = bit / size;
            final int next = Math.min((row + 1) * size, received.nextClearBit(bit));
            coverage.addChunkRun(
                TileMath.blockToChunk(key.originBlockX() + bit % size * blocksPerPixel),
                TileMath.blockToChunk(key.originBlockX() + (next - row * size - 1) * blocksPerPixel),
                TileMath.blockToChunk(key.originBlockZ() + row * blocksPerPixel)
            );
            bit = received.nextSetBit(next);
        }
    }

    void clear() {
        coverage = null;
        edges = null;
    }

    void draw(final MatrixStack matrices) {
        if (!map.config.fullscreenLoadedRangeVisible) return;
        final var window = window();
        if (coverage == null || !window.equals(coverage.window())) refresh(window);
        final Projection view = new Projection(
            map.width / 2.0 - map.centerX / map.scale,
            map.height / 2.0 - map.centerZ / map.scale,
            16.0 * window.stride() / map.scale, map.width, map.height
        );
        final List<ColoredRect> rects = new ArrayList<>();
        final int alpha = Math.round(255.0f * (100 - map.config.fullscreenUnloadedBrightnessPercent) / 100);
        if (alpha > 0) appendDimmed(rects, view, alpha << 24);
        if (map.config.fullscreenLoadedRangeStyle == LoadedRangeStyle.DASHED) {
            if (edges == null) edges = coverage.outline();
            appendOutlines(rects, view, window);
        }
        GuiShapes.fillRects(matrices, rects);
    }

    private void appendDimmed(final List<ColoredRect> rects, final Projection view, final int color) {
        final var window = coverage.window();
        final double left = view.x(window.x());
        double top = view.z(window.z());
        view.add(rects, 0, 0, view.width(), top, color);
        final BitSet loaded = coverage.loaded();
        int nextCovered = loaded.nextSetBit(0);
        for (int row = 0; row < window.height(); row++) {
            final double bottom = view.z(window.z() + row + 1);
            if (bottom > 0 && top < view.height()) {
                final int rowStart = row * window.width();
                final int rowEnd = rowStart + window.width();
                double gapStart = 0;
                while (nextCovered >= 0 && nextCovered < rowEnd) {
                    final int after = Math.min(rowEnd, loaded.nextClearBit(nextCovered));
                    view.add(rects, gapStart, top, left + (nextCovered - rowStart) * view.cellPixels(), bottom, color);
                    gapStart = left + (after - rowStart) * view.cellPixels();
                    nextCovered = loaded.nextSetBit(after);
                }
                view.add(rects, gapStart, top, view.width(), bottom, color);
            } else if (nextCovered >= 0 && nextCovered < (row + 1) * window.width()) {
                nextCovered = loaded.nextSetBit((row + 1) * window.width());
            }
            top = bottom;
        }
        view.add(rects, 0, top, view.width(), view.height(), color);
    }

    private void appendOutlines(
        final List<ColoredRect> rects, final Projection view, final ChunkLoadCoverage.Window window
    ) {
        for (final var edge : edges) {
            final boolean horizontal = edge.horizontal();
            final double axis = horizontal ? view.z(window.z() + edge.axis()) : view.x(window.x() + edge.axis());
            final double sideLimit = horizontal ? view.height() : view.width();
            if (axis < -1 || axis > sideLimit + 1) continue;
            final double start = horizontal ? view.x(window.x() + edge.start()) : view.z(window.z() + edge.start());
            final double end = horizontal ? view.x(window.x() + edge.end()) : view.z(window.z() + edge.end());
            final double upper = Math.min(end, horizontal ? view.width() : view.height());
            final double inner = axis + edge.inward();
            final double outer = axis - edge.inward();
            appendStroke(rects, view, horizontal, Math.max(0, start), upper, axis, inner);
            final double first = start + Math.floor((Math.max(0, start) - start) / DASH_PERIOD) * DASH_PERIOD;
            for (double dash = first; dash < upper; dash += DASH_PERIOD) {
                appendStroke(rects, view, horizontal, dash, Math.min(upper, dash + DASH_LENGTH), axis, outer);
            }
        }
    }

    private static void appendStroke(
        final List<ColoredRect> rects, final Projection view, final boolean horizontal,
        final double start, final double end, final double a, final double b
    ) {
        final double min = Math.min(a, b);
        final double max = Math.max(a, b);
        if (horizontal) view.add(rects, start, min, end, max, WHITE);
        else view.add(rects, min, start, max, end, WHITE);
    }

    private record Projection(double originX, double originZ, double cellPixels, int width, int height) {
        double x(final int cell) { return originX + cell * cellPixels; }
        double z(final int cell) { return originZ + cell * cellPixels; }

        void add(
            final List<ColoredRect> rects, final double x1, final double y1,
            final double x2, final double y2, final int color
        ) {
            final double left = Math.max(0, x1);
            final double top = Math.max(0, y1);
            final double right = Math.min(width, x2);
            final double bottom = Math.min(height, y2);
            if (right > left && bottom > top) {
                rects.add(new ColoredRect((float) left, (float) top, (float) (right - left), (float) (bottom - top), color));
            }
        }
    }
}
