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
    /** Cumulative revision-0 scan result, replaced on every progressive server update. */
    private final PatchCodec.Sample[] progressSamples = new PatchCodec.Sample[PIXELS];
    private final byte[] progressPresence = new byte[Proto.PATCH_PRESENCE_BYTES];
    private boolean progressActive;
    private long revision = Long.MIN_VALUE;
    /** Client wall-clock time of the newest committed server validation; zero means unvalidated. */
    private long validatedAtMillis;

    public synchronized boolean applyPatch(final long patchRevision, final byte[] newPresence, final PatchCodec.Patch patch) {
        return applyPatch(patchRevision, newPresence, patch, 0L);
    }

    public synchronized boolean applyPatch(
        final long patchRevision,
        final byte[] newPresence,
        final PatchCodec.Patch patch,
        final long patchValidatedAtMillis
    ) {
        if (newPresence == null || newPresence.length != Proto.PATCH_PRESENCE_BYTES || patch == null) {
            throw new IllegalArgumentException("invalid correction patch");
        }
        boolean changed = false;
        if (patchRevision >= revision) {
            changed = clearProgress();
            System.arraycopy(newPresence, 0, presence, 0, presence.length);
            revision = patchRevision;
            if (patchValidatedAtMillis > 0L && patchValidatedAtMillis != validatedAtMillis) {
                validatedAtMillis = patchValidatedAtMillis;
            }
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

    /**
     * Replaces the in-progress scan overlay without advancing the committed tile watermark.
     * Removal markers suppress an older committed correction only while that scan is active.
     */
    public synchronized boolean applyPartial(final byte[] newPresence, final PatchCodec.Patch patch) {
        if (newPresence == null || newPresence.length != Proto.PATCH_PRESENCE_BYTES || patch == null) {
            throw new IllegalArgumentException("invalid partial correction patch");
        }
        final PatchCodec.Sample[] next = new PatchCodec.Sample[PIXELS];
        for (final PatchCodec.Sample sample : patch.samples()) {
            next[sample.pixelIndex()] = sample;
        }
        boolean changed = !progressActive
            || !Arrays.equals(progressPresence, newPresence)
            || !Arrays.equals(progressSamples, next);
        System.arraycopy(next, 0, progressSamples, 0, next.length);
        System.arraycopy(newPresence, 0, progressPresence, 0, progressPresence.length);
        progressActive = true;
        return changed;
    }

    public synchronized long revision() {
        return revision == Long.MIN_VALUE ? 0L : revision;
    }

    public synchronized long validatedAtMillis() {
        return validatedAtMillis;
    }

    /** Whether this tile contains a committed server answer, including an empty revision-0 answer. */
    public synchronized boolean hasCommittedState() {
        return revision != Long.MIN_VALUE;
    }

    /** A progressive overlay makes the older validation unusable for cross-LOD composition. */
    public synchronized long reusableValidatedAtMillis() {
        return progressActive ? 0L : validatedAtMillis;
    }

    /** A progressive overlay or a future/old wall-clock stamp is never reusable. */
    public synchronized boolean isFreshAt(final long nowMillis, final long ttlMillis) {
        if (progressActive || validatedAtMillis <= 0L || ttlMillis < 0L || nowMillis < validatedAtMillis) {
            return false;
        }
        return nowMillis - validatedAtMillis <= ttlMillis;
    }

    public synchronized byte[] presence() {
        final byte[] visible = presence.clone();
        if (progressActive) {
            for (int i = 0; i < visible.length; i++) {
                visible[i] |= progressPresence[i];
            }
        }
        return visible;
    }

    public synchronized PatchCodec.Sample sampleAt(final int pixelIndex) {
        if (progressActive && progressSamples[pixelIndex] != null) {
            final PatchCodec.Sample progress = progressSamples[pixelIndex];
            return PatchCodec.isRemoval(progress) ? null : progress;
        }
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
        final int mask = 1 << (index & 7);
        return (presence[index >>> 3] & mask) != 0
            || (progressActive && (progressPresence[index >>> 3] & mask) != 0);
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
        clearProgress();
        revision = Long.MIN_VALUE;
        validatedAtMillis = 0L;
    }

    private boolean clearProgress() {
        if (!progressActive) {
            return false;
        }
        Arrays.fill(progressSamples, null);
        Arrays.fill(progressPresence, (byte) 0);
        progressActive = false;
        return true;
    }
}
