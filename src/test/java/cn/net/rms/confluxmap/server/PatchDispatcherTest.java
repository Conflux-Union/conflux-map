package cn.net.rms.confluxmap.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.net.MapPatchS2C;
import cn.net.rms.confluxmap.core.net.Message;
import cn.net.rms.confluxmap.core.net.MsgCodec;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.net.ProtoException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PatchDispatcherTest {
    private static final long T0 = 1_000_000_000L;

    @Test
    void budgetExhaustionQueuesTheTailInsteadOfDroppingIt() throws ProtoException {
        final int patchBytes = MsgCodec.encode(patch(job(0, 0), 1_200)).length;
        final PlayerBudget budget = new PlayerBudget(2 * patchBytes + 10, 0);
        final PatchDispatcher dispatcher = new PatchDispatcher(budget, 16);
        // The token bucket anchors its refill clock to System.nanoTime() at construction, so the
        // simulated timeline must start after that instant.
        final long t0 = System.nanoTime();
        assertEquals(0, dispatcher.submit(List.of(job(0, 0), job(1, 0), job(2, 0))));

        final List<MapPatchS2C> sent = new ArrayList<>();
        dispatcher.drain(t0, j -> patch(j, 1_200), collect(sent));
        assertEquals(2, sent.size(), "bucket holds exactly two patches");
        assertEquals(1, dispatcher.queued(), "third tile must stay queued, not be dropped");

        dispatcher.drain(t0, j -> patch(j, 1_200), collect(sent));
        assertEquals(2, sent.size(), "no tokens yet, nothing more may be sent");

        dispatcher.drain(t0 + 1_000_000_000L, j -> patch(j, 1_200), collect(sent));
        assertEquals(3, sent.size(), "refilled bucket delivers the queued tail");
        assertEquals(0, dispatcher.queued());
        assertEquals(List.of(0, 1, 2), sent.stream().map(MapPatchS2C::tileX).toList(), "strict FIFO order");
    }

    @Test
    void resubmittedTileDeduplicatesInPlaceWithNewestRevision() throws ProtoException {
        final PatchDispatcher dispatcher = new PatchDispatcher(new PlayerBudget(1 << 20, 0), 16);
        dispatcher.submit(List.of(new PatchDispatcher.TileJob(1, 0, 0, 5, 5, 0L)));
        dispatcher.submit(List.of(new PatchDispatcher.TileJob(2, 0, 0, 5, 5, 7L), job(1, 0)));
        assertEquals(2, dispatcher.queued(), "same tile must not occupy two queue slots");

        final List<PatchDispatcher.TileJob> built = new ArrayList<>();
        dispatcher.drain(T0, j -> {
            built.add(j);
            return patch(j, 8);
        }, message -> { });
        assertEquals(2, built.size());
        assertEquals(7L, built.get(0).sinceRevision(), "resubmit must replace the stale job in place");
        assertEquals(5, built.get(0).tileX(), "dedup keeps the original queue position");
    }

    @Test
    void overflowRejectsOnlyTheExcess() {
        final PatchDispatcher dispatcher = new PatchDispatcher(new PlayerBudget(1 << 20, 0), 2);
        assertEquals(1, dispatcher.submit(List.of(job(0, 0), job(1, 0), job(2, 0))));
        assertEquals(2, dispatcher.queued());
    }

    @Test
    void unencodablePatchIsDroppedInsteadOfWedgingTheQueue() {
        final PatchDispatcher dispatcher = new PatchDispatcher(new PlayerBudget(1 << 20, 0), 16);
        dispatcher.submit(List.of(job(0, 0), job(1, 0)));

        final List<MapPatchS2C> sent = new ArrayList<>();
        dispatcher.drain(T0, j -> j.tileX() == 0
            ? new MapPatchS2C(j.reqId(), j.dimIndex(), j.lod(), j.tileX(), j.tileZ(),
                Proto.PATCH_MODE_ABSOLUTE, 1L, new byte[1], new byte[0])
            : patch(j, 8), collect(sent));
        assertEquals(1, sent.size(), "the healthy job behind the broken one must still be sent");
        assertEquals(1, sent.get(0).tileX());
        assertEquals(0, dispatcher.queued());
    }

    private static PatchDispatcher.TileJob job(final int tileX, final int tileZ) {
        return new PatchDispatcher.TileJob(1, 0, 0, tileX, tileZ, 0L);
    }

    private static MapPatchS2C patch(final PatchDispatcher.TileJob job, final int bodyBytes) {
        return new MapPatchS2C(
            job.reqId(), job.dimIndex(), job.lod(), job.tileX(), job.tileZ(),
            Proto.PATCH_MODE_ABSOLUTE, 1L, new byte[Proto.PATCH_PRESENCE_BYTES], new byte[bodyBytes]
        );
    }

    private static java.util.function.Consumer<Message> collect(final List<MapPatchS2C> sent) {
        return message -> sent.add((MapPatchS2C) message);
    }
}
