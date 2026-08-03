package cn.net.rms.confluxmap.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CompanionRuntimeStateTest {
    @Test
    void lanPublicationActivatesRuntimeOnlyOnce() {
        final CompanionRuntimeState state = new CompanionRuntimeState();

        assertFalse(state.activateIfAllowed(true, false, -1));
        assertFalse(state.isActive());
        assertTrue(state.activateIfAllowed(true, false, 25565));
        assertTrue(state.isActive());
        assertFalse(state.activateIfAllowed(true, false, 25565));
    }

    @Test
    void stoppedRuntimeCanActivateForTheNextServer() {
        final CompanionRuntimeState state = new CompanionRuntimeState();
        assertTrue(state.activateIfAllowed(true, true, -1));

        state.deactivate();

        assertFalse(state.isActive());
        assertTrue(state.activateIfAllowed(true, true, -1));
    }
}
