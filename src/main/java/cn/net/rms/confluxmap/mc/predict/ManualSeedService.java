package cn.net.rms.confluxmap.mc.predict;

import cn.net.rms.confluxmap.core.config.ConfigIo;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.config.ManualSeedConfig;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/** Applies client-entered seed settings only to the currently bound non-companion world. */
public final class ManualSeedService {
    private final ConfluxConfig config;
    private final ConfigIo configIo;
    private final SessionGuard sessions;
    private final BooleanSupplier singleplayer;
    private final BooleanSupplier companionActive;
    private final Runnable refreshPrediction;

    public ManualSeedService(
        final ConfluxConfig config,
        final ConfigIo configIo,
        final SessionGuard sessions,
        final BooleanSupplier singleplayer,
        final BooleanSupplier companionActive,
        final Runnable refreshPrediction
    ) {
        this.config = config;
        this.configIo = configIo;
        this.sessions = sessions;
        this.singleplayer = singleplayer;
        this.companionActive = companionActive;
        this.refreshPrediction = refreshPrediction;
    }

    public boolean available() {
        return sessions.current().active() && !singleplayer.getAsBoolean() && !companionActive.getAsBoolean();
    }

    public Optional<ManualSeedConfig.Entry> current() {
        return config.predictionManualSeeds.get(sessions.current().world());
    }

    public boolean apply(
        final WorldIdentity boundWorld,
        final String seedInput,
        final String worldgenVersion
    ) {
        if (!boundToAvailableWorld(boundWorld)) {
            return false;
        }
        config.predictionManualSeeds.set(boundWorld, seedInput, worldgenVersion);
        configIo.save(config);
        refreshPrediction.run();
        return true;
    }

    public boolean clear(final WorldIdentity boundWorld) {
        if (!boundToAvailableWorld(boundWorld)) {
            return false;
        }
        if (config.predictionManualSeeds.clear(boundWorld)) {
            configIo.save(config);
            refreshPrediction.run();
        }
        return true;
    }

    private boolean boundToAvailableWorld(final WorldIdentity boundWorld) {
        return available() && sessions.current().world().equals(boundWorld);
    }
}
