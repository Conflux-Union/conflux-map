package cn.net.rms.confluxmap.core.loadstate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ChunkScreenRectTest {
    @Test
    void adjacentServerChunksShareTheExactMapBoundary() {
        final ChunkScreenRect first = ChunkScreenRect.forChunk(2, -3, 40.0, -40.0, 800, 600, 2.0);
        final ChunkScreenRect east = ChunkScreenRect.forChunk(3, -3, 40.0, -40.0, 800, 600, 2.0);
        final ChunkScreenRect south = ChunkScreenRect.forChunk(2, -2, 40.0, -40.0, 800, 600, 2.0);

        assertEquals(396.0, first.x());
        assertEquals(296.0, first.y());
        assertEquals(8.0, first.size());
        assertEquals(first.right(), east.x());
        assertEquals(first.bottom(), south.y());
    }
}
