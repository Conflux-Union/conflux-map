package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.core.net.HelloPolicyS2C;
import cn.net.rms.confluxmap.core.net.MapSyncCapability;
import cn.net.rms.confluxmap.core.net.NegotiatedMapSync;

/** Platform-neutral policy projection shared by the Fabric and Paper transports. */
public final class CompanionPolicy {
    private CompanionPolicy() {
    }

    public static HelloPolicyS2C.Flags configuredFlags(final ServerConfig config) {
        final boolean sharedSeedPolicy = config.enabled && config.shareSeed;
        return new HelloPolicyS2C.Flags(
            sharedSeedPolicy,
            config.enabled && config.shareCorrections,
            sharedSeedPolicy && !config.allowBiomeMap,
            config.enabled && config.shareChunkLoadState,
            config.enabled && !config.allowEntityRadar,
            config.enabled && config.shareCorrections,
            config.enabled && config.shareCorrections,
            sharedSeedPolicy && !config.allowStructureSearch
        );
    }

    public static HelloPolicyS2C.Flags compatibleFlags(
        final HelloPolicyS2C.Flags configured,
        final NegotiatedMapSync session
    ) {
        final boolean corrections = configured.correctionsEnabled()
            && session.correctionsEnabled();
        return new HelloPolicyS2C.Flags(
            configured.seedGranted(),
            corrections,
            configured.biomeMapForbidden(),
            configured.chunkLoadStateEnabled()
                && session.supports(MapSyncCapability.LOAD_STATE),
            configured.entityRadarForbidden(),
            corrections && configured.correctionInvalidationEnabled()
                && session.supports(MapSyncCapability.MAP_INVALIDATION),
            corrections && configured.chunkRangeCorrectionEnabled()
                && session.supports(MapSyncCapability.REGION_CORRECTION)
                && session.supports(MapSyncCapability.REGION_INVALIDATION),
            configured.structureSearchForbidden()
        );
    }
}
