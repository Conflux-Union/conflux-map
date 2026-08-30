package cn.net.rms.confluxmap.terrain.protocol;

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
