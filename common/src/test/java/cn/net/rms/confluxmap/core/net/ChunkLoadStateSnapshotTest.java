package cn.net.rms.confluxmap.core.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ChunkLoadStateSnapshotTest {
    @Test
    void ignoresStaleSubscriptionsAndAppliesResetRemovalAndCompletion() {
        final ChunkLoadStateSnapshot snapshot = new ChunkLoadStateSnapshot();
        snapshot.begin(8, 0);

        assertFalse(snapshot.apply(new LoadStateDeltaS2C(
            7, true, true, List.of(new LoadStateDeltaS2C.Entry(1, 2, 31, ChunkLoadBand.ENTITY_TICKING))
        )));
        assertTrue(snapshot.apply(new LoadStateDeltaS2C(
            8, true, false, List.of(new LoadStateDeltaS2C.Entry(1, 2, 31, ChunkLoadBand.ENTITY_TICKING))
        )));
        assertEquals(31, snapshot.get(1, 2).orElseThrow().level());
        assertFalse(snapshot.complete());

        assertTrue(snapshot.apply(new LoadStateDeltaS2C(
            8,
            false,
            true,
            List.of(new LoadStateDeltaS2C.Entry(
                1, 2, Proto.LOAD_STATE_UNLOADED_LEVEL, ChunkLoadBand.UNLOADED
            ))
        )));
        assertTrue(snapshot.get(1, 2).isEmpty());
        assertTrue(snapshot.complete());
    }

    @Test
    void allowsRenderingSnapshotsWhileNetworkDeltasArrive() throws InterruptedException {
        final ChunkLoadStateSnapshot snapshot = new ChunkLoadStateSnapshot();
        snapshot.begin(8, 0);
        final List<LoadStateDeltaS2C.Entry> loadedEntries = IntStream.range(0, 4_096)
            .mapToObj(index -> new LoadStateDeltaS2C.Entry(
                index, 0, 31, ChunkLoadBand.ENTITY_TICKING
            ))
            .toList();
        final LoadStateDeltaS2C loaded = new LoadStateDeltaS2C(
            8, true, false, loadedEntries
        );
        final LoadStateDeltaS2C cleared = new LoadStateDeltaS2C(
            8, true, false, List.of()
        );
        final CountDownLatch writerStarted = new CountDownLatch(1);
        final AtomicBoolean stop = new AtomicBoolean();
        final AtomicBoolean writerDone = new AtomicBoolean();
        final Thread writer = new Thread(() -> {
            writerStarted.countDown();
            try {
                for (int attempt = 0; attempt < 5_000 && !stop.get(); attempt++) {
                    snapshot.apply(loaded);
                    snapshot.apply(cleared);
                }
            } finally {
                writerDone.set(true);
            }
        }, "load-state-network-writer");
        writer.start();
        writerStarted.await();

        try {
            do {
                snapshot.entries();
            } while (!writerDone.get());
        } finally {
            stop.set(true);
            writer.join();
        }
    }
}
