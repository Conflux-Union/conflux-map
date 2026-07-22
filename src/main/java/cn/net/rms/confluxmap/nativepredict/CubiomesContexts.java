package cn.net.rms.confluxmap.nativepredict;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-thread cache of {@link CubiomesContext} instances keyed by (mcVersion, seed, dim, flags).
 * Each
 * worker thread gets its own {@link HashMap} (via {@link ThreadLocal}), which is what actually
 * satisfies {@link CubiomesContext}'s "one thread only" contract - a context handed out here
 * never leaves the thread that asked for it.
 *
 * <p>{@link #bumpEpoch()} invalidates every cached context on every thread without reaching into
 * other threads' maps: it just advances a shared {@link AtomicLong}, and each thread lazily
 * notices its cached entry is stale (tagged with an older epoch) the next time that thread asks
 * for it, closing the stale context and creating a fresh one in its place. Call this whenever a
 * (mcVersion, seed, dim, flags) key could no longer be trusted to mean the same generator state
 * (e.g. on world/session change).
 */
public final class CubiomesContexts {
    private record Key(int mcVersion, long seed, int dim, int flags) {
    }

    private record Entry(CubiomesContext context, long epoch) {
    }

    private static final AtomicLong EPOCH = new AtomicLong();
    private static final ThreadLocal<Map<Key, Entry>> PER_THREAD = ThreadLocal.withInitial(HashMap::new);

    private CubiomesContexts() {
    }

    /** Invalidates every cached context on every thread (lazily - see class javadoc). */
    public static void bumpEpoch() {
        EPOCH.incrementAndGet();
    }

    /**
     * Returns this thread's context for {@code (mcVersion, seed, dim, flags)}, creating (or
     * recreating, if {@link #bumpEpoch()} advanced since it was cached) one as needed. Returns
     * {@code null} if cubiomes rejects the parameters (see {@link CubiomesNative#cfxCreate}); a
     * rejected combination is never cached, so every call retries it.
     */
    public static CubiomesContext get(final int mcVersion, final long seed, final int dim, final int flags) {
        final Map<Key, Entry> cache = PER_THREAD.get();
        final Key key = new Key(mcVersion, seed, dim, flags);
        final long currentEpoch = EPOCH.get();

        final Entry existing = cache.get(key);
        if (existing != null) {
            if (existing.epoch() == currentEpoch) {
                return existing.context();
            }
            existing.context().close();
        }

        final CubiomesContext created = CubiomesContext.create(mcVersion, seed, dim, flags);
        if (created == null) {
            cache.remove(key);
            return null;
        }
        cache.put(key, new Entry(created, currentEpoch));
        return created;
    }

    /** Closes and forgets every context cached on the calling thread. Call on thread shutdown. */
    public static void closeAllOnThisThread() {
        final Map<Key, Entry> cache = PER_THREAD.get();
        for (final Entry entry : cache.values()) {
            entry.context().close();
        }
        cache.clear();
    }
}
