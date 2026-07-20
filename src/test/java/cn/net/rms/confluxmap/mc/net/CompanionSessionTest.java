package cn.net.rms.confluxmap.mc.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.net.HelloPolicyS2C;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompanionSessionTest {
    @Test
    void multiplayerIdentityWaitsForAnOutstandingHandshake() {
        final CompanionSession session = new CompanionSession();
        assertEquals(WorldIdentity.multiplayer("example.net"), session.resolveWorldIdentity("example.net").orElseThrow());

        session.onHelloSent();
        assertTrue(session.resolveWorldIdentity("example.net").isEmpty());

        session.onPolicy(policy("11111111-2222-3333-4444-555555555555"));
        assertEquals(
            WorldIdentity.multiplayer("example.net", "11111111-2222-3333-4444-555555555555"),
            session.resolveWorldIdentity("example.net").orElseThrow()
        );
    }

    @Test
    void handshakeTimeoutReleasesTheAddressFallback() {
        final CompanionSession session = new CompanionSession();
        session.onHelloSent();
        for (int i = 0; i < CompanionSession.TIMEOUT_TICKS; i++) {
            session.tick();
        }

        assertEquals(WorldIdentity.multiplayer("example.net"), session.resolveWorldIdentity("example.net").orElseThrow());
    }

    private static HelloPolicyS2C policy(final String worldId) {
        return new HelloPolicyS2C(
            new HelloPolicyS2C.Flags(false, true, true),
            worldId,
            "1.17.1",
            new HelloPolicyS2C.Budgets(65_536, 8, 300, 2),
            List.of()
        );
    }
}
