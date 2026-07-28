package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.net.PatchCodec;
import cn.net.rms.confluxmap.core.net.Proto;
import java.util.Arrays;

/** Thread-safe absolute corrections and generated-chunk presence for one predicted tile. */
public final class CorrectionTile {
    public static final int PIXELS = 256 * 256;
    private final PatchCodec.Sample[] samples = new PatchCodec.Sample[PIXELS];
    private final byte[] evaluated = new byte[PatchCodec.MASK_BYTES];
    private final byte[] presence = new byte[Proto.PATCH_PRESENCE_BYTES];
    /** Cumulative revision-0 scan result, replaced on every progressive server update. */
    private final PatchCodec.Sample[] progressSamples = new PatchCodec.Sample[PIXELS];
    private final byte[] progressEvaluated = new byte[PatchCodec.MASK_BYTES];
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
        if (patchRevision < revision) {
            return false;
        }
        clearProgress();
        Arrays.fill(samples, null);
        Arrays.fill(evaluated, (byte) 0);
        final byte[] patchEvaluated = patch.evaluated();
        System.arraycopy(patchEvaluated, 0, evaluated, 0, evaluated.length);
        for (final PatchCodec.Sample sample : patch.samples()) {
            samples[sample.pixelIndex()] = sample;
        }
        System.arraycopy(newPresence, 0, presence, 0, presence.length);
        revision = patchRevision;
        if (patchValidatedAtMillis > 0L) {
            validatedAtMillis = patchValidatedAtMillis;
        }
        return true;
    }

    /**
     * Replaces the pending progressive snapshot without advancing or changing the drawable
     * committed snapshot. The pending state becomes visible only when a final patch commits it.
     */
    public synchronized boolean applyPartial(final byte[] newPresence, final PatchCodec.Patch patch) {
        if (newPresence == null || newPresence.length != Proto.PATCH_PRESENCE_BYTES || patch == null) {
            throw new IllegalArgumentException("invalid partial correction patch");
        }
        final PatchCodec.Sample[] next = new PatchCodec.Sample[PIXELS];
        for (final PatchCodec.Sample sample : patch.samples()) {
            next[sample.pixelIndex()] = sample;
        }
        final byte[] nextEvaluated = patch.evaluated();
        boolean changed = !progressActive
            || !Arrays.equals(progressPresence, newPresence)
            || !Arrays.equals(progressEvaluated, nextEvaluated)
            || !Arrays.equals(progressSamples, next);
        System.arraycopy(next, 0, progressSamples, 0, next.length);
        System.arraycopy(nextEvaluated, 0, progressEvaluated, 0, progressEvaluated.length);
        System.arraycopy(newPresence, 0, progressPresence, 0, progressPresence.length);
        progressActive = true;
        return changed;
    }

    /** Refreshes an unchanged authoritative snapshot without replacing its residual samples. */
    public synchronized boolean validate(
        final long patchRevision,
        final byte[] newPresence,
        final long patchValidatedAtMillis
    ) {
        if (newPresence == null || newPresence.length != Proto.PATCH_PRESENCE_BYTES
            || revision == Long.MIN_VALUE || patchRevision != revision) {
            return false;
        }
        clearProgress();
        System.arraycopy(newPresence, 0, presence, 0, presence.length);
        if (patchValidatedAtMillis > 0L) {
            validatedAtMillis = patchValidatedAtMillis;
        }
        return true;
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

    /** A pending progressive snapshot makes the older validation unusable for cross-LOD composition. */
    public synchronized long reusableValidatedAtMillis() {
        return progressActive ? 0L : validatedAtMillis;
    }

    /** A pending snapshot or a future/old wall-clock stamp is never reusable. */
    public synchronized boolean isFreshAt(final long nowMillis, final long ttlMillis) {
        if (progressActive || validatedAtMillis <= 0L || ttlMillis < 0L || nowMillis < validatedAtMillis) {
            return false;
        }
        return nowMillis - validatedAtMillis <= ttlMillis;
    }

    /** Keeps the last drawable correction while forcing the next viewport use to revalidate it. */
    public synchronized boolean invalidateValidation() {
        if (validatedAtMillis == 0L) {
            return false;
        }
        validatedAtMillis = 0L;
        return true;
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
        return new PatchCodec.Patch(evaluated, copy);
    }

    public synchronized boolean hasGeneratedChunk(final int cellX, final int cellZ) {
        if (cellX < 0 || cellX >= 16 || cellZ < 0 || cellZ >= 16) {
            return false;
        }
        final int index = cellZ * 16 + cellX;
        final int mask = 1 << (index & 7);
        return (presence[index >>> 3] & mask) != 0;
    }

    /** True only when the server evaluated this exact output pixel in the committed snapshot. */
    public synchronized boolean hasGeneratedChunkForPixel(final int pixelIndex, final int lod) {
        // The bitmap is expressed in output pixels, so its lookup no longer depends on LOD. Keep
        // the parameter in the seam used by existing view-mode callers.
        if (pixelIndex < 0 || pixelIndex >= PIXELS) {
            return false;
        }
        return (evaluated[pixelIndex >>> 3] & (1 << (pixelIndex & 7))) != 0;
    }

    public synchronized void clear() {
        Arrays.fill(samples, null);
        Arrays.fill(evaluated, (byte) 0);
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
        Arrays.fill(progressEvaluated, (byte) 0);
        Arrays.fill(progressPresence, (byte) 0);
        progressActive = false;
        return true;
    }
}
