package cn.net.rms.confluxmap.mc.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void onlyAnActiveCompanionPolicyCanForbidEntityRadar() {
        final CompanionSession session = new CompanionSession();
        assertTrue(session.entityRadarAllowed());

        session.onPolicy(policy("11111111-2222-3333-4444-555555555555"));
        assertTrue(session.entityRadarAllowed());

        session.onPolicy(policy("11111111-2222-3333-4444-555555555555", true));
        assertFalse(session.entityRadarAllowed());

        session.reset();
        assertTrue(session.entityRadarAllowed());
    }

    @Test
    void onlyAnActiveCompanionPolicyCanForbidSeedFeatures() {
        final CompanionSession session = new CompanionSession();
        assertTrue(session.biomeMapAllowed());
        assertTrue(session.structureSearchAllowed());

        session.onPolicy(policy(
            "11111111-2222-3333-4444-555555555555", false, true, true
        ));
        assertFalse(session.biomeMapAllowed());
        assertFalse(session.structureSearchAllowed());

        session.reset();
        assertTrue(session.biomeMapAllowed());
        assertTrue(session.structureSearchAllowed());
    }

    private static HelloPolicyS2C policy(final String worldId) {
        return policy(worldId, false);
    }

    private static HelloPolicyS2C policy(final String worldId, final boolean entityRadarForbidden) {
        return policy(worldId, entityRadarForbidden, false, false);
    }

    private static HelloPolicyS2C policy(
        final String worldId,
        final boolean entityRadarForbidden,
        final boolean biomeMapForbidden,
        final boolean structureSearchForbidden
    ) {
        return new HelloPolicyS2C(
            new HelloPolicyS2C.Flags(
                false, true, biomeMapForbidden, false, entityRadarForbidden,
                false, false, structureSearchForbidden
            ),
            worldId,
            "1.17.1",
            new HelloPolicyS2C.Budgets(65_536, 8, 300, 2),
            List.of()
        );
    }
}
