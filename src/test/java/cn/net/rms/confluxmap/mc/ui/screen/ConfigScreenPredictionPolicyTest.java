package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ConfigScreenPredictionPolicyTest {
    @Test
    void withheldSeedDisablesSeedDependentControlsButNotServerMapSync() {
        final ConfigScreen.PredictionSettingsAccess access =
            ConfigScreen.PredictionSettingsAccess.from(false, false, true, null, true);

        assertEquals(
            "confluxmap.screen.config.prediction.seed_disabled_by_server",
            access.disabledReasonKey(ConfigScreen.PredictionControl.UNDERLAY)
        );
        assertNull(access.disabledReasonKey(ConfigScreen.PredictionControl.NETWORK_SYNC));
        assertEquals(
            "confluxmap.screen.config.prediction.seed_disabled_by_server",
            access.disabledReasonKey(ConfigScreen.PredictionControl.STRUCTURES)
        );
    }

    @Test
    void seedIndependentFlatUnderlayStaysUsableWithoutEnablingStructureControls() {
        final ConfigScreen.PredictionSettingsAccess access =
            ConfigScreen.PredictionSettingsAccess.from(false, true, true, null, true);

        assertNull(access.disabledReasonKey(ConfigScreen.PredictionControl.UNDERLAY));
        assertEquals(
            "confluxmap.screen.config.prediction.seed_disabled_by_server",
            access.disabledReasonKey(ConfigScreen.PredictionControl.STRUCTURES)
        );
    }

    @Test
    void directServerPoliciesExplainCorrectionAndStructureControls() {
        final ConfigScreen.PredictionSettingsAccess access =
            ConfigScreen.PredictionSettingsAccess.from(
                false,
                false,
                false,
                "confluxmap.screen.config.prediction.sync_disabled_by_server",
                false
            );

        assertNull(access.disabledReasonKey(ConfigScreen.PredictionControl.UNDERLAY));
        assertEquals(
            "confluxmap.screen.config.prediction.sync_disabled_by_server",
            access.disabledReasonKey(ConfigScreen.PredictionControl.NETWORK_SYNC)
        );
        assertEquals(
            "confluxmap.map.structure_search.disabled_by_server",
            access.disabledReasonKey(ConfigScreen.PredictionControl.STRUCTURES)
        );
    }

    @Test
    void singleplayerNeverTreatsSeedSharingAsARestriction() {
        final ConfigScreen.PredictionSettingsAccess access =
            ConfigScreen.PredictionSettingsAccess.from(true, false, true, null, true);

        assertNull(access.disabledReasonKey(ConfigScreen.PredictionControl.UNDERLAY));
        assertNull(access.disabledReasonKey(ConfigScreen.PredictionControl.STRUCTURES));
    }

    @Test
    void incompatibleServerUsesItsSpecificMapSyncExplanation() {
        final ConfigScreen.PredictionSettingsAccess access =
            ConfigScreen.PredictionSettingsAccess.from(
                false,
                false,
                false,
                "confluxmap.screen.config.prediction.sync_incompatible_server",
                true
            );

        assertEquals(
            "confluxmap.screen.config.prediction.sync_incompatible_server",
            access.disabledReasonKey(ConfigScreen.PredictionControl.NETWORK_SYNC)
        );
    }
}
