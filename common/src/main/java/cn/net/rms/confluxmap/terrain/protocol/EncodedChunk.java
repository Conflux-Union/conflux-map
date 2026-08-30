package cn.net.rms.confluxmap.terrain.protocol;

import java.util.List;

public record EncodedChunk(
    long sessionToken,
    long revision,
    int chunkX,
    int chunkZ,
    int minSectionY,
    int maxSectionY,
    int localPaletteMaxBits,
    int directPaletteBits,
    int airStateId,
    List<EncodedSection> sections
) {
    public EncodedChunk {
        sections = List.copyOf(sections);
        if (maxSectionY < minSectionY) {
            throw new IllegalArgumentException("invalid section range");
        }
    }

    public long estimatedBytes() {
        long bytes = 128L;
        for (final EncodedSection section : sections) {
            bytes += 32L + section.blockStates().length;
        }
        return bytes;
    }
}
