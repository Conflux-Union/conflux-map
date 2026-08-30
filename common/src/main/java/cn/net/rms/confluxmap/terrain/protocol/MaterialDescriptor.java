package cn.net.rms.confluxmap.terrain.protocol;

/** Only the Minecraft-dependent decisions required by the process-side floor scan. */
public record MaterialDescriptor(boolean openForFloorScan, boolean overlayCandidate) {
}
