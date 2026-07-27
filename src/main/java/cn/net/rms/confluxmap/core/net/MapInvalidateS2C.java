package cn.net.rms.confluxmap.core.net;

import java.util.List;

/** S2C batch marking correction tiles stale after one of their source regions changes. */
public record MapInvalidateS2C(int dimIndex, int lod, List<Tile> tiles) implements Message {
    public MapInvalidateS2C {
        tiles = tiles == null ? List.of() : List.copyOf(tiles);
    }

    public record Tile(int tileX, int tileZ) {
    }

    @Override
    public int typeId() {
        return Proto.MSG_MAP_INVALIDATE_S2C;
    }
}
