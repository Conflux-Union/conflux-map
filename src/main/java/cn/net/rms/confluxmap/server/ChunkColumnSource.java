package cn.net.rms.confluxmap.server;

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

    int biomeIdAt(int x, int y, int z);
}
