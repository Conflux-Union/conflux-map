package cn.net.rms.confluxmap.core.multiworld;

/** Last known player position for one logical world visit. */
public record ClientWorldPosition(int x, int y, int z) {
    public double spatialDistanceTo(final ClientWorldPosition other) {
        final long deltaX = (long) x - other.x;
        final long deltaY = (long) y - other.y;
        final long deltaZ = (long) z - other.z;
        return Math.hypot(Math.hypot(deltaX, deltaY), deltaZ);
    }

    public double horizontalDistanceTo(final ClientWorldPosition other) {
        final long deltaX = (long) x - other.x;
        final long deltaZ = (long) z - other.z;
        return Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
    }
}
