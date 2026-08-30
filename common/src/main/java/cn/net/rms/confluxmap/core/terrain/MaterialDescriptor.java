package cn.net.rms.confluxmap.core.terrain;

/** Only the Minecraft-dependent decisions required by the terrain floor scan. */
public record MaterialDescriptor(boolean openForFloorScan, boolean overlayCandidate) {
}
