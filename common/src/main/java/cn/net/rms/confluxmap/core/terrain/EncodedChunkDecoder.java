package cn.net.rms.confluxmap.core.terrain;

import java.io.IOException;
import java.util.Arrays;

/** Decodes the wire representation into the worker's mutable calculation model. */
final class EncodedChunkDecoder {
    private EncodedChunkDecoder() {
    }

    static ChunkVolume decode(final EncodedChunk encoded) throws IOException {
        final int height = (encoded.maxSectionY() - encoded.minSectionY() + 1) * 16;
        final int[] states = new int[height * 256];
        Arrays.fill(states, encoded.airStateId());
        for (final EncodedSection section : encoded.sections()) {
            if (section.sectionY() < encoded.minSectionY()
                || section.sectionY() > encoded.maxSectionY()) {
                throw new IOException("section outside declared chunk range");
            }
            final int[] decoded = PalettedSection.decode(
                section.blockStates(), encoded.localPaletteMaxBits(),
                encoded.directPaletteBits(), 4096
            );
            final int offset = (section.sectionY() - encoded.minSectionY()) * 4096;
            System.arraycopy(decoded, 0, states, offset, decoded.length);
        }
        return new ChunkVolume(
            encoded.chunkX(), encoded.chunkZ(), encoded.revision(),
            encoded.minSectionY() * 16, height, states
        );
    }
}
