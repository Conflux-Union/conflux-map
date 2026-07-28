package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.core.net.MapPatchS2C;
import cn.net.rms.confluxmap.core.net.Message;
import cn.net.rms.confluxmap.core.net.MsgCodec;
import cn.net.rms.confluxmap.core.net.ProtoException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Per-player patch delivery queue. Tiles from MAP_VIEW_REQ are enqueued in arrival order and
 * drained strictly FIFO as the player's byte budget refills, instead of dropping the tail of a
 * request the moment the budget runs out mid-serve. A tile requested again while still queued is
 * deduplicated in place (newest request wins, keeping its queue position). Only tiles beyond
 * {@code maxQueued} are rejected; everything accepted is eventually sent or dropped with the
 * player.
 *
 * <p>Thread-safe: requests enqueue from the network thread while the server tick drains.
 */
public final class PatchDispatcher {
    /** One tile's worth of pending patch work; identity is (dimIndex, lod, tileX, tileZ). */
    public record TileJob(
        int reqId,
        int dimIndex,
        int lod,
        int tileX,
        int tileZ,
        long sinceRevision,
        long receivedAtNanos,
        int requestBytes
    ) {
        public TileJob(
            final int reqId,
            final int dimIndex,
            final int lod,
            final int tileX,
            final int tileZ,
            final long sinceRevision
        ) {
            this(reqId, dimIndex, lod, tileX, tileZ, sinceRevision, 0L, 0);
        }
    }

    @FunctionalInterface
    public interface PatchSource {
        /** Builds the patch for one job; implementations return an UNAVAILABLE-mode patch on failure. */
        MapPatchS2C build(TileJob job);
    }

    /** A patch plus the server work measurements gathered while preparing it. */
    public record BuiltPatch(
        MapPatchS2C patch,
        SyncPerformanceMonitor.DirectWork directWork,
        SyncPerformanceMonitor.CumulativeWork cumulativeWork
    ) {
        public BuiltPatch {
            if (patch == null) {
                throw new IllegalArgumentException("patch cannot be null");
            }
            directWork = directWork == null ? SyncPerformanceMonitor.DirectWork.NONE : directWork;
            cumulativeWork = cumulativeWork == null
                ? SyncPerformanceMonitor.CumulativeWork.NONE
                : cumulativeWork;
        }

        static BuiltPatch untimed(final MapPatchS2C patch) {
            return new BuiltPatch(
                patch,
                SyncPerformanceMonitor.DirectWork.NONE,
                SyncPerformanceMonitor.CumulativeWork.NONE
            );
        }
    }

    @FunctionalInterface
    public interface TimedPatchSource {
        BuiltPatch build(TileJob job);
    }

    @FunctionalInterface
    public interface PatchSender {
        void send(Message message, byte[] encoded);
    }

    @FunctionalInterface
    public interface DeliveryObserver {
        DeliveryObserver NONE = delivery -> { };

        void delivered(SyncPerformanceMonitor.Delivery delivery);
    }

    private record JobKey(int dimIndex, int lod, int tileX, int tileZ) {
    }

    private static final class QueuedJob {
        TileJob job;
        BuiltPatch built;
        byte[] encoded;
        long buildStartedNanos;
        long encodeNanos;
    }

    private final PlayerBudget budget;
    private final int maxQueued;
    private final LongSupplier nanoClock;
    private final DeliveryObserver deliveryObserver;
    private final LinkedHashMap<JobKey, QueuedJob> queue = new LinkedHashMap<>();

    public PatchDispatcher(final PlayerBudget budget, final int maxQueued) {
        this(budget, maxQueued, System::nanoTime, DeliveryObserver.NONE);
    }

    PatchDispatcher(
        final PlayerBudget budget,
        final int maxQueued,
        final LongSupplier nanoClock,
        final DeliveryObserver deliveryObserver
    ) {
        this.budget = budget;
        this.maxQueued = Math.max(1, maxQueued);
        this.nanoClock = nanoClock;
        this.deliveryObserver = deliveryObserver == null ? DeliveryObserver.NONE : deliveryObserver;
    }

    public PlayerBudget budget() {
        return budget;
    }

    /** Enqueues jobs in order with per-tile dedup; returns how many were rejected for lack of room. */
    public synchronized int submit(final List<TileJob> jobs) {
        int overflow = 0;
        for (final TileJob job : jobs) {
            final JobKey key = new JobKey(job.dimIndex(), job.lod(), job.tileX(), job.tileZ());
            final QueuedJob existing = queue.get(key);
            if (existing != null) {
                existing.job = job;
                existing.built = null;
                existing.encoded = null;
                existing.buildStartedNanos = 0L;
                existing.encodeNanos = 0L;
                continue;
            }
            if (queue.size() >= maxQueued) {
                overflow++;
                continue;
            }
            final QueuedJob queued = new QueuedJob();
            queued.job = job;
            queue.put(key, queued);
        }
        return overflow;
    }

    public synchronized int queued() {
        return queue.size();
    }

    public synchronized void clear() {
        queue.clear();
    }

    /**
     * Sends queued patches while the byte budget allows. When the head patch is unaffordable it
     * stays queued (with its built patch cached) and the drain stops - strict FIFO, so one large
     * patch cannot be starved by cheaper tiles behind it. Call again after the bucket refills.
     */
    public synchronized void drain(final long nowNanos, final PatchSource source, final Consumer<Message> sender) {
        drainTimed(
            nowNanos,
            job -> BuiltPatch.untimed(source.build(job)),
            (message, encoded) -> sender.accept(message)
        );
    }

    /** Identical FIFO delivery with server work and wire measurements attached to each sent patch. */
    public synchronized void drainTimed(
        final long nowNanos,
        final TimedPatchSource source,
        final PatchSender sender
    ) {
        final Iterator<QueuedJob> heads = queue.values().iterator();
        while (heads.hasNext()) {
            final QueuedJob head = heads.next();
            if (head.built == null) {
                head.buildStartedNanos = nanoClock.getAsLong();
                head.built = source.build(head.job);
                final long encodeStartedNanos = nanoClock.getAsLong();
                try {
                    head.encoded = MsgCodec.encode(head.built.patch());
                } catch (final ProtoException e) {
                    // An unencodable patch is a server-side bug; drop the job rather than wedge the queue.
                    heads.remove();
                    continue;
                }
                head.encodeNanos = Math.max(0L, nanoClock.getAsLong() - encodeStartedNanos);
            }
            if (!budget.allowBytes(head.encoded.length, nowNanos)) {
                return;
            }
            sender.send(head.built.patch(), head.encoded);
            deliveryObserver.delivered(new SyncPerformanceMonitor.Delivery(
                head.job.dimIndex(),
                head.job.lod(),
                head.job.tileX(),
                head.job.tileZ(),
                head.job.receivedAtNanos(),
                head.job.requestBytes(),
                head.built.patch().mode(),
                head.encoded.length,
                Math.max(0L, head.buildStartedNanos - head.job.receivedAtNanos()),
                head.encodeNanos,
                nanoClock.getAsLong(),
                head.built.directWork(),
                head.built.cumulativeWork()
            ));
            heads.remove();
        }
    }
}
