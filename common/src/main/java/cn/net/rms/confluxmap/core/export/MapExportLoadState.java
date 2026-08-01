package cn.net.rms.confluxmap.core.export;

import cn.net.rms.confluxmap.core.net.ChunkLoadBand;
import cn.net.rms.confluxmap.core.net.LoadStateDeltaS2C;
import cn.net.rms.confluxmap.core.util.Argb;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Immutable chunk-load-state color plane captured for one export. */
public final class MapExportLoadState {
    private static final int ENTITY_COLOR = 0x7048B85E;
    private static final int BLOCK_COLOR = 0x70D8A83E;
    private static final int BORDER_COLOR = 0x704E78C4;
    private static final int OUTLINE_COLOR = 0xA0101018;
    private static final MapExportLoadState EMPTY = new MapExportLoadState(List.of());

    private final Map<Long, ChunkLoadBand> bands;

    public MapExportLoadState(final List<LoadStateDeltaS2C.Entry> entries) {
        final Map<Long, ChunkLoadBand> copy = new HashMap<>();
        for (final LoadStateDeltaS2C.Entry entry : entries) {
            if (entry.band() != ChunkLoadBand.UNLOADED) {
                copy.put(key(entry.chunkX(), entry.chunkZ()), entry.band());
            }
        }
        bands = Map.copyOf(copy);
    }

    public static MapExportLoadState empty() {
        return EMPTY;
    }

    public int overlayAt(
        final int blockX,
        final int blockZ,
        final MapExportResolution resolution
    ) {
        final ChunkLoadBand band = bands.get(key(blockX >> 4, blockZ >> 4));
        if (band == null) {
            return Argb.TRANSPARENT;
        }
        final int fill = switch (band) {
            case ENTITY_TICKING -> ENTITY_COLOR;
            case BLOCK_TICKING -> BLOCK_COLOR;
            case BORDER -> BORDER_COLOR;
            case UNLOADED -> Argb.TRANSPARENT;
        };
        final int chunkPixels = 16 >> resolution.lod();
        if (chunkPixels < 4) {
            return fill;
        }
        final int pixelX = (blockX >> resolution.lod()) & (chunkPixels - 1);
        final int pixelZ = (blockZ >> resolution.lod()) & (chunkPixels - 1);
        return pixelX == 0 || pixelZ == 0 || pixelX == chunkPixels - 1 || pixelZ == chunkPixels - 1
            ? Argb.over(OUTLINE_COLOR, fill)
            : fill;
    }

    private static long key(final int chunkX, final int chunkZ) {
        return (long) chunkX << 32 ^ (chunkZ & 0xFFFFFFFFL);
    }
}
