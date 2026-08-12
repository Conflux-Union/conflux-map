package cn.net.rms.confluxmap.core.task;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class DirtyChunkSetTest {
    @Test
    void unavailableChunksRemainDirtyUntilTheyBecomeCapturable() {
        final DirtyChunkSet chunks = new DirtyChunkSet();
        chunks.mark(1, 0);
        chunks.mark(2, 0);
        chunks.mark(3, 0);

        final List<long[]> first = chunks.drainNearestMatching(
            2, 0, 0, (chunkX, chunkZ) -> chunkX != 1
        );

        assertEquals(List.of(2L, 3L), first.stream().map(position -> position[0]).toList());
        assertEquals(1, chunks.size());

        final List<long[]> second = chunks.drainNearestMatching(
            1, 0, 0, (chunkX, chunkZ) -> true
        );

        assertEquals(1L, second.get(0)[0]);
        assertEquals(0, chunks.size());
    }

    @Test
    void eligibilityDoesNotChangeNearestFirstOrdering() {
        final DirtyChunkSet chunks = new DirtyChunkSet();
        chunks.mark(8, 0);
        chunks.mark(2, 0);
        chunks.mark(4, 0);

        final List<long[]> drained = chunks.drainNearestMatching(
            2, 0, 0, (chunkX, chunkZ) -> true
        );

        assertEquals(List.of(2L, 4L), drained.stream().map(position -> position[0]).toList());
        assertEquals(1, chunks.size());
    }
}
