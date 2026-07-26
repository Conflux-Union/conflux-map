package cn.net.rms.confluxmap.core.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChunkLoadStatePublisherTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void initialSnapshotCompletesBeforeQueuedChangesAreDelivered() {
        final ChunkLoadStatePublisher publisher = new ChunkLoadStatePublisher();
        publisher.update(0, -1, 2, 31, ChunkLoadBand.ENTITY_TICKING);
        publisher.update(0, 3, 4, 33, ChunkLoadBand.BORDER);
        publisher.update(0, 100, 100, 31, ChunkLoadBand.ENTITY_TICKING);
        publisher.subscribe(PLAYER, new LoadStateSubscribeC2S(7, 0, true, -10, -10, 10, 10));

        final LoadStateDeltaS2C first = publisher.poll(PLAYER, 1);
        assertNotNull(first);
        assertTrue(first.reset());
        assertFalse(first.complete());

        publisher.remove(0, -1, 2);
        final LoadStateDeltaS2C second = publisher.poll(PLAYER, 10);
        assertNotNull(second);
        assertFalse(second.reset());
        assertTrue(second.complete());

        final LoadStateDeltaS2C queuedRemoval = publisher.poll(PLAYER, 10);
        assertEquals(1, queuedRemoval.entries().size());
        assertEquals(ChunkLoadBand.UNLOADED, queuedRemoval.entries().get(0).band());
        assertFalse(queuedRemoval.complete());
        assertNull(publisher.poll(PLAYER, 10));
    }

    @Test
    void effectiveTicketLevelsMapToStablePresentationBands() {
        assertEquals(ChunkLoadBand.ENTITY_TICKING, ChunkLoadBand.fromTicketLevel(0));
        assertEquals(ChunkLoadBand.ENTITY_TICKING, ChunkLoadBand.fromTicketLevel(31));
        assertEquals(ChunkLoadBand.BLOCK_TICKING, ChunkLoadBand.fromTicketLevel(32));
        assertEquals(ChunkLoadBand.BORDER, ChunkLoadBand.fromTicketLevel(33));
        assertEquals(ChunkLoadBand.UNLOADED, ChunkLoadBand.fromTicketLevel(34));
    }
}
