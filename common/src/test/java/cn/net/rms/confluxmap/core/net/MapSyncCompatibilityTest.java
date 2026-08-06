package cn.net.rms.confluxmap.core.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MapSyncCompatibilityTest {
    private static final String PREDICTOR = "cb:9afc1038ea5a|shim:9|base:14";

    @Test
    void advertisedHelloPreservesPredictorIdentityAndSelectsCurrentWireProfile() throws ProtoException {
        final String advertised = MapSyncCompatibility.advertise(PREDICTOR);
        final HelloC2S decoded = (HelloC2S) MsgCodec.decode(MsgCodec.encode(
            new HelloC2S("0.2.0", advertised)
        ));

        final MapSyncCompatibility.ClientHello hello =
            MapSyncCompatibility.parseClientHello(decoded.predictorVersion());

        assertEquals(PREDICTOR, hello.predictorVersion());
        assertTrue(hello.negotiationSupported());
        assertTrue(hello.supportsCurrentWire());
        assertTrue(hello.supportsLegacyWire());
        assertTrue(hello.supportsServerViewDistance());
    }

    @Test
    void newServerSelectsLegacyBodiesForReleasedNegotiationAwareClient() {
        final MapSyncCompatibility.ClientHello oldClient =
            MapSyncCompatibility.parseClientHello(
                PREDICTOR + "|sync:1|wire:4.0|patch:3|region:1"
            );

        final MapSyncCompatibility.ServerSelection selected =
            MapSyncCompatibility.selectServer(oldClient, PREDICTOR);

        assertTrue(selected.correctionsEnabled());
        assertFalse(selected.enhancedProfile());
        assertFalse(oldClient.supportsServerViewDistance());
        assertEquals(3, selected.patchCodecVersion());
        assertEquals(1, selected.regionCodecVersion());
    }

    @Test
    void newClientAcceptsBothSelectedCodecProfiles() {
        assertTrue(MapSyncCompatibility.supportedProfile(4, 2));
        assertTrue(MapSyncCompatibility.supportedProfile(3, 1));
        assertFalse(MapSyncCompatibility.supportedProfile(4, 1));
    }

    @Test
    void plainStableHelloRemainsARecognizedLegacyClient() {
        final MapSyncCompatibility.ClientHello hello =
            MapSyncCompatibility.parseClientHello(PREDICTOR);

        assertEquals(PREDICTOR, hello.predictorVersion());
        assertFalse(hello.negotiationSupported());
        assertTrue(MapSyncCompatibility.isStableLegacyPredictor(hello.predictorVersion()));
    }

    @Test
    void oldStablePolicySelectsLegacyProfileButOlderUnknownPolicyDisablesCorrections() {
        assertEquals(
            MapSyncCompatibility.ClientMode.LEGACY_RESIDUAL,
            MapSyncCompatibility.fallbackClientMode(true, true)
        );
        assertEquals(
            MapSyncCompatibility.ClientMode.INCOMPATIBLE,
            MapSyncCompatibility.fallbackClientMode(true, false)
        );
        assertEquals(
            MapSyncCompatibility.ClientMode.SERVER_DISABLED,
            MapSyncCompatibility.fallbackClientMode(false, false)
        );
        assertEquals(
            MapSyncCompatibility.ClientMode.INCOMPATIBLE,
            MapSyncCompatibility.fallbackClientMode(
                true,
                true,
                "cb:future|shim:10|base:15"
            )
        );
    }

    @Test
    void serverSelectsResidualAbsoluteOrDisabledWithoutGuessing() {
        final MapSyncCompatibility.ServerSelection same = MapSyncCompatibility.selectServer(
            MapSyncCompatibility.parseClientHello(MapSyncCompatibility.advertise(PREDICTOR)),
            PREDICTOR
        );
        assertTrue(same.correctionsEnabled());
        assertFalse(same.forceAbsolute());
        assertTrue(same.sendCompatibility());

        final MapSyncCompatibility.ServerSelection mismatched = MapSyncCompatibility.selectServer(
            MapSyncCompatibility.parseClientHello(MapSyncCompatibility.advertise(
                "cb:future|shim:10|base:15"
            )),
            PREDICTOR
        );
        assertTrue(mismatched.correctionsEnabled());
        assertTrue(mismatched.forceAbsolute());
        assertTrue(mismatched.sendCompatibility());

        final MapSyncCompatibility.ServerSelection stableLegacy = MapSyncCompatibility.selectServer(
            MapSyncCompatibility.parseClientHello(PREDICTOR), PREDICTOR
        );
        assertTrue(stableLegacy.correctionsEnabled());
        assertFalse(stableLegacy.forceAbsolute());
        assertFalse(stableLegacy.sendCompatibility());

        final MapSyncCompatibility.ServerSelection stableLegacyOnFutureServer =
            MapSyncCompatibility.selectServer(
                MapSyncCompatibility.parseClientHello(PREDICTOR),
                "cb:future|shim:10|base:15"
            );
        assertFalse(stableLegacyOnFutureServer.correctionsEnabled());
        assertFalse(stableLegacyOnFutureServer.sendCompatibility());

        final MapSyncCompatibility.ServerSelection unknownLegacy = MapSyncCompatibility.selectServer(
            MapSyncCompatibility.parseClientHello("cb:old|shim:3|base:8"), PREDICTOR
        );
        assertFalse(unknownLegacy.correctionsEnabled());
        assertFalse(unknownLegacy.sendCompatibility());
    }
}
