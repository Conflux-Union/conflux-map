package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.net.ChunkPatchCodec;
import cn.net.rms.confluxmap.core.net.PatchCodec;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.net.MapSyncCompatibility;
import java.util.Arrays;

/** Thread-safe committed corrections, source profile, and generated-chunk presence for one tile. */
public final class CorrectionTile {
    public static final int PIXELS = 256 * 256;
    private final int lod;
    private final int chunksPerSide;
    private final int samplesPerChunk;
    private final PatchCodec.Sample[] samples = new PatchCodec.Sample[PIXELS];
    private final byte[] evaluated = new byte[PatchCodec.MASK_BYTES];
    private final byte[] presence = new byte[Proto.PATCH_PRESENCE_BYTES];
    /** Cumulative revision-0 scan result, replaced on every progressive server update. */
    private final PatchCodec.Sample[] progressSamples = new PatchCodec.Sample[PIXELS];
    private final byte[] progressEvaluated = new byte[PatchCodec.MASK_BYTES];
    private final byte[] progressPresence = new byte[Proto.PATCH_PRESENCE_BYTES];
    private boolean progressActive;
    private final boolean[] generatedChunks;
    private final long[] chunkRevisions;
    private final long[] chunkValidatedAtMillis;
    private long revision = Long.MIN_VALUE;
    /** Client wall-clock time of the newest committed server validation; zero means unvalidated. */
    private long validatedAtMillis;
    private int patchMode = Proto.PATCH_MODE_RESIDUAL;
    private String baselineProfile = MapSyncCompatibility.STABLE_PREDICTOR;

    public CorrectionTile() {
        this(0);
    }

    public CorrectionTile(final int lod) {
        if (lod < 0 || lod > 4) {
            throw new IllegalArgumentException("unsupported correction LOD " + lod);
        }
        this.lod = lod;
        this.chunksPerSide = 16 << lod;
        this.samplesPerChunk = 16 >> lod;
        final int chunks = chunksPerSide * chunksPerSide;
        this.generatedChunks = new boolean[chunks];
        this.chunkRevisions = new long[chunks];
        this.chunkValidatedAtMillis = new long[chunks];
        Arrays.fill(chunkRevisions, Long.MIN_VALUE);
    }

    public synchronized boolean applyPatch(final long patchRevision, final byte[] newPresence, final PatchCodec.Patch patch) {
        return applyPatch(patchRevision, newPresence, patch, 0L);
    }

    public synchronized boolean applyPatch(
        final long patchRevision,
        final byte[] newPresence,
        final PatchCodec.Patch patch,
        final long patchValidatedAtMillis
    ) {
        return applyPatch(
            patchRevision, newPresence, patch,
            Proto.PATCH_MODE_RESIDUAL, MapSyncCompatibility.STABLE_PREDICTOR,
            patchValidatedAtMillis
        );
    }

    public synchronized boolean applyPatch(
        final long patchRevision,
        final byte[] newPresence,
        final PatchCodec.Patch patch,
        final int newPatchMode,
        final String newBaselineProfile,
        final long patchValidatedAtMillis
    ) {
        if (newPresence == null || newPresence.length != Proto.PATCH_PRESENCE_BYTES || patch == null) {
            throw new IllegalArgumentException("invalid correction patch");
        }
        checkSource(newPatchMode, newBaselineProfile);
        clearProgress();
        Arrays.fill(samples, null);
        Arrays.fill(evaluated, (byte) 0);
        final byte[] patchEvaluated = patch.evaluated();
        System.arraycopy(patchEvaluated, 0, evaluated, 0, evaluated.length);
        for (final PatchCodec.Sample sample : patch.samples()) {
            samples[sample.pixelIndex()] = sample;
        }
        System.arraycopy(newPresence, 0, presence, 0, presence.length);
        Arrays.fill(generatedChunks, false);
        restoreGeneratedChunksFromPresence(newPresence);
        Arrays.fill(chunkRevisions, Long.MIN_VALUE);
        Arrays.fill(chunkValidatedAtMillis, 0L);
        revision = patchRevision;
        validatedAtMillis = Math.max(0L, patchValidatedAtMillis);
        patchMode = newPatchMode;
        baselineProfile = newBaselineProfile;
        return true;
    }

    /** Atomically replaces only the output pixels owned by one cropped chunk-region page. */
    public synchronized boolean applyRegionSlice(
        final int minTileChunkX,
        final int minTileChunkZ,
        final ChunkPatchCodec.Patch patch,
        final long patchValidatedAtMillis
    ) {
        return applyRegionSlice(
            minTileChunkX, minTileChunkZ, patch,
            Proto.PATCH_MODE_RESIDUAL, MapSyncCompatibility.STABLE_PREDICTOR,
            patchValidatedAtMillis
        );
    }

    public synchronized boolean applyRegionSlice(
        final int minTileChunkX,
        final int minTileChunkZ,
        final ChunkPatchCodec.Patch patch,
        final int newPatchMode,
        final String newBaselineProfile,
        final long patchValidatedAtMillis
    ) {
        checkRegionSlice(minTileChunkX, minTileChunkZ, patch.chunkWidth(), patch.chunkHeight());
        if (patch.samplesPerChunk() != samplesPerChunk || patchValidatedAtMillis <= 0L) {
            throw new IllegalArgumentException("region patch does not match correction tile LOD");
        }
        checkSource(newPatchMode, newBaselineProfile);
        final boolean hadCommittedState = hasCommittedState();
        if (hadCommittedState
            && patchMode == Proto.PATCH_MODE_RESIDUAL
            && newPatchMode == Proto.PATCH_MODE_RESIDUAL
            && !baselineProfile.equals(newBaselineProfile)) {
            clearCommitted();
        }
        if (!hadCommittedState
            || patchMode == Proto.PATCH_MODE_ABSOLUTE
            || newPatchMode == Proto.PATCH_MODE_RESIDUAL) {
            patchMode = newPatchMode;
            baselineProfile = newBaselineProfile;
        }
        clearProgress();
        final PatchCodec.Sample[] sourceSamples = new PatchCodec.Sample[patch.pixelCount()];
        for (final PatchCodec.Sample sample : patch.samples()) {
            sourceSamples[sample.pixelIndex()] = sample;
        }
        final int sourceWidth = patch.sampleWidth();
        for (int chunkZ = 0; chunkZ < patch.chunkHeight(); chunkZ++) {
            for (int chunkX = 0; chunkX < patch.chunkWidth(); chunkX++) {
                for (int sampleZ = 0; sampleZ < samplesPerChunk; sampleZ++) {
                    for (int sampleX = 0; sampleX < samplesPerChunk; sampleX++) {
                        final int sourcePixel = (chunkZ * samplesPerChunk + sampleZ) * sourceWidth
                            + chunkX * samplesPerChunk + sampleX;
                        final int targetX = (minTileChunkX + chunkX) * samplesPerChunk + sampleX;
                        final int targetZ = (minTileChunkZ + chunkZ) * samplesPerChunk + sampleZ;
                        final int targetPixel = targetZ * 256 + targetX;
                        samples[targetPixel] = null;
                        clearBit(evaluated, targetPixel);
                        if (patch.evaluatedAt(sourcePixel)) {
                            setBit(evaluated, targetPixel);
                        }
                        final PatchCodec.Sample source = sourceSamples[sourcePixel];
                        if (source != null) {
                            samples[targetPixel] = new PatchCodec.Sample(targetPixel, source.pixel());
                        }
                    }
                }
            }
        }
        final long[] revisions = ChunkPatchCodec.chunkRevisions(patch);
        for (int chunkZ = 0; chunkZ < patch.chunkHeight(); chunkZ++) {
            for (int chunkX = 0; chunkX < patch.chunkWidth(); chunkX++) {
                final int sourceChunk = chunkZ * patch.chunkWidth() + chunkX;
                final int targetChunk = (minTileChunkZ + chunkZ) * chunksPerSide + minTileChunkX + chunkX;
                generatedChunks[targetChunk] = patch.generatedAt(sourceChunk);
                chunkRevisions[targetChunk] = revisions[sourceChunk];
                chunkValidatedAtMillis[targetChunk] = patchValidatedAtMillis;
            }
        }
        rebuildPresence();
        revision = Long.MIN_VALUE;
        validatedAtMillis = completeValidatedAt();
        return true;
    }

    public synchronized boolean validateRegionSlice(
        final int minTileChunkX,
        final int minTileChunkZ,
        final int chunkWidth,
        final int chunkHeight,
        final long expectedRevision,
        final long patchValidatedAtMillis,
        final int regionX,
        final int regionZ,
        final int minRegionChunkX,
        final int minRegionChunkZ
    ) {
        checkRegionSlice(minTileChunkX, minTileChunkZ, chunkWidth, chunkHeight);
        if (patchValidatedAtMillis <= 0L) {
            return false;
        }
        final long current = regionSliceRevision(
            minTileChunkX, minTileChunkZ, chunkWidth, chunkHeight,
            regionX, regionZ, minRegionChunkX, minRegionChunkZ
        );
        if (current == Long.MIN_VALUE || current != expectedRevision) {
            return false;
        }
        for (int z = 0; z < chunkHeight; z++) {
            for (int x = 0; x < chunkWidth; x++) {
                chunkValidatedAtMillis[(minTileChunkZ + z) * chunksPerSide + minTileChunkX + x] =
                    patchValidatedAtMillis;
            }
        }
        validatedAtMillis = completeValidatedAt();
        return true;
    }

    public synchronized boolean regionSliceFreshAt(
        final int minTileChunkX,
        final int minTileChunkZ,
        final int chunkWidth,
        final int chunkHeight,
        final long nowMillis,
        final long ttlMillis
    ) {
        checkRegionSlice(minTileChunkX, minTileChunkZ, chunkWidth, chunkHeight);
        for (int z = 0; z < chunkHeight; z++) {
            for (int x = 0; x < chunkWidth; x++) {
                final long stamp = chunkValidatedAtMillis[
                    (minTileChunkZ + z) * chunksPerSide + minTileChunkX + x
                ];
                if (stamp <= 0L || nowMillis < stamp || nowMillis - stamp > ttlMillis) {
                    return false;
                }
            }
        }
        return true;
    }

    public synchronized long regionSliceRevision(
        final int minTileChunkX,
        final int minTileChunkZ,
        final cn.net.rms.confluxmap.core.util.ChunkRegionSlice slice
    ) {
        if (slice == null) {
            return Long.MIN_VALUE;
        }
        checkRegionSlice(minTileChunkX, minTileChunkZ, slice.width(), slice.height());
        return regionSliceRevision(
            minTileChunkX, minTileChunkZ, slice.width(), slice.height(),
            slice.regionX(), slice.regionZ(), slice.minLocalChunkX(), slice.minLocalChunkZ()
        );
    }

    public synchronized boolean invalidateRegionSlice(
        final int minTileChunkX,
        final int minTileChunkZ,
        final int chunkWidth,
        final int chunkHeight
    ) {
        checkRegionSlice(minTileChunkX, minTileChunkZ, chunkWidth, chunkHeight);
        boolean changed = validatedAtMillis != 0L;
        for (int z = 0; z < chunkHeight; z++) {
            for (int x = 0; x < chunkWidth; x++) {
                final int index = (minTileChunkZ + z) * chunksPerSide + minTileChunkX + x;
                if (chunkValidatedAtMillis[index] != 0L) {
                    chunkValidatedAtMillis[index] = 0L;
                    changed = true;
                }
            }
        }
        validatedAtMillis = 0L;
        return changed;
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

    /** Raw tile-wide revision for persistence; {@link Long#MIN_VALUE} means region pages only. */
    public synchronized long storedRevision() {
        return revision;
    }

    public synchronized boolean hasTileSnapshot() {
        return revision != Long.MIN_VALUE;
    }

    public synchronized long validatedAtMillis() {
        return validatedAtMillis;
    }

    /** Whether this tile contains a committed server answer, including an empty revision-0 answer. */
    public synchronized boolean hasCommittedState() {
        if (revision != Long.MIN_VALUE) {
            return true;
        }
        for (final long chunkRevision : chunkRevisions) {
            if (chunkRevision != Long.MIN_VALUE) {
                return true;
            }
        }
        return false;
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
        boolean changed = validatedAtMillis != 0L;
        validatedAtMillis = 0L;
        for (int i = 0; i < chunkValidatedAtMillis.length; i++) {
            if (chunkValidatedAtMillis[i] != 0L) {
                chunkValidatedAtMillis[i] = 0L;
                changed = true;
            }
        }
        return changed;
    }

    public synchronized byte[] presence() {
        return presence.clone();
    }

    public synchronized PatchCodec.Sample sampleAt(final int pixelIndex) {
        return samples[pixelIndex];
    }

    public synchronized int patchMode() {
        return patchMode;
    }

    public synchronized String baselineProfile() {
        return baselineProfile;
    }

    /** Absolute snapshots are baseline-independent; residual snapshots require an exact profile. */
    public synchronized boolean matchesSource(
        final int expectedPatchMode,
        final String expectedBaselineProfile
    ) {
        if (patchMode == Proto.PATCH_MODE_ABSOLUTE) {
            return expectedPatchMode == Proto.PATCH_MODE_RESIDUAL
                || expectedPatchMode == Proto.PATCH_MODE_ABSOLUTE;
        }
        return expectedPatchMode == Proto.PATCH_MODE_RESIDUAL
            && expectedBaselineProfile != null
            && baselineProfile.equals(expectedBaselineProfile);
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

    public synchronized byte[] copyGeneratedChunkMask() {
        final byte[] generated = new byte[ChunkPatchCodec.maskBytes(generatedChunks.length)];
        for (int chunk = 0; chunk < generatedChunks.length; chunk++) {
            if (generatedChunks[chunk]) {
                ChunkPatchCodec.setBit(generated, chunk);
            }
        }
        return generated;
    }

    public synchronized long[] copyChunkRevisions() {
        return chunkRevisions.clone();
    }

    public synchronized long[] copyChunkValidatedAtMillis() {
        return chunkValidatedAtMillis.clone();
    }

    public synchronized long newestValidatedAtMillis() {
        long newest = validatedAtMillis;
        for (final long chunkValidatedAt : chunkValidatedAtMillis) {
            newest = Math.max(newest, chunkValidatedAt);
        }
        return newest;
    }

    /** Restores v16 page metadata after the drawable pixel snapshot has been loaded. */
    public synchronized void restoreChunkMetadata(
        final byte[] generated,
        final long[] revisions,
        final long[] validated
    ) {
        if (generated == null || generated.length != ChunkPatchCodec.maskBytes(generatedChunks.length)
            || revisions == null || revisions.length != chunkRevisions.length
            || validated == null || validated.length != chunkValidatedAtMillis.length) {
            throw new IllegalArgumentException("chunk correction metadata has the wrong length");
        }
        boolean hasKnownChunk = false;
        for (int chunk = 0; chunk < generatedChunks.length; chunk++) {
            generatedChunks[chunk] = (generated[chunk >>> 3] & (1 << (chunk & 7))) != 0;
            chunkRevisions[chunk] = revisions[chunk];
            chunkValidatedAtMillis[chunk] = validated[chunk];
            hasKnownChunk |= revisions[chunk] != Long.MIN_VALUE;
        }
        rebuildPresence();
        if (hasKnownChunk) {
            revision = Long.MIN_VALUE;
            validatedAtMillis = completeValidatedAt();
        }
    }

    private static void checkSource(final int mode, final String profile) {
        if ((mode != Proto.PATCH_MODE_RESIDUAL && mode != Proto.PATCH_MODE_ABSOLUTE)
            || profile == null || (mode == Proto.PATCH_MODE_RESIDUAL && profile.isEmpty())) {
            throw new IllegalArgumentException("invalid correction source");
        }
    }

    private void clearCommitted() {
        clearProgress();
        Arrays.fill(samples, null);
        Arrays.fill(evaluated, (byte) 0);
        Arrays.fill(presence, (byte) 0);
        Arrays.fill(generatedChunks, false);
        Arrays.fill(chunkRevisions, Long.MIN_VALUE);
        Arrays.fill(chunkValidatedAtMillis, 0L);
        revision = Long.MIN_VALUE;
        validatedAtMillis = 0L;
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
        Arrays.fill(generatedChunks, false);
        Arrays.fill(chunkRevisions, Long.MIN_VALUE);
        Arrays.fill(chunkValidatedAtMillis, 0L);
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

    private void rebuildPresence() {
        Arrays.fill(presence, (byte) 0);
        for (int chunkZ = 0; chunkZ < chunksPerSide; chunkZ++) {
            for (int chunkX = 0; chunkX < chunksPerSide; chunkX++) {
                if (!generatedChunks[chunkZ * chunksPerSide + chunkX]) {
                    continue;
                }
                final int cellX = chunkX * samplesPerChunk >>> 4;
                final int cellZ = chunkZ * samplesPerChunk >>> 4;
                setBit(presence, cellZ * 16 + cellX);
            }
        }
    }

    private void restoreGeneratedChunksFromPresence(final byte[] sourcePresence) {
        final int chunksPerPresenceCell = chunksPerSide / 16;
        for (int cellZ = 0; cellZ < 16; cellZ++) {
            for (int cellX = 0; cellX < 16; cellX++) {
                final int cell = cellZ * 16 + cellX;
                if ((sourcePresence[cell >>> 3] & (1 << (cell & 7))) == 0) {
                    continue;
                }
                for (int offsetZ = 0; offsetZ < chunksPerPresenceCell; offsetZ++) {
                    for (int offsetX = 0; offsetX < chunksPerPresenceCell; offsetX++) {
                        final int chunkX = cellX * chunksPerPresenceCell + offsetX;
                        final int chunkZ = cellZ * chunksPerPresenceCell + offsetZ;
                        generatedChunks[chunkZ * chunksPerSide + chunkX] = true;
                    }
                }
            }
        }
    }

    private long completeValidatedAt() {
        long minimum = Long.MAX_VALUE;
        for (int i = 0; i < chunkRevisions.length; i++) {
            if (chunkRevisions[i] == Long.MIN_VALUE || chunkValidatedAtMillis[i] <= 0L) {
                return 0L;
            }
            minimum = Math.min(minimum, chunkValidatedAtMillis[i]);
        }
        return minimum == Long.MAX_VALUE ? 0L : minimum;
    }

    private long regionSliceRevision(
        final int minTileChunkX,
        final int minTileChunkZ,
        final int chunkWidth,
        final int chunkHeight,
        final int regionX,
        final int regionZ,
        final int minRegionChunkX,
        final int minRegionChunkZ
    ) {
        final long[] revisions = new long[chunkWidth * chunkHeight];
        for (int z = 0; z < chunkHeight; z++) {
            for (int x = 0; x < chunkWidth; x++) {
                final long revision = chunkRevisions[
                    (minTileChunkZ + z) * chunksPerSide + minTileChunkX + x
                ];
                if (revision == Long.MIN_VALUE) {
                    return Long.MIN_VALUE;
                }
                revisions[z * chunkWidth + x] = revision;
            }
        }
        return ChunkPatchCodec.regionRevision(
            lod,
            new cn.net.rms.confluxmap.core.util.ChunkRegionSlice(
                regionX, regionZ,
                minRegionChunkX, minRegionChunkZ,
                minRegionChunkX + chunkWidth - 1,
                minRegionChunkZ + chunkHeight - 1
            ),
            revisions
        );
    }

    private void checkRegionSlice(
        final int minTileChunkX,
        final int minTileChunkZ,
        final int chunkWidth,
        final int chunkHeight
    ) {
        if (minTileChunkX < 0 || minTileChunkZ < 0 || chunkWidth <= 0 || chunkHeight <= 0
            || minTileChunkX > chunksPerSide - chunkWidth
            || minTileChunkZ > chunksPerSide - chunkHeight) {
            throw new IllegalArgumentException("chunk region slice lies outside correction tile");
        }
    }

    private static void setBit(final byte[] bits, final int index) {
        bits[index >>> 3] |= (byte) (1 << (index & 7));
    }

    private static void clearBit(final byte[] bits, final int index) {
        bits[index >>> 3] &= (byte) ~(1 << (index & 7));
    }
}
