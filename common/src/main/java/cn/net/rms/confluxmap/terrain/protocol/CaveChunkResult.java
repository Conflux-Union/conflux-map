package cn.net.rms.confluxmap.terrain.protocol;

public record CaveChunkResult(
    int chunkX,
    int chunkZ,
    long revision,
    int pivotY,
    short[] surfaceY,
    int[] floorStateId,
    int[] overlayStateId,
    boolean[] crossSection
) {
    public CaveChunkResult {
        if (surfaceY.length != 256 || floorStateId.length != 256
            || overlayStateId.length != 256 || crossSection.length != 256) {
            throw new IllegalArgumentException("cave result arrays must contain 256 entries");
        }
    }
}
