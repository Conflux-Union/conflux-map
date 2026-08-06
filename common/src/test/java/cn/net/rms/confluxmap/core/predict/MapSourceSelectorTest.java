package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.net.CorrectionProfile;

import org.junit.jupiter.api.Test;

class MapSourceSelectorTest {
    @Test
    void sourceLightProfileChoosesTheNewerKnownChunkAndLocalOnTies() {
        assertTrue(MapSourceSelector.syncWins(
            true, 10L, true, 11L, CorrectionProfile.SOURCE_LIGHT_V2
        ));
        assertFalse(MapSourceSelector.syncWins(
            true, 11L, true, 10L, CorrectionProfile.SOURCE_LIGHT_V2
        ));
        assertFalse(MapSourceSelector.syncWins(
            true, 11L, true, 11L, CorrectionProfile.SOURCE_LIGHT_V2
        ));
    }

    @Test
    void knownRevisionBeatsUnknownButExistingSyncStillBeatsPrediction() {
        assertTrue(MapSourceSelector.syncWins(
            true, MapSourceSelector.UNKNOWN_REVISION, true, 1L,
            CorrectionProfile.SOURCE_LIGHT_V2
        ));
        assertFalse(MapSourceSelector.syncWins(
            true, 1L, true, MapSourceSelector.UNKNOWN_REVISION,
            CorrectionProfile.SOURCE_LIGHT_V2
        ));
        assertFalse(MapSourceSelector.syncWins(
            true, MapSourceSelector.UNKNOWN_REVISION,
            true, MapSourceSelector.UNKNOWN_REVISION, CorrectionProfile.SOURCE_LIGHT_V2
        ));
        assertTrue(MapSourceSelector.syncWins(
            false, MapSourceSelector.UNKNOWN_REVISION,
            true, MapSourceSelector.UNKNOWN_REVISION, CorrectionProfile.SOURCE_LIGHT_V2
        ));
    }

    @Test
    void legacyProfileKeepsLocalFirstBehavior() {
        assertFalse(MapSourceSelector.syncWins(
            true, 1L, true, 2L, CorrectionProfile.LEGACY_V1
        ));
        assertTrue(MapSourceSelector.syncWins(
            false, Long.MIN_VALUE, true, Long.MIN_VALUE, CorrectionProfile.LEGACY_V1
        ));
    }
}
