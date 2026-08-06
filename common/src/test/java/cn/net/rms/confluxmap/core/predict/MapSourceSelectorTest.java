package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MapSourceSelectorTest {
    @Test
    void enhancedProfileChoosesTheNewerKnownChunkAndLocalOnTies() {
        assertTrue(MapSourceSelector.syncWins(true, 10L, true, 11L, true));
        assertFalse(MapSourceSelector.syncWins(true, 11L, true, 10L, true));
        assertFalse(MapSourceSelector.syncWins(true, 11L, true, 11L, true));
    }

    @Test
    void knownRevisionBeatsUnknownButExistingSyncStillBeatsPrediction() {
        assertTrue(MapSourceSelector.syncWins(
            true, MapSourceSelector.UNKNOWN_REVISION, true, 1L, true
        ));
        assertFalse(MapSourceSelector.syncWins(
            true, 1L, true, MapSourceSelector.UNKNOWN_REVISION, true
        ));
        assertFalse(MapSourceSelector.syncWins(
            true, MapSourceSelector.UNKNOWN_REVISION,
            true, MapSourceSelector.UNKNOWN_REVISION, true
        ));
        assertTrue(MapSourceSelector.syncWins(
            false, MapSourceSelector.UNKNOWN_REVISION,
            true, MapSourceSelector.UNKNOWN_REVISION, true
        ));
    }

    @Test
    void legacyProfileKeepsLocalFirstBehavior() {
        assertFalse(MapSourceSelector.syncWins(true, 1L, true, 2L, false));
        assertTrue(MapSourceSelector.syncWins(false, Long.MIN_VALUE, true, Long.MIN_VALUE, false));
    }
}
