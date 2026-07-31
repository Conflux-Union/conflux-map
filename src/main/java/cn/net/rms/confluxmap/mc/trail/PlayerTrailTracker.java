package cn.net.rms.confluxmap.mc.trail;

import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.trail.PlayerTrail;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;

/** Samples the local player's recent movement without retaining positions across map sessions. */
public final class PlayerTrailTracker {
    private static final int SAMPLE_INTERVAL_TICKS = 10;

    private final MinecraftClient client;
    private final ConfluxConfig config;
    private final SessionGuard sessionGuard;
    private final PlayerTrail trail;
    private int ticksUntilSample;

    public PlayerTrailTracker(
        final MinecraftClient client,
        final ConfluxConfig config,
        final SessionGuard sessionGuard,
        final PlayerTrail trail
    ) {
        this.client = client;
        this.config = config;
        this.sessionGuard = sessionGuard;
        this.trail = trail;
    }

    public void register() {
        ClientTickEvents.END_CLIENT_TICK.register(ignored -> tick());
    }

    public void onSessionChanged(final SessionGuard.Session session) {
        trail.clear();
        ticksUntilSample = 0;
    }

    private void tick() {
        if (!config.playerTrailEnabled) {
            trail.clear();
            ticksUntilSample = 0;
            return;
        }
        if (!sessionGuard.current().active() || client.world == null || client.player == null) {
            ticksUntilSample = 0;
            return;
        }
        if (ticksUntilSample > 0) {
            --ticksUntilSample;
            return;
        }
        ticksUntilSample = SAMPLE_INTERVAL_TICKS - 1;
        trail.record(
            client.player.getX(),
            client.player.getZ(),
            System.nanoTime(),
            TimeUnit.SECONDS.toNanos(config.playerTrailDurationSeconds)
        );
    }
}
