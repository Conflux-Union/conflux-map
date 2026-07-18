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
                if (!sample.equals(samples[index])) {
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

    public synchronized boolean hasGeneratedChunk(final int chunkX, final int chunkZ) {
        if (chunkX < 0 || chunkX >= 16 || chunkZ < 0 || chunkZ >= 16) {
            return false;
        }
        final int index = chunkZ * 16 + chunkX;
        return (presence[index >>> 3] & (1 << (index & 7))) != 0;
    }

    /** Presence cells cover every chunk touched by a LOD pixel; any generated chunk makes it visible. */
    public synchronized boolean hasGeneratedChunkForPixel(final int pixelIndex, final int lod) {
        final int x = pixelIndex & 255;
        final int z = pixelIndex >>> 8;
        final int blocks = 1 << Math.max(0, Math.min(4, lod));
        final int startX = (x * blocks) >>> 4;
        final int startZ = (z * blocks) >>> 4;
        final int endX = ((x + 1) * blocks - 1) >>> 4;
        final int endZ = ((z + 1) * blocks - 1) >>> 4;
        for (int cz = startZ; cz <= Math.min(15, endZ); cz++) {
            for (int cx = startX; cx <= Math.min(15, endX); cx++) {
                if (hasGeneratedChunk(cx, cz)) {
                    return true;
                }
            }
        }
        return false;
    }

    public synchronized void clear() {
        Arrays.fill(samples, null);
        Arrays.fill(pixelRevision, 0L);
        Arrays.fill(presence, (byte) 0);
        revision = Long.MIN_VALUE;
    }
}
