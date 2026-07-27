package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.core.net.PatchCodec;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.net.SummaryCodec;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.LongSupplier;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.ChunkPos;

/** One reusable, freshness-checked progressive correction scan for an LOD-3/4 tile. */
final class ProgressiveRegionPatch {
    static final int PUBLISH_CHUNK_INTERVAL = 2_048;
    static final long FINAL_FRESHNESS_NANOS = 2_000_000_000L;
    private static final int MAX_REVISION_VARIANTS = 8;
    private static final byte[] EMPTY_PATCH_BODY = PatchCodec.encode(java.util.List.of());

    static byte[] emptyPatchBody() {
        return EMPTY_PATCH_BODY.clone();
    }

    @FunctionalInterface
    interface BaselineFactory {
        PatchBuilder.PreparedBaseline prepare(SummaryView summary);
    }

    @FunctionalInterface
    interface ChunkNbtReader {
        NbtCompound read(ChunkPos pos) throws IOException;
    }

    record Response(int mode, long revision, byte[] presence, byte[] body) {
    }

    private enum State {
        SCANNING,
        VALIDATING,
        COMPLETE
    }

    private static final class BuildSlot {
        long generation = Long.MIN_VALUE;
        boolean building;
        PatchBuilder.Result result;
    }

    private final String dimension;
    private final Path worldRoot;
    private final SummaryDiskCache disk;
    private final LiveChunkSummaryTracker liveChunks;
    private final ChunkSummarizer summarizer;
    private final PatchBuilder patchBuilder;
    private final Executor worker;
    private final BaselineFactory baselineFactory;
    private final ChunkNbtReader nbtReader;
    private final int lod;
    private final int tileX;
    private final int tileZ;
    private final int regionsPerSide;
    private final int baseRegionX;
    private final int baseRegionZ;
    private final Map<Long, BuildSlot> builds = new LinkedHashMap<>();

    private ProgressivePatchTask scanner;
    private RegionSource source;
    private ProgressiveSourceStamps stamps;
    private State state;
    private SummaryView published;
    private long generation;
    private int lastPublishedChunks;
    private long lastValidatedAtNanos;
    private long lastRequestedAtNanos;
    private PatchBuilder.PreparedBaseline baseline;
    private boolean baselineReady;
    private boolean closed;

    ProgressiveRegionPatch(
        final String dimension,
        final Path worldRoot,
        final SummaryDiskCache disk,
        final LiveChunkSummaryTracker liveChunks,
        final ChunkSummarizer summarizer,
        final PatchBuilder patchBuilder,
        final Executor worker,
        final int lod,
        final int tileX,
        final int tileZ,
        final BaselineFactory baselineFactory,
        final ChunkNbtReader nbtReader,
        final long nowNanos
    ) {
        this.dimension = dimension;
        this.worldRoot = worldRoot;
        this.disk = disk;
        this.liveChunks = liveChunks;
        this.summarizer = summarizer;
        this.patchBuilder = patchBuilder;
        this.worker = worker;
        this.baselineFactory = baselineFactory;
        this.nbtReader = nbtReader;
        this.lod = lod;
        this.tileX = tileX;
        this.tileZ = tileZ;
        this.regionsPerSide = 1 << lod;
        final long firstRegionX = (long) tileX * regionsPerSide;
        final long firstRegionZ = (long) tileZ * regionsPerSide;
        final long lastRegionX = firstRegionX + regionsPerSide - 1L;
        final long lastRegionZ = firstRegionZ + regionsPerSide - 1L;
        if (firstRegionX < Integer.MIN_VALUE || lastRegionX > Integer.MAX_VALUE
            || firstRegionZ < Integer.MIN_VALUE || lastRegionZ > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("requested tile is outside the region coordinate range");
        }
        this.baseRegionX = (int) firstRegionX;
        this.baseRegionZ = (int) firstRegionZ;
        this.lastRequestedAtNanos = nowNanos;
        restartScan();
        scheduleBaseline();
    }

    synchronized long lastRequestedAtNanos() {
        return lastRequestedAtNanos;
    }

    synchronized boolean complete() {
        return state == State.COMPLETE;
    }

    /** Main server thread: advances either chunk reads or source-version validation. */
    synchronized void tick(
        final int maxChunksOrRegions,
        final long maxNanos,
        final long nowNanos,
        final LongSupplier nanoClock
    ) {
        if (closed || maxChunksOrRegions <= 0 || maxNanos <= 0L) {
            return;
        }
        if (state == State.SCANNING) {
            scanner.advance(source, maxChunksOrRegions, maxNanos, nanoClock);
            if (scanner.processedChunks() - lastPublishedChunks >= PUBLISH_CHUNK_INTERVAL) {
                publish();
            }
            if (!scanner.complete()) {
                return;
            }
            if (!stamps.stableScan()) {
                restartScan();
                return;
            }
            publish();
            stamps.restartValidation();
            state = State.VALIDATING;
            return;
        }

        final ProgressiveSourceStamps.Validation validation = stamps.validate(
            this::currentMtime,
            this::currentLiveEpoch,
            maxChunksOrRegions,
            maxNanos,
            nanoClock
        );
        if (validation == ProgressiveSourceStamps.Validation.STALE) {
            restartScan();
        } else if (validation == ProgressiveSourceStamps.Validation.FRESH) {
            lastValidatedAtNanos = nowNanos;
            if (state != State.COMPLETE) {
                state = State.COMPLETE;
                publish();
            }
        }
    }

    /** Any request thread: returns the newest immutable build and schedules a fresher one if needed. */
    synchronized Response response(final long sinceRevision, final long nowNanos) {
        lastRequestedAtNanos = nowNanos;
        trimBuildVariants(sinceRevision);
        BuildSlot slot = builds.get(sinceRevision);
        if (slot == null && builds.size() >= MAX_REVISION_VARIANTS) {
            return new Response(Proto.PATCH_MODE_PARTIAL, 0L, published.presence(), EMPTY_PATCH_BODY);
        }
        if (slot == null) {
            slot = new BuildSlot();
            builds.put(sinceRevision, slot);
        }
        if (baselineReady && slot.generation != generation && !slot.building) {
            scheduleBuild(sinceRevision, slot, generation, published, baseline);
        }
        final boolean finalFresh = state == State.COMPLETE
            && nowNanos - lastValidatedAtNanos <= FINAL_FRESHNESS_NANOS;
        if (slot.generation == generation && slot.result != null) {
            final PatchBuilder.Result result = slot.result;
            if (finalFresh) {
                return new Response(result.mode(), result.revision(), result.presence(), result.body());
            }
            return new Response(
                Proto.PATCH_MODE_PARTIAL,
                0L,
                result.presence(),
                result.recordCount() == 0 ? EMPTY_PATCH_BODY : result.body()
            );
        }
        return new Response(Proto.PATCH_MODE_PARTIAL, 0L, published.presence(), EMPTY_PATCH_BODY);
    }

    synchronized void close() {
        closed = true;
        builds.clear();
    }

    private void scheduleBaseline() {
        final SummaryView origin = published;
        try {
            worker.execute(() -> {
                synchronized (ProgressiveRegionPatch.this) {
                    if (closed) {
                        return;
                    }
                }
                PatchBuilder.PreparedBaseline prepared;
                try {
                    prepared = baselineFactory.prepare(origin);
                } catch (final RuntimeException e) {
                    ConfluxMapMod.LOGGER.warn(
                        "companion: progressive baseline failed for tile {},{} lod {} ({})",
                        tileX, tileZ, lod, e.getMessage()
                    );
                    prepared = PatchBuilder.PreparedBaseline.absoluteOnly();
                }
                if (prepared == null) {
                    prepared = PatchBuilder.PreparedBaseline.absoluteOnly();
                }
                synchronized (ProgressiveRegionPatch.this) {
                    if (!closed) {
                        baseline = prepared;
                        baselineReady = true;
                    }
                }
            });
        } catch (final RejectedExecutionException ignored) {
            baseline = PatchBuilder.PreparedBaseline.absoluteOnly();
            baselineReady = true;
        }
    }

    private void scheduleBuild(
        final long sinceRevision,
        final BuildSlot slot,
        final long targetGeneration,
        final SummaryView snapshot,
        final PatchBuilder.PreparedBaseline prepared
    ) {
        slot.building = true;
        try {
            worker.execute(() -> {
                synchronized (ProgressiveRegionPatch.this) {
                    if (closed || generation != targetGeneration || builds.get(sinceRevision) != slot) {
                        slot.building = false;
                        return;
                    }
                }
                PatchBuilder.Result result = null;
                try {
                    result = patchBuilder.buildPrepared(snapshot, sinceRevision, prepared);
                } catch (final IllegalArgumentException e) {
                    ConfluxMapMod.LOGGER.warn(
                        "companion: progressive patch too large for tile {},{} lod {} ({})",
                        tileX, tileZ, lod, e.getMessage()
                    );
                }
                synchronized (ProgressiveRegionPatch.this) {
                    slot.building = false;
                    if (!closed && result != null
                        && generation == targetGeneration && builds.get(sinceRevision) == slot) {
                        slot.generation = targetGeneration;
                        slot.result = result;
                    }
                }
            });
        } catch (final RejectedExecutionException ignored) {
            slot.building = false;
        }
    }

    private void publish() {
        published = scanner.snapshot();
        generation++;
        lastPublishedChunks = scanner.processedChunks();
        builds.clear();
    }

    private void restartScan() {
        scanner = new ProgressivePatchTask(lod, tileX, tileZ);
        stamps = new ProgressiveSourceStamps(regionsPerSide * regionsPerSide);
        source = new RegionSource();
        state = State.SCANNING;
        lastValidatedAtNanos = 0L;
        published = scanner.snapshot();
        generation++;
        lastPublishedChunks = 0;
        builds.clear();
    }

    private void trimBuildVariants(final long keepRevision) {
        if (builds.size() <= MAX_REVISION_VARIANTS) {
            return;
        }
        final Iterator<Map.Entry<Long, BuildSlot>> entries = builds.entrySet().iterator();
        while (builds.size() > MAX_REVISION_VARIANTS && entries.hasNext()) {
            final Map.Entry<Long, BuildSlot> entry = entries.next();
            if (entry.getKey() != keepRevision && !entry.getValue().building) {
                entries.remove();
            }
        }
    }

    private long currentMtime(final int regionIndex) {
        return RegionStoragePaths.mcaMtimeMs(
            worldRoot,
            dimension,
            baseRegionX + regionIndex % regionsPerSide,
            baseRegionZ + regionIndex / regionsPerSide
        );
    }

    private long currentLiveEpoch(final int regionIndex) {
        return liveChunks.regionEpoch(
            dimension,
            baseRegionX + regionIndex % regionsPerSide,
            baseRegionZ + regionIndex / regionsPerSide
        );
    }

    /** Region-major source that decodes a current cache once, then fills only missing slots from NBT. */
    private final class RegionSource implements ProgressivePatchTask.ChunkSource {
        private int currentRegionIndex = -1;
        private int currentRegionX;
        private int currentRegionZ;
        private long mtimeBefore;
        private long liveEpochBefore;
        private SummaryCodec.Region cached;

        @Override
        public SummaryCodec.Chunk load(final int chunkX, final int chunkZ) {
            final int regionX = Math.floorDiv(chunkX, 16);
            final int regionZ = Math.floorDiv(chunkZ, 16);
            final int regionIndex = (regionZ - baseRegionZ) * regionsPerSide + regionX - baseRegionX;
            final int localX = Math.floorMod(chunkX, 16);
            final int localZ = Math.floorMod(chunkZ, 16);
            final int chunkIndex = localZ * 16 + localX;
            if (regionIndex != currentRegionIndex) {
                startRegion(regionIndex, regionX, regionZ);
            }
            SummaryCodec.Chunk summary = liveChunks.get(dimension, chunkX, chunkZ);
            if (summary == null && cached != null && cached.chunks()[chunkIndex].generated()) {
                summary = cached.chunks()[chunkIndex];
            } else if (summary == null) {
                try {
                    final NbtCompound nbt = nbtReader.read(new ChunkPos(chunkX, chunkZ));
                    summary = nbt == null ? SummaryCodec.Chunk.empty() : summarizer.summarize(nbt);
                } catch (final IOException | RuntimeException ignored) {
                    summary = SummaryCodec.Chunk.empty();
                }
            }
            if (chunkIndex == SummaryCodec.CHUNKS - 1) {
                finishRegion();
            }
            return summary;
        }

        private void startRegion(final int regionIndex, final int regionX, final int regionZ) {
            currentRegionIndex = regionIndex;
            currentRegionX = regionX;
            currentRegionZ = regionZ;
            mtimeBefore = RegionStoragePaths.mcaMtimeMs(worldRoot, dimension, regionX, regionZ);
            liveEpochBefore = liveChunks.regionEpoch(dimension, regionX, regionZ);
            cached = disk.loadCurrent(dimension, regionX, regionZ, mtimeBefore);
        }

        private void finishRegion() {
            final long mtimeAfter = RegionStoragePaths.mcaMtimeMs(
                worldRoot, dimension, currentRegionX, currentRegionZ
            );
            final long liveEpochAfter = liveChunks.regionEpoch(dimension, currentRegionX, currentRegionZ);
            stamps.record(
                currentRegionIndex, mtimeBefore, mtimeAfter, liveEpochBefore, liveEpochAfter
            );
            // Do not encode/write a newly scanned region on the server tick. Existing current
            // summaries are reused above; cold scans remain task-local so the 4 ms slice is not
            // defeated by optional cache persistence.
        }
    }
}
