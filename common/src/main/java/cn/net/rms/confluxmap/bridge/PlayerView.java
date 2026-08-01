package cn.net.rms.confluxmap.bridge;

import cn.net.rms.confluxmap.core.model.DimensionId;

/** Immutable snapshot of the local player's pose, taken on the main thread. */
public record PlayerView(
    double x,
    double y,
    double z,
    float yawDegrees,
    DimensionId dimension
) {
    public int blockX() {
        return (int) Math.floor(x);
    }

    public int blockY() {
        return (int) Math.floor(y);
    }

    public int blockZ() {
        return (int) Math.floor(z);
    }
}
