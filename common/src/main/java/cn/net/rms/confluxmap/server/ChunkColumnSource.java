package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.core.model.SurfaceKind;

/**
 * Narrow input seam for one generated chunk's surface columns.
 *
 * <p>The summarizer depends only on this contract. Serialized NBT and a live Minecraft chunk can
 * therefore provide the same data without leaking either representation into the classification
 * algorithm or its tests. Height methods use vanilla heightmap semantics: the returned value is
 * one block above the represented surface.
 */
public interface ChunkColumnSource {
    int NO_HEIGHT = Integer.MIN_VALUE;

    boolean generated();

    long revision();

    int bottomY();

    int motionBlockingHeight(int x, int z);

    /** Returns {@link #NO_HEIGHT} when the source has no ocean-floor heightmap. */
    int oceanFloorHeight(int x, int z);

    String blockNameAt(int x, int y, int z);

    /**
     * Fluid occupying this block state, independent of its registry name. Implementations backed by
     * live chunks must read {@code FluidState}; serialized implementations must retain the palette
     * properties needed to recover waterlogged and submerged-plant states.
     */
    default SurfaceKind fluidKindAt(final int x, final int y, final int z) {
        final String name = blockNameAt(x, y, z);
        if (name == null) {
            return SurfaceKind.UNKNOWN;
        }
        if (name.contains("lava")) {
            return SurfaceKind.LAVA;
        }
        if (name.contains("water") || "minecraft:kelp".equals(name) || "minecraft:kelp_plant".equals(name)
            || "minecraft:seagrass".equals(name) || "minecraft:tall_seagrass".equals(name)
            || "minecraft:bubble_column".equals(name) || "minecraft:sea_pickle".equals(name)) {
            return SurfaceKind.WATER;
        }
        return SurfaceKind.UNKNOWN;
    }

    int biomeIdAt(int x, int y, int z);

    /** Block light at the air block directly above the selected surface. */
    default int blockLightAbove(final int x, final int surfaceY, final int z) {
        return 0;
    }
}
