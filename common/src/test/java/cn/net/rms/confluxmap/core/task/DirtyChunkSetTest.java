package cn.net.rms.confluxmap.core.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.task.DirtyChunkSet.ChunkReadiness;
import cn.net.rms.confluxmap.core.task.DirtyChunkSet.Readiness;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DirtyChunkSetTest {
    private static final ChunkReadiness ALL_READY = (x, z) -> Readiness.READY;

    @Test
    void markWithLoadedNeighborsCoversTheWholeSquareWhenEveryNeighborIsLoaded() {
        final DirtyChunkSet dirty = new DirtyChunkSet();

        dirty.markWithLoadedNeighbors(4, -7, (x, z) -> true);

        assertEquals(9, dirty.size());
        final Set<String> marked = drainAll(dirty, 4, -7);
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                assertTrue(marked.contains(key(4 + dx, -7 + dz)), "missing neighbour " + dx + "," + dz);
            }
        }
    }

    @Test
    void markWithLoadedNeighborsSkipsNeighborsTheClientHasNotReceived() {
        final DirtyChunkSet dirty = new DirtyChunkSet();
        // Only the west column exists yet, the frontier the chunk just arrived on does not.
        final Set<String> loaded = Set.of(key(-1, -1), key(-1, 0), key(-1, 1));

        dirty.markWithLoadedNeighbors(0, 0, (x, z) -> loaded.contains(key(x, z)));

        final Set<String> marked = drainAll(dirty, 0, 0);
        assertEquals(4, marked.size());
        assertTrue(marked.contains(key(0, 0)));
        assertTrue(marked.containsAll(loaded));
        assertFalse(marked.contains(key(1, 0)));
    }

    @Test
    void markWithLoadedNeighborsAlwaysMarksTheArrivingChunkItself() {
        final DirtyChunkSet dirty = new DirtyChunkSet();
        final Set<String> probed = new HashSet<>();

        dirty.markWithLoadedNeighbors(9, 9, (x, z) -> {
            probed.add(key(x, z));
            return false;
        });

        assertEquals(Set.of(key(9, 9)), drainAll(dirty, 9, 9));
        assertFalse(probed.contains(key(9, 9)), "the arriving chunk is loaded by definition");
        assertEquals(8, probed.size());
    }

    @Test
    void repeatedMarksOfTheSameNeighborhoodCoalesceIntoOneCaptureEach() {
        final DirtyChunkSet dirty = new DirtyChunkSet();

        dirty.markWithLoadedNeighbors(0, 0, (x, z) -> true);
        dirty.markWithLoadedNeighbors(1, 0, (x, z) -> true);

        assertEquals(12, dirty.size());
    }

    @Test
    void drainNearestTakesTheClosestChunksFirst() {
        final DirtyChunkSet dirty = new DirtyChunkSet();
        dirty.mark(10, 0);
        dirty.mark(1, 0);
        dirty.mark(5, 0);

        final List<long[]> batch = dirty.drainNearest(2, 0, 0, ALL_READY);

        assertEquals(2, batch.size());
        assertEquals(key(1, 0), keyOf(batch.get(0)));
        assertEquals(key(5, 0), keyOf(batch.get(1)));
        assertEquals(1, dirty.size());
    }

    @Test
    void unloadedChunksAreDroppedWithoutSpendingBudget() {
        final DirtyChunkSet dirty = new DirtyChunkSet();
        dirty.mark(1, 0);
        dirty.mark(2, 0);

        final List<long[]> batch = dirty.drainNearest(
            1, 0, 0, (x, z) -> x == 1 ? Readiness.MISSING : Readiness.READY
        );

        assertEquals(List.of(key(2, 0)), batch.stream().map(DirtyChunkSetTest::keyOf).toList());
        assertEquals(0, dirty.size(), "a missing chunk marks itself again when it arrives");
    }

    @Test
    void chunksWaitingForNeighborsLetReadyChunksThroughFirst() {
        final DirtyChunkSet dirty = new DirtyChunkSet();
        dirty.mark(1, 0);
        dirty.mark(2, 0);

        final List<long[]> batch = dirty.drainNearest(
            1, 0, 0, (x, z) -> x == 1 ? Readiness.WAITING : Readiness.READY
        );

        assertEquals(List.of(key(2, 0)), batch.stream().map(DirtyChunkSetTest::keyOf).toList());
        assertEquals(1, dirty.size());
        assertEquals(Set.of(key(1, 0)), drainAll(dirty, 0, 0));
    }

    @Test
    void waitingChunksTakeTheBudgetLeftOverAfterTheSampleableOnes() {
        final DirtyChunkSet dirty = new DirtyChunkSet();
        dirty.mark(1, 0);
        dirty.mark(2, 0);
        dirty.mark(3, 0);

        final List<long[]> batch = dirty.drainNearest(
            3, 0, 0, (x, z) -> x == 2 ? Readiness.WAITING : Readiness.READY
        );

        assertEquals(
            List.of(key(1, 0), key(3, 0), key(2, 0)),
            batch.stream().map(DirtyChunkSetTest::keyOf).toList(),
            "the held chunk is sampled last, but an idle budget must not leave it off the map"
        );
        assertEquals(0, dirty.size());
    }

    @Test
    void aNeighborhoodThatNeverCompletesIsSampledAnywayOnceTheHoldRunsOut() {
        final DirtyChunkSet dirty = new DirtyChunkSet();
        // The held chunk is nearer than the ready work behind it, so every drain fills its whole
        // budget after passing it over - the hold has to expire on its own to ever be sampled.
        dirty.mark(5, 0);
        final ChunkReadiness readiness = (x, z) -> x == 5 ? Readiness.WAITING : Readiness.READY;

        for (int drain = 1; drain < DirtyChunkSet.MAX_DEFERRALS; drain++) {
            dirty.mark(6, 0);
            dirty.mark(7, 0);
            assertEquals(
                List.of(key(6, 0), key(7, 0)),
                dirty.drainNearest(2, 0, 0, readiness).stream().map(DirtyChunkSetTest::keyOf).toList(),
                "released on drain " + drain
            );
            // Re-marking mid-hold must not restart the count, or the edge ring never draws.
            dirty.mark(5, 0);
        }
        dirty.mark(6, 0);
        dirty.mark(7, 0);

        final List<long[]> batch = dirty.drainNearest(2, 0, 0, readiness);

        assertEquals(
            List.of(key(5, 0), key(6, 0)),
            batch.stream().map(DirtyChunkSetTest::keyOf).toList()
        );
        assertEquals(Set.of(key(7, 0)), drainAll(dirty, 0, 0));
    }

    @Test
    void theHoldIsMeasuredInDrainsEvenWhenTheBudgetNeverReachesTheHeldChunk() {
        final DirtyChunkSet dirty = new DirtyChunkSet();
        dirty.mark(50, 0);
        final ChunkReadiness readiness = (x, z) -> x == 50 ? Readiness.WAITING : Readiness.READY;

        // A chunk arriving next to the player every tick keeps the budget spent long before the
        // scan reaches the far ring, so the hold must age on the drain count, not on inspection.
        for (int drain = 1; drain < DirtyChunkSet.MAX_DEFERRALS; drain++) {
            dirty.mark(1, 0);
            assertEquals(
                List.of(key(1, 0)),
                dirty.drainNearest(1, 0, 0, readiness).stream().map(DirtyChunkSetTest::keyOf).toList()
            );
        }

        assertEquals(
            List.of(key(50, 0)),
            dirty.drainNearest(1, 0, 0, readiness).stream().map(DirtyChunkSetTest::keyOf).toList()
        );
        assertEquals(0, dirty.size());
    }

    private static Set<String> drainAll(final DirtyChunkSet dirty, final int centerX, final int centerZ) {
        final Set<String> keys = new LinkedHashSet<>();
        for (final long[] chunkPos : dirty.drainNearest(Integer.MAX_VALUE, centerX, centerZ, ALL_READY)) {
            keys.add(keyOf(chunkPos));
        }
        return keys;
    }

    private static String keyOf(final long[] chunkPos) {
        return key((int) chunkPos[0], (int) chunkPos[1]);
    }

    private static String key(final int chunkX, final int chunkZ) {
        return chunkX + "," + chunkZ;
    }
}
