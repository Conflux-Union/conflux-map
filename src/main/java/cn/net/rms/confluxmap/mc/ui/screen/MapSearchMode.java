package cn.net.rms.confluxmap.mc.ui.screen;

enum MapSearchMode {
    STRUCTURE,
    BIOME;

    MapSearchMode toggle() {
        return this == STRUCTURE ? BIOME : STRUCTURE;
    }

    boolean allowed(final boolean structureSearchAllowed) {
        return this == BIOME || structureSearchAllowed;
    }

    int itemCount(final int structureCount, final int biomeCount) {
        return this == STRUCTURE ? structureCount : biomeCount;
    }
}
