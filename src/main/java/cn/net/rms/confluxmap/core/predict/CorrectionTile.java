package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.net.PatchCodec;
import cn.net.rms.confluxmap.core.net.Proto;
import java.util.Arrays;

/** Thread-safe absolute corrections and generated-chunk presence for one predicted tile. */
public final class CorrectionTile {
    public static final int PIXELS = 256 * 256;
    private final PatchCodec.Sample[] samples = new PatchCodec.Sample[PIXELS];
    private final long[] pixelRevision = new long[PIXELS];
    private final byte[] presence = new byte[Proto.PATCH_PRESENCE_BYTES];
    private long revision = Long.MIN_VALUE;

    public synchronized boolean applyPatch(final long patchRevision, final byte[] newPresence, final PatchCodec.Patch patch) {
        if (newPresence == null || newPresence.length != Proto.PATCH_PRESENCE_BYTES || patch == null) {
            throw new IllegalArgumentException("invalid correction patch");
        }
        boolean changed = false;
        if (patchRevision >= revision) {
            System.arraycopy(newPresence, 0, presence, 0, presence.length);
            revision = patchRevision;
            changed = true;
        }
        for (final PatchCodec.Sample sample : patch.samples()) {
            final int index = sample.pixelIndex();
            if (patchRevision >= pixelRevision[index]) {
                if (PatchCodec.isRemoval(sample)) {
                    if (samples[index] != null) {
                        samples[index] = null;
                        changed = true;
                    }
                } else if (!sample.equals(samples[index])) {
                    samples[index] = sample;
                    changed = true;
                }
                pixelRevision[index] = patchRevision;
            }
        }
        return changed;
    }

    public synchronized long revision() {
        return revision == Long.MIN_VALUE ? 0L : revision;
    }

    public synchronized byte[] presence() {
        return presence.clone();
    }

    public synchronized PatchCodec.Sample sampleAt(final int pixelIndex) {
        return samples[pixelIndex];
    }

    public synchronized PatchCodec.Patch copyPatch() {
        final java.util.ArrayList<PatchCodec.Sample> copy = new java.util.ArrayList<>();
        for (final PatchCodec.Sample sample : samples) {
            if (sample != null) {
                copy.add(sample);
            }
        }
        return new PatchCodec.Patch(copy);
    }

    public synchronized boolean hasGeneratedChunk(final int cellX, final int cellZ) {
        if (cellX < 0 || cellX >= 16 || cellZ < 0 || cellZ >= 16) {
            return false;
        }
        final int index = cellZ * 16 + cellX;
        return (presence[index >>> 3] & (1 << (index & 7))) != 0;
    }

    /** Presence cells are 16x16 output-pixel blocks; at LOD0 each block is exactly one chunk. */
    public synchronized boolean hasGeneratedChunkForPixel(final int pixelIndex, final int lod) {
        // The bitmap is expressed in output pixels, so its lookup no longer depends on LOD. Keep
        // the parameter in the seam used by existing view-mode callers.
        if (pixelIndex < 0 || pixelIndex >= PIXELS) {
            return false;
        }
        final int cellX = (pixelIndex & 255) >>> 4;
        final int cellZ = (pixelIndex >>> 8) >>> 4;
        return hasGeneratedChunk(cellX, cellZ);
    }

    public synchronized void clear() {
        Arrays.fill(samples, null);
        Arrays.fill(pixelRevision, 0L);
        Arrays.fill(presence, (byte) 0);
        revision = Long.MIN_VALUE;
    }
}
