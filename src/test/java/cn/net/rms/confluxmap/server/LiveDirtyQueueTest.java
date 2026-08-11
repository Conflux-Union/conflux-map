package cn.net.rms.confluxmap.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

final class LiveDirtyQueueTest {
    @Test
    void dirtySignalBeforeFirstViewerIsRetainedUntilTheChunkBecomesDemanded() {
        final LiveDirtyQueue<String> dirty = new LiveDirtyQueue<>();

        dirty.mark("overworld:4:7");
        dirty.mark("overworld:4:7");

        assertNull(dirty.pollMatching(key -> false, 128));
        assertEquals("overworld:4:7", dirty.pollMatching(key -> true, 128));
        assertNull(dirty.pollMatching(key -> true, 128));
    }

    @Test
    void removedChunksCannotBeRefreshedLater() {
        final LiveDirtyQueue<String> dirty = new LiveDirtyQueue<>();
        dirty.mark("overworld:4:7");
        dirty.remove("overworld:4:7");

        assertNull(dirty.pollMatching(key -> true, 128));
    }
}
