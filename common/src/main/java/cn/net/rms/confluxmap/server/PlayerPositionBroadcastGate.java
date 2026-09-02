package cn.net.rms.confluxmap.server;

/** Shared five-tick cadence for Fabric and Paper player-position broadcasts. */
public final class PlayerPositionBroadcastGate {
    private static final int INTERVAL_TICKS = 5;

    private int ticks;

    public boolean tick(final boolean enabled) {
        if (!enabled) {
            ticks = 0;
            return false;
        }
        if (++ticks < INTERVAL_TICKS) {
            return false;
        }
        ticks = 0;
        return true;
    }
}
