package cn.net.rms.confluxmap.core.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.confluxmap.core.net.shared.ResultS2C;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointCodec;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointMessage;
import cn.net.rms.confluxmap.core.net.shared.StatusS2C;
import cn.net.rms.confluxmap.core.predict.WorldPreset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Golden wire fixtures captured from the public v0.1.0 release contract. */
class ReleasedProtocolCompatibilityTest {
    private static final String HELLO_V010 =
        "AQAFMC4xLjAAHmNiOjlhZmMxMDM4ZWE1YXxzaGltOjl8YmFzZToxNA==";
    private static final String POLICY_V010 =
        "AmsAJDExMTExMTExLTIyMjItMzMzMy00NDQ0LTU1NTU1NTU1NTU1NQAEMS4xNwABAAAACAEsAgEAE21pbmVjcmFmdDpvdmVyd29ybGQACW92ZXJ3b3JsZAf////+7pN09g==";
    private static final String WAYPOINT_HELLO_V010 = "AQAAAAEAAAAB";
    private static final String WAYPOINT_STATUS_V010 =
        "AgAAAAEAAAABAQEAAAh3b3JsZC1pZAAAAAAAAAAqAAAAyAAAABQ=";
    private static final String WAYPOINT_RESULT_V010 =
        "CgARIjNEVWZ3iJmqu8zd7v8AAAABAAAACw==";

    @Test
    void v010MapHandshakeBytesRemainStableAndDecodable() throws ProtoException {
        final HelloC2S hello = new HelloC2S(
            "0.1.0",
            MapSyncCompatibility.STABLE_PREDICTOR
        );
        final HelloPolicyS2C policy = new HelloPolicyS2C(
            new HelloPolicyS2C.Flags(true, true, false, true, false, true, true, false),
            "11111111-2222-3333-4444-555555555555",
            "1.17",
            new HelloPolicyS2C.Budgets(65_536, 8, 300, 2),
            List.of(new HelloPolicyS2C.DimDescriptor(
                "minecraft:overworld",
                "overworld",
                true,
                true,
                -4_587_293_450L,
                WorldPreset.LARGE_BIOMES
            ))
        );

        assertArrayEquals(fixture(HELLO_V010), MsgCodec.encode(hello));
        assertEquals(hello, MsgCodec.decode(fixture(HELLO_V010)));
        assertArrayEquals(fixture(POLICY_V010), MsgCodec.encode(policy));
        assertEquals(policy, MsgCodec.decode(fixture(POLICY_V010)));
    }

    @Test
    void v010SharedWaypointBytesRemainStableAndDecodable() throws Exception {
        final cn.net.rms.confluxmap.core.net.shared.HelloC2S hello =
            new cn.net.rms.confluxmap.core.net.shared.HelloC2S(1, 1);
        final StatusS2C status = new StatusS2C(
            1, 1, true, true, false, "world-id", 42L, 200, 20
        );
        final ResultS2C result = new ResultS2C(
            UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"),
            1,
            11
        );

        assertSharedC2S(WAYPOINT_HELLO_V010, hello);
        assertSharedS2C(WAYPOINT_STATUS_V010, status);
        assertSharedS2C(WAYPOINT_RESULT_V010, result);
    }

    private static void assertSharedC2S(
        final String fixture,
        final SharedWaypointMessage message
    ) throws Exception {
        assertArrayEquals(fixture(fixture), SharedWaypointCodec.encode(message));
        assertEquals(message, SharedWaypointCodec.decodeC2S(fixture(fixture)));
    }

    private static void assertSharedS2C(
        final String fixture,
        final SharedWaypointMessage message
    ) throws Exception {
        assertArrayEquals(fixture(fixture), SharedWaypointCodec.encode(message));
        assertEquals(message, SharedWaypointCodec.decodeS2C(fixture(fixture)));
    }

    private static byte[] fixture(final String base64) {
        return Base64.getDecoder().decode(base64);
    }
}
