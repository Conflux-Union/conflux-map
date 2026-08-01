package cn.net.rms.confluxmap.core.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
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
}
