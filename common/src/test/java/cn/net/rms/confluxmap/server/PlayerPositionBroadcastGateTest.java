package cn.net.rms.confluxmap.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PlayerPositionBroadcastGateTest {
    @Test
    void publishesEveryFifthEnabledServerTick() {
        final PlayerPositionBroadcastGate gate = new PlayerPositionBroadcastGate();

        for (int tick = 1; tick < 5; tick++) {
            assertFalse(gate.tick(true));
        }
        assertTrue(gate.tick(true));
        for (int tick = 6; tick < 10; tick++) {
            assertFalse(gate.tick(true));
        }
        assertTrue(gate.tick(true));
    }
}
