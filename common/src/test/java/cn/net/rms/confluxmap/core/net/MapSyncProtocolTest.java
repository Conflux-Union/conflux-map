package cn.net.rms.confluxmap.core.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MapSyncProtocolTest {
    private static final String PREDICTOR = "cb:9afc1038ea5a|shim:9|base:14";

    @Test
    void currentHelloKeepsItsReleasedShapeAndSelectsTheHighestCommonContract() throws Exception {
        final HelloC2S hello = (HelloC2S) MsgCodec.decode(MsgCodec.encode(
            MapSyncProtocol.clientHello("0.2.0", PREDICTOR)
        ));

        final MapSyncProtocol.ServerHandshake handshake =
            MapSyncProtocol.acceptClient(hello, "0.2.0", PREDICTOR);

        assertEquals(CorrectionProfile.MATERIAL_COLOR_V3, handshake.session().correctionProfile());
        assertEquals(NegotiatedMapSync.CorrectionMode.RESIDUAL, handshake.session().correctionMode());
        assertTrue(handshake.session().supports(MapSyncCapability.REGION_CORRECTION));
        assertTrue(handshake.session().supports(MapSyncCapability.SERVER_VIEW_DISTANCE));
        assertInstanceOf(MapCapabilitiesS2C.class, handshake.selection());
    }

    @Test
    void currentHelloSelectsTheServerInstanceCapability() throws Exception {
        final HelloC2S hello = (HelloC2S) MsgCodec.decode(MsgCodec.encode(
            MapSyncProtocol.clientHello("0.2.0", PREDICTOR)
        ));

        final MapSyncProtocol.ServerHandshake handshake =
            MapSyncProtocol.acceptClient(hello, "0.2.0", PREDICTOR);

        assertTrue(handshake.session().supports(MapSyncCapability.SERVER_INSTANCE));
    }

    @Test
    void currentHelloSelectsThePlayerPositionsCapability() throws Exception {
        final HelloC2S hello = (HelloC2S) MsgCodec.decode(MsgCodec.encode(
            MapSyncProtocol.clientHello("0.2.0", PREDICTOR)
        ));

        final MapSyncProtocol.ServerHandshake handshake =
            MapSyncProtocol.acceptClient(hello, "0.2.0", PREDICTOR);

        assertTrue(handshake.session().supports(MapSyncCapability.PLAYER_POSITIONS));
    }

    /**
     * Legacy peers advertise capabilities through predictor tokens, and no released token ever
     * meant SERVER_INSTANCE. Granting it would send them a message id their codec rejects.
     */
    @Test
    void legacyHelloNeverSelectsTheServerInstanceCapability() {
        final MapSyncProtocol.ServerHandshake handshake = MapSyncProtocol.acceptClient(
            new HelloC2S("0.2.0", PREDICTOR + "|sync:1|wire:4.0|patch:4|region:2|source-light:1"),
            "0.2.0",
            PREDICTOR
        );

        assertFalse(handshake.session().supports(MapSyncCapability.SERVER_INSTANCE));
    }

    @Test
    void legacyHelloNeverSelectsThePlayerPositionsCapability() {
        final MapSyncProtocol.ServerHandshake handshake = MapSyncProtocol.acceptClient(
            new HelloC2S("0.2.0", PREDICTOR + "|sync:1|wire:4.0|patch:4|region:2|source-light:1"),
            "0.2.0",
            PREDICTOR
        );

        assertFalse(handshake.session().supports(MapSyncCapability.PLAYER_POSITIONS));
    }

    /**
     * The capability check is what actually protects a released client: its codec range stops at
     * 0x12, so an unguarded send would fail its decode rather than being ignored.
     */
    @Test
    void legacySessionRefusesToEncodeTheServerInstanceMessage() {
        final NegotiatedMapSync session = MapSyncProtocol.acceptClient(
            new HelloC2S("0.2.0", PREDICTOR + "|sync:1|wire:4.0|patch:4|region:2|source-light:1"),
            "0.2.0",
            PREDICTOR
        ).session();

        assertThrows(
            ProtoException.class,
            () -> session.encodeOutbound(
                new ServerInstanceS2C("aaaaaaaa-0000-0000-0000-000000000000")
            )
        );
    }

    @Test
    void legacySessionRefusesToEncodePlayerPositions() {
        final NegotiatedMapSync session = MapSyncProtocol.acceptClient(
            new HelloC2S("0.2.0", PREDICTOR + "|sync:1|wire:4.0|patch:4|region:2|source-light:1"),
            "0.2.0",
            PREDICTOR
        ).session();

        final ProtoException error = assertThrows(
            ProtoException.class,
            () -> session.encodeOutbound(new PlayerPositionsS2C(List.of()))
        );
        assertTrue(error.getMessage().contains("PLAYER_POSITIONS"));
    }

    @Test
    void capabilityAwareSessionEncodesTheServerInstanceMessage() throws Exception {
        final HelloC2S hello = (HelloC2S) MsgCodec.decode(MsgCodec.encode(
            MapSyncProtocol.clientHello("0.2.0", PREDICTOR)
        ));
        final NegotiatedMapSync session =
            MapSyncProtocol.acceptClient(hello, "0.2.0", PREDICTOR).session();

        final byte[] encoded = session.encodeOutbound(
            new ServerInstanceS2C("aaaaaaaa-0000-0000-0000-000000000000")
        );

        assertEquals(
            "aaaaaaaa-0000-0000-0000-000000000000",
            ((ServerInstanceS2C) MsgCodec.decode(encoded)).instanceId()
        );
    }

    @Test
    void capabilityAwareSessionEncodesPlayerPositions() throws Exception {
        final HelloC2S hello = (HelloC2S) MsgCodec.decode(MsgCodec.encode(
            MapSyncProtocol.clientHello("0.2.0", PREDICTOR)
        ));
        final NegotiatedMapSync session =
            MapSyncProtocol.acceptClient(hello, "0.2.0", PREDICTOR).session();

        final byte[] encoded = session.encodeOutbound(new PlayerPositionsS2C(List.of()));

        assertEquals(new PlayerPositionsS2C(List.of()), MsgCodec.decode(encoded));
    }

    @Test
    void predictorMismatchKeepsWireFeaturesButUsesAbsoluteCorrections() {
        final HelloC2S hello = MapSyncProtocol.clientHello("0.2.0", "cb:future|shim:10|base:15");

        final MapSyncProtocol.ServerHandshake handshake =
            MapSyncProtocol.acceptClient(hello, "0.2.0", PREDICTOR);

        assertEquals(CorrectionProfile.MATERIAL_COLOR_V3, handshake.session().correctionProfile());
        assertEquals(NegotiatedMapSync.CorrectionMode.ABSOLUTE, handshake.session().correctionMode());
    }

    @Test
    void clientResolvesTheServerSelectionToTheSameSessionContract() {
        final MapSyncProtocol.ServerHandshake server = MapSyncProtocol.acceptClient(
            MapSyncProtocol.clientHello("0.2.0", PREDICTOR), "0.2.0", PREDICTOR
        );
        final NegotiatedMapSync client = MapSyncProtocol.acceptServer(
            server.selection(), policy(true), PREDICTOR
        );

        assertEquals(server.session().correctionProfile(), client.correctionProfile());
        assertEquals(server.session().correctionMode(), client.correctionMode());
        assertEquals(
            server.session().capabilities(), client.capabilities()
        );
    }

    @Test
    void unknownSelectedCapabilitiesAreIgnoredWithoutDroppingKnownOnes() {
        final MapCapabilitiesS2C selection = new MapCapabilitiesS2C(
            MapSyncProtocol.NEGOTIATION_VERSION,
            "0.2.0",
            PREDICTOR,
            MapCompatibilityS2C.MODE_RESIDUAL,
            MapCompatibilityS2C.REASON_NONE,
            CorrectionProfile.SOURCE_LIGHT_V2.id(),
            List.of(
                new MapCapabilitiesS2C.Entry(MapSyncCapability.LOAD_STATE.id(), 1),
                new MapCapabilitiesS2C.Entry(250, 1)
            )
        );

        final NegotiatedMapSync client = MapSyncProtocol.acceptServer(
            selection, policy(true), PREDICTOR
        );

        assertTrue(client.supports(MapSyncCapability.LOAD_STATE));
        assertFalse(client.supports(MapSyncCapability.REGION_CORRECTION));
    }

    @Test
    void unknownNegotiationEnvelopeRejectsAllSelectedCapabilities() {
        final MapCapabilitiesS2C selection = new MapCapabilitiesS2C(
            MapSyncProtocol.NEGOTIATION_VERSION + 1,
            "future",
            PREDICTOR,
            MapCompatibilityS2C.MODE_RESIDUAL,
            MapCompatibilityS2C.REASON_NONE,
            CorrectionProfile.SOURCE_LIGHT_V2.id(),
            List.of(new MapCapabilitiesS2C.Entry(MapSyncCapability.LOAD_STATE.id(), 1))
        );

        final NegotiatedMapSync client = MapSyncProtocol.acceptServer(
            selection, policy(true), PREDICTOR
        );

        assertEquals(NegotiatedMapSync.CorrectionMode.DISABLED, client.correctionMode());
        assertFalse(client.supports(MapSyncCapability.LOAD_STATE));
    }

    @Test
    void clientRejectsResidualSelectionFromADifferentPredictor() {
        final MapCapabilitiesS2C selection = new MapCapabilitiesS2C(
            MapSyncProtocol.NEGOTIATION_VERSION,
            "0.2.0",
            "cb:other|shim:10|base:15",
            MapCompatibilityS2C.MODE_RESIDUAL,
            MapCompatibilityS2C.REASON_NONE,
            CorrectionProfile.SOURCE_LIGHT_V2.id(),
            List.of()
        );

        final NegotiatedMapSync client = MapSyncProtocol.acceptServer(
            selection, policy(true), PREDICTOR
        );

        assertEquals(NegotiatedMapSync.CorrectionMode.DISABLED, client.correctionMode());
    }

    @Test
    void releasedPlainHelloUsesLegacyProfileWithoutSendingUnknownSelection() {
        final MapSyncProtocol.ServerHandshake handshake = MapSyncProtocol.acceptClient(
            new HelloC2S("0.1.0", PREDICTOR), "0.2.0", PREDICTOR
        );

        assertEquals(CorrectionProfile.LEGACY_V1, handshake.session().correctionProfile());
        assertEquals(NegotiatedMapSync.CorrectionMode.RESIDUAL, handshake.session().correctionMode());
        assertEquals(null, handshake.selection());
    }

    @Test
    void sync1PeerSelectsItsHighestPublishedProfileAndReceivesLegacySelection() {
        final MapSyncProtocol.ServerHandshake handshake = MapSyncProtocol.acceptClient(
            new HelloC2S(
                "0.1.1",
                PREDICTOR + "|sync:1|wire:4.0|patch:3|region:1"
            ),
            "0.2.0",
            PREDICTOR
        );

        assertEquals(CorrectionProfile.LEGACY_V1, handshake.session().correctionProfile());
        assertEquals(NegotiatedMapSync.CorrectionMode.RESIDUAL, handshake.session().correctionMode());
        assertFalse(handshake.session().supports(MapSyncCapability.SERVER_VIEW_DISTANCE));
        assertInstanceOf(MapCompatibilityS2C.class, handshake.selection());
    }

    @Test
    void sourceLightLegacyAdvertisementStillSelectsItsPublishedProfile() {
        final MapSyncProtocol.ServerHandshake handshake = MapSyncProtocol.acceptClient(
            new HelloC2S(
                "0.1.1",
                PREDICTOR + "|sync:1|wire:4.0|patch:4|region:2|source-light:1"
            ),
            "0.2.0",
            PREDICTOR
        );

        assertEquals(CorrectionProfile.SOURCE_LIGHT_V2, handshake.session().correctionProfile());
        assertEquals(NegotiatedMapSync.CorrectionMode.RESIDUAL, handshake.session().correctionMode());
        assertInstanceOf(MapCompatibilityS2C.class, handshake.selection());
    }

    @Test
    void unknownPlainHelloDisablesOnlyCorrections() {
        final MapSyncProtocol.ServerHandshake handshake = MapSyncProtocol.acceptClient(
            new HelloC2S("beta.1", "cb:unknown|shim:1|base:1"), "0.2.0", PREDICTOR
        );

        assertEquals(NegotiatedMapSync.CorrectionMode.DISABLED, handshake.session().correctionMode());
        assertFalse(handshake.session().correctionsEnabled());
    }

    @Test
    void malformedCaps2DoesNotFallBackToBroaderLegacyClaims() {
        final MapSyncProtocol.ServerHandshake handshake = MapSyncProtocol.acceptClient(
            new HelloC2S(
                "0.2.0",
                PREDICTOR
                    + "|sync:1|wire:4.0|patch:4|region:2|source-light:1|caps2:not-base64!"
            ),
            "0.2.0",
            PREDICTOR
        );

        assertEquals(NegotiatedMapSync.CorrectionMode.DISABLED, handshake.session().correctionMode());
        assertInstanceOf(MapCapabilitiesS2C.class, handshake.selection());
    }

    @Test
    void legacySessionOwnsCorrectionBodyDowngrade() throws Exception {
        final byte[] evaluated = new byte[PatchCodec.MASK_BYTES];
        PatchCodec.setEvaluated(evaluated, 0);
        final long[] revisions = new long[PatchCodec.PIXELS];
        Arrays.fill(revisions, Long.MIN_VALUE);
        revisions[0] = 42L;
        final byte[] light = new byte[PatchCodec.PIXELS];
        light[0] = 12;
        final MapPatchS2C enhanced = new MapPatchS2C(
            1, 0, 0, 0, 0, Proto.PATCH_MODE_ABSOLUTE, 7L,
            new byte[Proto.PATCH_PRESENCE_BYTES],
            PatchCodec.encode(new PatchCodec.Patch(
                evaluated,
                List.of(new PatchCodec.Sample(0, 1, 64, 1, 1, 0)),
                revisions,
                light
            ))
        );
        final NegotiatedMapSync legacy = NegotiatedMapSync.server(
            CorrectionProfile.LEGACY_V1,
            NegotiatedMapSync.CorrectionMode.RESIDUAL,
            PREDICTOR,
            MapSyncCapability.all()
        );

        final MapPatchS2C decoded = (MapPatchS2C) MsgCodec.decode(
            legacy.encodeOutbound(enhanced)
        );
        final PatchCodec.Patch body = PatchCodec.decode(decoded.body());

        assertEquals(Long.MIN_VALUE, body.sourceRevisionAt(0));
        assertEquals(0, body.blockLightAt(0));
    }

    @Test
    void sourceLightSessionDropsOnlyMaterialIdentity() throws Exception {
        final byte[] evaluated = new byte[PatchCodec.MASK_BYTES];
        PatchCodec.setEvaluated(evaluated, 0);
        final long[] revisions = new long[PatchCodec.PIXELS];
        Arrays.fill(revisions, Long.MIN_VALUE);
        revisions[0] = 42L;
        final byte[] light = new byte[PatchCodec.PIXELS];
        light[0] = 12;
        final MapPatchS2C material = new MapPatchS2C(
            1, 0, 0, 0, 0, Proto.PATCH_MODE_ABSOLUTE, 7L,
            new byte[Proto.PATCH_PRESENCE_BYTES],
            PatchCodec.encode(new PatchCodec.Patch(
                evaluated,
                List.of(new PatchCodec.Sample(
                    0, 1, 64, 1, 18, 0, 255, "minecraft:glowstone", ""
                )),
                revisions,
                light
            ))
        );
        final NegotiatedMapSync sourceLight = NegotiatedMapSync.server(
            CorrectionProfile.SOURCE_LIGHT_V2,
            NegotiatedMapSync.CorrectionMode.RESIDUAL,
            PREDICTOR,
            MapSyncCapability.all()
        );

        final MapPatchS2C decoded = (MapPatchS2C) MsgCodec.decode(
            sourceLight.encodeOutbound(material)
        );
        final PatchCodec.Patch body = PatchCodec.decode(decoded.body());

        assertEquals("", body.sampleAt(0).materialId());
        assertEquals(42L, body.sourceRevisionAt(0));
        assertEquals(12, body.blockLightAt(0));
    }

    @Test
    void legacySessionDowngradesRegionCorrectionMetadata() throws Exception {
        final ChunkPatchCodec.Patch body = new ChunkPatchCodec.Patch(
            1, 1, 1, new byte[] {1}, new byte[] {1}, List.of(),
            new long[] {42L}, new byte[] {12}
        );
        final MapRegionPatchS2C current = new MapRegionPatchS2C(
            1, 0, 4, 0, 0, 0, 0, 0, 0,
            Proto.PATCH_MODE_RESIDUAL, 9L, ChunkPatchCodec.encode(body)
        );
        final NegotiatedMapSync legacy = NegotiatedMapSync.server(
            CorrectionProfile.LEGACY_V1,
            NegotiatedMapSync.CorrectionMode.RESIDUAL,
            PREDICTOR,
            MapSyncCapability.all()
        );

        final MapRegionPatchS2C decoded = (MapRegionPatchS2C) MsgCodec.decode(
            legacy.encodeOutbound(current)
        );
        final ChunkPatchCodec.Patch decodedBody = ChunkPatchCodec.decode(decoded.body());

        assertEquals(Long.MIN_VALUE, decodedBody.sourceRevisionAt(0));
        assertEquals(0, decodedBody.blockLightAt(0));
    }

    @Test
    void negotiatedSessionRejectsMessagesInTheWrongDirection() {
        final NegotiatedMapSync server = NegotiatedMapSync.server(
            CorrectionProfile.SOURCE_LIGHT_V2,
            NegotiatedMapSync.CorrectionMode.RESIDUAL,
            PREDICTOR,
            MapSyncCapability.all()
        );

        assertThrows(ProtoException.class, () -> server.encodeOutbound(
            new MapViewReqC2S(1, 0, 0, List.of())
        ));
    }

    @Test
    void negotiatedSessionRejectsMessagesForAnUnselectedCapability() {
        final NegotiatedMapSync client = NegotiatedMapSync.client(
            CorrectionProfile.SOURCE_LIGHT_V2,
            NegotiatedMapSync.CorrectionMode.RESIDUAL,
            PREDICTOR,
            Map.of(MapSyncCapability.LOAD_STATE, 1)
        );

        assertThrows(ProtoException.class, () -> client.encodeOutbound(
            new MapRegionViewReqC2S(1, 0, 0, List.of(
                new MapRegionViewReqC2S.RegionReq(0, 0, 0, 0, 0, 0, 0L)
            ))
        ));
    }

    private static HelloPolicyS2C policy(final boolean corrections) {
        return new HelloPolicyS2C(
            new HelloPolicyS2C.Flags(false, corrections, false, false),
            "world",
            "1.17",
            new HelloPolicyS2C.Budgets(65_536, 8, 100, 4),
            List.of()
        );
    }
}
