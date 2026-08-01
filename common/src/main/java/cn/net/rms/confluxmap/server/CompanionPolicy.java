package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.core.net.HelloPolicyS2C;
import cn.net.rms.confluxmap.core.net.MapSyncCompatibility;

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
        final MapSyncCompatibility.ServerSelection selection
    ) {
        final boolean corrections = configured.correctionsEnabled()
            && selection.correctionsEnabled();
        return new HelloPolicyS2C.Flags(
            configured.seedGranted(),
            corrections,
            configured.biomeMapForbidden(),
            configured.chunkLoadStateEnabled(),
            configured.entityRadarForbidden(),
            corrections && configured.correctionInvalidationEnabled(),
            corrections && configured.chunkRangeCorrectionEnabled(),
            configured.structureSearchForbidden()
        );
    }
}
