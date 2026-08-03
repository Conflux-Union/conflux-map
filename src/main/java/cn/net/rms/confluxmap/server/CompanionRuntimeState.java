package cn.net.rms.confluxmap.server;

final class CompanionRuntimeState {
    private volatile boolean active;

    boolean activateIfAllowed(
        final boolean configured,
        final boolean dedicated,
        final int serverPort
    ) {
        if (active || !CompanionActivationPolicy.shouldActivate(configured, dedicated, serverPort)) {
            return false;
        }
        active = true;
        return true;
    }

    boolean isActive() {
        return active;
    }

    void deactivate() {
        active = false;
    }
}
