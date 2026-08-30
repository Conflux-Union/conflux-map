package cn.net.rms.confluxmap.core.terrain;

public record TerrainDelta(
    long sessionToken,
    long revision,
    int chunkX,
    int chunkZ,
    int localX,
    int y,
    int localZ,
    int stateId
) {
}
