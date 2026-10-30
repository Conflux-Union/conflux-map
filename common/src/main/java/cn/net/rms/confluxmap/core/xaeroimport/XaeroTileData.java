package cn.net.rms.confluxmap.core.xaeroimport;

import java.util.Arrays;

/**
 * A decoded 16x16-column tile (one Minecraft chunk of map data) together with
 * its tile-trailer context.
 *
 * @param interpretationVersion writer interpretation of top-height semantics;
 *     0 means the tile carries no reliable top-height information
 * @param caveStart cave context when written: {@code Integer.MAX_VALUE} for
 *     surface-layer data, {@code Integer.MIN_VALUE} for full-height cave data,
 *     any other value the Y where a cave layer's view starts
 * @param caveDepth depth in blocks of the cave view below {@code caveStart}
 * @param columns 256 entries indexed {@code z * 16 + x}, never null elements
 */
public record XaeroTileData(int interpretationVersion, int caveStart, int caveDepth, XaeroColumn[] columns) {
    public static final int SURFACE_CAVE_START = Integer.MAX_VALUE;

    public XaeroTileData {
        if (columns == null || columns.length != 256) {
            throw new IllegalArgumentException("tile columns must have 256 entries");
        }
        columns = columns.clone();
    }

    public XaeroColumn column(final int x, final int z) {
        return columns[z * 16 + x];
    }

    @Override
    public boolean equals(final Object o) {
        return o instanceof XaeroTileData other
            && interpretationVersion == other.interpretationVersion
            && caveStart == other.caveStart
            && caveDepth == other.caveDepth
            && Arrays.equals(columns, other.columns);
    }

    @Override
    public int hashCode() {
        int result = interpretationVersion;
        result = 31 * result + caveStart;
        result = 31 * result + caveDepth;
        return 31 * result + Arrays.hashCode(columns);
    }
}
