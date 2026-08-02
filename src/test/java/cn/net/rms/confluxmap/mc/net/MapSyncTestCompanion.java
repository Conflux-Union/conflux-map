package cn.net.rms.confluxmap.mc.net;

import cn.net.rms.confluxmap.core.net.ChunkPatchCodec;
import cn.net.rms.confluxmap.core.net.HelloPolicyS2C;
import cn.net.rms.confluxmap.core.net.MapCompatibilityS2C;
import cn.net.rms.confluxmap.core.net.MapSyncCompatibility;
import cn.net.rms.confluxmap.core.net.PatchCodec;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.nativepredict.PredictorVersion;

/** Activates a test session as a current negotiation-aware companion. */
final class MapSyncTestCompanion {
    private MapSyncTestCompanion() {
    }

    static void activate(
        final CompanionSession session,
        final HelloPolicyS2C policy
    ) {
        session.onHelloSent();
        session.onCompatibility(new MapCompatibilityS2C(
            MapSyncCompatibility.NEGOTIATION_VERSION,
            "test",
            Proto.PROTO_MAJOR,
            Proto.PROTO_MINOR,
            PatchCodec.FORMAT_VERSION,
            ChunkPatchCodec.FORMAT_VERSION,
            PredictorVersion.full(),
            MapCompatibilityS2C.MODE_RESIDUAL,
            MapCompatibilityS2C.REASON_NONE
        ));
        session.onPolicy(policy);
    }
}
