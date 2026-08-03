package cn.net.rms.confluxmap.server;

/**
 * Detects whether server-only features have a remote audience without linking the client-only
 * IntegratedServer class on dedicated servers. IntegratedServer uses -1 until published to LAN.
 */
final class CompanionActivationPolicy {
    private CompanionActivationPolicy() {
    }

    static boolean shouldActivate(
        final boolean configured,
        final boolean dedicated,
        final int serverPort
    ) {
        return configured && (dedicated || serverPort >= 0);
    }
}
