package cn.net.rms.confluxmap.core.multiworld;

import java.util.Objects;

/** A complete terrain observation tied to the chunk center where it was captured. */
public record ClientWorldTerrainAnchor(
    ClientWorldPosition position,
    ClientWorldTerrainFingerprint fingerprint,
    long capturedAtEpochMs
) {
    public ClientWorldTerrainAnchor {
        position = Objects.requireNonNull(position, "position");
        fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        if (!fingerprint.complete() || !fingerprint.hasCenter()) {
            throw new IllegalArgumentException("terrain anchor requires a complete centered fingerprint");
        }
        if ((position.x() >> 4) != fingerprint.centerChunkX()
            || (position.z() >> 4) != fingerprint.centerChunkZ()) {
            throw new IllegalArgumentException("terrain anchor position must match fingerprint center");
        }
        if (capturedAtEpochMs < 0L) {
            throw new IllegalArgumentException("capturedAtEpochMs must not be negative");
        }
    }
}
