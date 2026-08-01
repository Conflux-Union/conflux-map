package cn.net.rms.confluxmap.paper;

import cn.net.rms.confluxmap.core.net.PatchCodec;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.net.SummaryCodec;
import cn.net.rms.confluxmap.server.PatchBuilder;
import cn.net.rms.confluxmap.server.SummaryView;
import cn.net.rms.confluxmap.server.SyncPerformanceMonitor;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/** Bounded reusable LOD-3/4 Paper scan with Fabric-compatible partial responses. */
final class PaperProgressiveTile implements AutoCloseable {
    @FunctionalInterface
    interface RegionReader {
        SummaryCodec.SampledRegion read(int regionX, int regionZ);
    }

    record Response(
        int mode,
        long revision,
        byte[] presence,
        byte[] body,
        SyncPerformanceMonitor.CumulativeWork work
    ) {
    }

    private record RegionStamp(int regionX, int regionZ, long sourceMcaMtime) {
    }

    private record ScanResult(
        SummaryCodec.SampledRegion region,
        RegionStamp stamp,
        int generation
    ) {
    }

    private record SummaryResult(
        PaperSampledSummaryTile summary,
        boolean fresh,
        int generation
    ) {
    }

    private static final byte[] EMPTY_PATCH = PatchCodec.encode(List.of());
    private static final int MAX_REVISION_VARIANTS = 8;
    private static final AtomicLong NEXT_WORK_ID = new AtomicLong();

    private final Path regionDirectory;
    private final int lod;
    private final int tileX;
    private final int tileZ;
    private final int regionsPerSide;
    private final RegionReader reader;
    private final Function<SummaryView, PatchBuilder.PreparedBaseline> baselineFactory;
    private final PatchBuilder patchBuilder;
    private final Executor worker;
    private final long workId = NEXT_WORK_ID.incrementAndGet();
    private final long startedNanos;
    private final AtomicLong ioNanos = new AtomicLong();
    private final AtomicLong computeNanos = new AtomicLong();
    private final List<SummaryCodec.SampledRegion> regions = new ArrayList<>();
    private final List<RegionStamp> stamps = new ArrayList<>();
    private final Map<Long, CompletableFuture<PatchBuilder.Result>> builds =
        new LinkedHashMap<>();

    private CompletableFuture<ScanResult> scan;
    private CompletableFuture<SummaryResult> finishing;
    private PaperSampledSummaryTile completeSummary;
    private int nextRegion;
    private int generation;
    private long lastRequestedAtNanos;
    private boolean closed;

    PaperProgressiveTile(
        final Path regionDirectory,
        final int lod,
        final int tileX,
        final int tileZ,
        final RegionReader reader,
        final Function<SummaryView, PatchBuilder.PreparedBaseline> baselineFactory,
        final PatchBuilder patchBuilder,
        final Executor worker,
        final long nowNanos
    ) {
        this.regionDirectory = regionDirectory;
        this.lod = lod;
        this.tileX = tileX;
        this.tileZ = tileZ;
        this.regionsPerSide = 1 << lod;
        this.reader = reader;
        this.baselineFactory = baselineFactory;
        this.patchBuilder = patchBuilder;
        this.worker = worker;
        this.startedNanos = nowNanos;
        this.lastRequestedAtNanos = nowNanos;
        final long firstRegionX = (long) tileX * regionsPerSide;
        final long firstRegionZ = (long) tileZ * regionsPerSide;
        if (firstRegionX < Integer.MIN_VALUE
            || firstRegionX + regionsPerSide - 1L > Integer.MAX_VALUE
            || firstRegionZ < Integer.MIN_VALUE
            || firstRegionZ + regionsPerSide - 1L > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("progressive tile is outside the region range");
        }
    }

    synchronized long lastRequestedAtNanos() {
        return lastRequestedAtNanos;
    }

    synchronized boolean complete() {
        return completeSummary != null;
    }

    synchronized boolean covers(final int regionX, final int regionZ) {
        final long baseX = (long) tileX * regionsPerSide;
        final long baseZ = (long) tileZ * regionsPerSide;
        return regionX >= baseX && regionX < baseX + regionsPerSide
            && regionZ >= baseZ && regionZ < baseZ + regionsPerSide;
    }

    synchronized void tick() {
        if (closed) {
            return;
        }
        finishScanIfReady();
        finishSummaryIfReady();
        if (scan == null && finishing == null && completeSummary == null) {
            if (nextRegion < regionsPerSide * regionsPerSide) {
                scheduleRegion();
            } else {
                scheduleSummary();
            }
        }
    }

    synchronized Response response(final long sinceRevision, final long nowNanos) {
        lastRequestedAtNanos = nowNanos;
        finishScanIfReady();
        finishSummaryIfReady();
        if (completeSummary == null) {
            return partial();
        }
        builds.entrySet().removeIf(entry -> entry.getValue().isCancelled());
        CompletableFuture<PatchBuilder.Result> build = builds.get(sinceRevision);
        if (build == null) {
            if (builds.size() >= MAX_REVISION_VARIANTS) {
                return partial();
            }
            final PaperSampledSummaryTile summary = completeSummary;
            try {
                build = CompletableFuture.supplyAsync(
                    () -> {
                        final long started = System.nanoTime();
                        try {
                            return patchBuilder.buildPrepared(
                                summary, sinceRevision, baselineFactory.apply(summary)
                            );
                        } finally {
                            computeNanos.addAndGet(
                                Math.max(0L, System.nanoTime() - started)
                            );
                        }
                    },
                    worker
                );
                builds.put(sinceRevision, build);
            } catch (final RejectedExecutionException e) {
                return partial();
            }
        }
        if (!build.isDone()) {
            return partial();
        }
        try {
            final PatchBuilder.Result result = build.join();
            return new Response(
                result.mode(), result.revision(), result.presence(), result.body(), workSnapshot()
            );
        } catch (final CompletionException | java.util.concurrent.CancellationException e) {
            builds.remove(sinceRevision);
            return partial();
        }
    }

    synchronized void invalidate() {
        generation++;
        nextRegion = 0;
        regions.clear();
        stamps.clear();
        completeSummary = null;
        if (scan != null) {
            scan.cancel(false);
            scan = null;
        }
        if (finishing != null) {
            finishing.cancel(false);
            finishing = null;
        }
        builds.values().forEach(future -> future.cancel(false));
        builds.clear();
    }

    @Override
    public synchronized void close() {
        closed = true;
        invalidate();
    }

    private void scheduleRegion() {
        final int target = nextRegion;
        final int regionX = tileX * regionsPerSide + target % regionsPerSide;
        final int regionZ = tileZ * regionsPerSide + target / regionsPerSide;
        final int targetGeneration = generation;
        try {
            scan = CompletableFuture.supplyAsync(() -> {
                final long started = System.nanoTime();
                try {
                    SummaryCodec.SampledRegion region = reader.read(regionX, regionZ);
                    if (region == null) {
                        region = emptyRegion(regionX, regionZ, lod);
                    }
                    return new ScanResult(
                        region,
                        new RegionStamp(regionX, regionZ, region.sourceMcaMtimeMs()),
                        targetGeneration
                    );
                } finally {
                    ioNanos.addAndGet(Math.max(0L, System.nanoTime() - started));
                }
            }, worker);
        } catch (final RejectedExecutionException ignored) {
            scan = null;
        }
    }

    private void finishScanIfReady() {
        if (scan == null || !scan.isDone()) {
            return;
        }
        final CompletableFuture<ScanResult> completed = scan;
        scan = null;
        try {
            final ScanResult result = completed.join();
            if (result.generation() != generation) {
                return;
            }
            regions.add(result.region());
            stamps.add(result.stamp());
            nextRegion++;
        } catch (final CompletionException | java.util.concurrent.CancellationException ignored) {
            // Retry the same region on a later tick.
        }
    }

    private void scheduleSummary() {
        final int targetGeneration = generation;
        final List<SummaryCodec.SampledRegion> regionSnapshot = List.copyOf(regions);
        final List<RegionStamp> stampSnapshot = List.copyOf(stamps);
        try {
            finishing = CompletableFuture.supplyAsync(() -> {
                final long started = System.nanoTime();
                try {
                    for (final RegionStamp stamp : stampSnapshot) {
                        final long current = PaperAnvilReader.summaryRegionMtime(
                            regionDirectory, stamp.regionX(), stamp.regionZ()
                        );
                        if (current != stamp.sourceMcaMtime()) {
                            return new SummaryResult(null, false, targetGeneration);
                        }
                    }
                    return new SummaryResult(
                        new PaperSampledSummaryTile(lod, tileX, tileZ, regionSnapshot),
                        true,
                        targetGeneration
                    );
                } finally {
                    computeNanos.addAndGet(Math.max(0L, System.nanoTime() - started));
                }
            }, worker);
        } catch (final RejectedExecutionException ignored) {
            finishing = null;
        }
    }

    private void finishSummaryIfReady() {
        if (finishing == null || !finishing.isDone()) {
            return;
        }
        final CompletableFuture<SummaryResult> completed = finishing;
        finishing = null;
        try {
            final SummaryResult result = completed.join();
            if (result.generation() != generation) {
                return;
            }
            if (!result.fresh()) {
                invalidate();
                return;
            }
            completeSummary = result.summary();
        } catch (final CompletionException | java.util.concurrent.CancellationException ignored) {
            invalidate();
        }
    }

    private Response partial() {
        return new Response(
            Proto.PATCH_MODE_PARTIAL,
            0L,
            new byte[Proto.PATCH_PRESENCE_BYTES],
            EMPTY_PATCH.clone(),
            workSnapshot()
        );
    }

    private SyncPerformanceMonitor.CumulativeWork workSnapshot() {
        return new SyncPerformanceMonitor.CumulativeWork(
            workId, startedNanos, ioNanos.get(), computeNanos.get()
        );
    }

    private static SummaryCodec.SampledRegion emptyRegion(
        final int regionX,
        final int regionZ,
        final int lod
    ) {
        final int stride = 1 << lod;
        final SummaryCodec.SampledChunk[] chunks =
            new SummaryCodec.SampledChunk[SummaryCodec.CHUNKS];
        Arrays.fill(chunks, SummaryCodec.SampledChunk.empty(stride));
        return new SummaryCodec.SampledRegion(regionX, regionZ, 0L, stride, chunks);
    }
}
