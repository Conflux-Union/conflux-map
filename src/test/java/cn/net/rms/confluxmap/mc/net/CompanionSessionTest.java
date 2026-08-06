package cn.net.rms.confluxmap.mc.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.net.CorrectionProfile;
import cn.net.rms.confluxmap.core.net.HelloPolicyS2C;
import cn.net.rms.confluxmap.core.net.MapCompatibilityS2C;
import cn.net.rms.confluxmap.core.net.MapSyncCompatibility;
import cn.net.rms.confluxmap.core.net.PatchCodec;
import cn.net.rms.confluxmap.core.net.ChunkPatchCodec;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.net.ServerViewDistanceS2C;
import cn.net.rms.confluxmap.nativepredict.PredictorVersion;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompanionSessionTest {
    @Test
    void explicitCompatibilitySelectsResidualOrAbsoluteBeforePolicy() {
        final CompanionSession residual = new CompanionSession();
        residual.onHelloSent();
        residual.onSelection(compatibility(MapCompatibilityS2C.MODE_RESIDUAL));
        residual.onPolicy(policyWithCorrections(true));
        assertEquals(MapSyncCompatibility.ClientMode.OPTIMAL_RESIDUAL, residual.mapSyncMode());
        assertTrue(residual.policy().flags().correctionsEnabled());

        final CompanionSession absolute = new CompanionSession();
        absolute.onHelloSent();
        absolute.onSelection(compatibility(MapCompatibilityS2C.MODE_ABSOLUTE));
        absolute.onPolicy(policyWithCorrections(true));
        assertEquals(MapSyncCompatibility.ClientMode.COMPATIBLE_ABSOLUTE, absolute.mapSyncMode());
        assertTrue(absolute.policy().flags().correctionsEnabled());
        assertEquals(CorrectionProfile.MATERIAL_COLOR_V3, residual.mapSyncCorrectionProfile());
        assertEquals(CorrectionProfile.MATERIAL_COLOR_V3, absolute.mapSyncCorrectionProfile());
    }

    @Test
    void serverViewDistanceIsIndependentFromClientSettings() {
        final CompanionSession session = new CompanionSession();
        session.onHelloSent();
        session.onServerViewDistance(new ServerViewDistanceS2C(12));
        session.onSelection(compatibility(MapCompatibilityS2C.MODE_RESIDUAL));
        session.onPolicy(policyWithCorrections(true));

        assertEquals(12, session.serverViewDistance());
    }

    @Test
    void releasedFallbackUsesLegacyRegionRevisionProfile() {
        final CompanionSession session = new CompanionSession();
        session.onHelloSent();
        session.onPolicy(policyWithCorrections(true));

        assertEquals(CorrectionProfile.LEGACY_V1, session.mapSyncCorrectionProfile());
    }

    @Test
    void compatibilitySelectionIsFrozenWhenThePolicyActivatesTheSession() {
        final CompanionSession session = new CompanionSession();
        session.onHelloSent();
        session.onSelection(compatibility(MapCompatibilityS2C.MODE_RESIDUAL));
        session.onPolicy(policyWithCorrections(true));

        session.onSelection(new MapCompatibilityS2C(
            MapSyncCompatibility.NEGOTIATION_VERSION,
            "future",
            Proto.PROTO_MAJOR,
            Proto.PROTO_MINOR,
            PatchCodec.FORMAT_VERSION,
            ChunkPatchCodec.FORMAT_VERSION,
            "cb:future|shim:10|base:15",
            MapCompatibilityS2C.MODE_RESIDUAL,
            MapCompatibilityS2C.REASON_NONE
        ));

        assertEquals(PredictorVersion.full(), session.mapSyncBaselineProfile());
    }

    @Test
    void explicitResidualIsRejectedWhenItsBaselineCannotBeReproduced() {
        final CompanionSession session = new CompanionSession();
        session.onHelloSent();
        session.onSelection(new MapCompatibilityS2C(
            MapSyncCompatibility.NEGOTIATION_VERSION,
            "future",
            Proto.PROTO_MAJOR,
            Proto.PROTO_MINOR,
            PatchCodec.FORMAT_VERSION,
            ChunkPatchCodec.FORMAT_VERSION,
            "cb:future|shim:10|base:15",
            MapCompatibilityS2C.MODE_RESIDUAL,
            MapCompatibilityS2C.REASON_NONE
        ));
        session.onPolicy(policyWithCorrections(true));

        assertEquals(MapSyncCompatibility.ClientMode.INCOMPATIBLE, session.mapSyncMode());
        assertFalse(session.policy().flags().correctionsEnabled());
    }

    @Test
    void explicitNoCommonWireKeepsTheIncompatibleReasonWhenPolicyMasksCorrections() {
        final CompanionSession session = new CompanionSession();
        session.onHelloSent();
        session.onSelection(compatibility(MapCompatibilityS2C.MODE_DISABLED));
        session.onPolicy(new HelloPolicyS2C(
            new HelloPolicyS2C.Flags(false, false, false, false),
            "11111111-2222-3333-4444-555555555555",
            "1.17.1",
            new HelloPolicyS2C.Budgets(65_536, 8, 300, 2),
            List.of()
        ));

        assertEquals(MapSyncCompatibility.ClientMode.INCOMPATIBLE, session.mapSyncMode());
        assertEquals(
            "confluxmap.screen.config.prediction.sync_incompatible_server",
            session.mapCorrectionDisabledReasonKey()
        );
    }

    @Test
    void compatibleSelectionReportsServerDisabledWhenPolicyTurnsCorrectionsOff() {
        final CompanionSession session = new CompanionSession();
        session.onHelloSent();
        session.onSelection(compatibility(MapCompatibilityS2C.MODE_RESIDUAL));
        session.onPolicy(new HelloPolicyS2C(
            new HelloPolicyS2C.Flags(false, false, false, false),
            "11111111-2222-3333-4444-555555555555",
            "1.17.1",
            new HelloPolicyS2C.Budgets(65_536, 8, 300, 2),
            List.of()
        ));

        assertEquals(MapSyncCompatibility.ClientMode.SERVER_DISABLED, session.mapSyncMode());
        assertEquals(
            "confluxmap.screen.config.prediction.sync_disabled_by_server",
            session.mapCorrectionDisabledReasonKey()
        );
    }

    @Test
    void releasedPolicyFingerprintIsRejectedWhenItsBaselineCannotBeReproduced() {
        final CompanionSession session = new CompanionSession();
        session.onHelloSent();
        session.onPolicy(policyWithCorrections(true));

        assertEquals(MapSyncCompatibility.ClientMode.INCOMPATIBLE, session.mapSyncMode());
        assertFalse(session.policy().flags().correctionsEnabled());
    }

    @Test
    void unknownOlderCorrectionProtocolIsLocallyDisabledWithoutDroppingCompanion() {
        final CompanionSession session = new CompanionSession();
        session.onHelloSent();
        session.onPolicy(policyWithCorrections(false));

        assertTrue(session.isActive());
        assertEquals(MapSyncCompatibility.ClientMode.INCOMPATIBLE, session.mapSyncMode());
        assertFalse(session.policy().flags().correctionsEnabled());
        assertEquals(
            "confluxmap.screen.config.prediction.sync_incompatible_server",
            session.mapCorrectionDisabledReasonKey()
        );
    }

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

    @Test
    void activePolicyReportsWithheldSeedAndCorrectionsAsServerDisabled() {
        final CompanionSession session = new CompanionSession();
        assertNull(session.mapCorrectionDisabledReasonKey());
        session.onPolicy(new HelloPolicyS2C(
            new HelloPolicyS2C.Flags(false, false, false, false),
            "11111111-2222-3333-4444-555555555555",
            "1.17.1",
            new HelloPolicyS2C.Budgets(65_536, 8, 300, 2),
            List.of()
        ));

        assertTrue(session.seedSharingDisabledByServer());
        assertTrue(session.mapCorrectionsDisabledByServer());

        session.reset();
        assertFalse(session.seedSharingDisabledByServer());
        assertFalse(session.mapCorrectionsDisabledByServer());
        assertNull(session.mapCorrectionDisabledReasonKey());
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

    private static HelloPolicyS2C policyWithCorrections(final boolean chunkRange) {
        return new HelloPolicyS2C(
            new HelloPolicyS2C.Flags(
                false, true, false, false, false, chunkRange, chunkRange, false
            ),
            "11111111-2222-3333-4444-555555555555",
            "1.17.1",
            new HelloPolicyS2C.Budgets(65_536, 8, 300, 4),
            List.of()
        );
    }

    private static MapCompatibilityS2C compatibility(final int mode) {
        return new MapCompatibilityS2C(
            MapSyncCompatibility.NEGOTIATION_VERSION,
            "0.2.0",
            Proto.PROTO_MAJOR,
            Proto.PROTO_MINOR,
            PatchCodec.FORMAT_VERSION,
            ChunkPatchCodec.FORMAT_VERSION,
            PredictorVersion.full(),
            mode,
            mode == MapCompatibilityS2C.MODE_RESIDUAL
                ? MapCompatibilityS2C.REASON_NONE
                : mode == MapCompatibilityS2C.MODE_ABSOLUTE
                    ? MapCompatibilityS2C.REASON_BASELINE_MISMATCH
                    : MapCompatibilityS2C.REASON_NO_COMMON_WIRE
        );
    }
}
