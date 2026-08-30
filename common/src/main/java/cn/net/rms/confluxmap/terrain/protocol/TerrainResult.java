package cn.net.rms.confluxmap.terrain.protocol;

public record TerrainResult(long sessionToken, long generation, CaveChunkResult result) {
}
