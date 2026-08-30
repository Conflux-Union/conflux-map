package cn.net.rms.confluxmap.terrain;

public final class ChunkVolume {
    private final int chunkX;
    private final int chunkZ;
    private long revision;
    private final int minY;
    private final int height;
    private final int[] stateIds;

    public ChunkVolume(
        final int chunkX,
        final int chunkZ,
        final long revision,
        final int minY,
        final int height,
        final int[] stateIds
    ) {
        if (height < 1 || stateIds.length != height * 256) {
            throw new IllegalArgumentException("chunk volume must contain height * 256 states");
        }
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.revision = revision;
        this.minY = minY;
        this.height = height;
        this.stateIds = stateIds;
    }

    public int chunkX() {
        return chunkX;
    }

    public int chunkZ() {
        return chunkZ;
    }

    public long revision() {
        return revision;
    }

    public int minY() {
        return minY;
    }

    public int height() {
        return height;
    }

    public int[] stateIds() {
        return stateIds;
    }

    public int state(final int x, final int y, final int z) {
        if (y < minY || y >= minY + height) {
            return 0;
        }
        return stateIds[(y - minY) * 256 + z * 16 + x];
    }

    public boolean update(
        final long nextRevision,
        final int x,
        final int y,
        final int z,
        final int stateId
    ) {
        if (x < 0 || x >= 16 || z < 0 || z >= 16 || y < minY || y >= minY + height) {
            return false;
        }
        stateIds[(y - minY) * 256 + z * 16 + x] = stateId;
        revision = Math.max(revision, nextRevision);
        return true;
    }
}
