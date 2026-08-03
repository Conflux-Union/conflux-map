package cn.net.rms.confluxmap.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CompanionActivationPolicyTest {
    @Test
    void pureSingleplayerDoesNotActivateCompanion() {
        assertFalse(CompanionActivationPolicy.shouldActivate(true, false, -1));
    }

    @Test
    void lanWorldActivatesCompanion() {
        assertTrue(CompanionActivationPolicy.shouldActivate(true, false, 25565));
    }

    @Test
    void dedicatedServerActivatesCompanionWithoutLanPort() {
        assertTrue(CompanionActivationPolicy.shouldActivate(true, true, -1));
    }

    @Test
    void disabledConfigNeverActivatesCompanion() {
        assertFalse(CompanionActivationPolicy.shouldActivate(false, true, 25565));
    }
}
