package cn.net.rms.confluxmap.mc.predict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.config.ConfigIo;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManualSeedServiceTest {
    @Test
    void appliesOnlyToTheBoundNonCompanionWorldWithoutEnablingSync(@TempDir final Path temp) {
        final ConfluxConfig config = new ConfluxConfig();
        config.predictionNetworkSync = false;
        final ConfigIo configIo = new ConfigIo(temp.resolve("config.json"), LogManager.getLogger());
        final SessionGuard sessions = new SessionGuard();
        final WorldIdentity first = WorldIdentity.multiplayer("play.example.net", "survival");
        final WorldIdentity second = WorldIdentity.multiplayer("play.example.net", "creative");
        sessions.begin(first, DimensionId.OVERWORLD);
        final AtomicBoolean singleplayer = new AtomicBoolean();
        final AtomicBoolean companionActive = new AtomicBoolean();
        final AtomicInteger refreshes = new AtomicInteger();
        final ManualSeedService service = new ManualSeedService(
            config,
            configIo,
            sessions,
            singleplayer::get,
            companionActive::get,
            refreshes::incrementAndGet
        );

        assertTrue(service.apply(first, "123456", "1.21.1"));
        assertEquals(123456L, service.current().orElseThrow().seed());
        assertFalse(config.predictionNetworkSync);
        assertEquals(1, refreshes.get());
        assertEquals(
            123456L,
            configIo.load().predictionManualSeeds.get(first).orElseThrow().seed()
        );

        sessions.begin(second, DimensionId.OVERWORLD);
        assertFalse(service.apply(first, "789", "1.21.1"));
        assertTrue(service.current().isEmpty());
        assertEquals(1, refreshes.get());

        companionActive.set(true);
        assertTrue(service.apply(second, "789", "1.21.1"));
        assertEquals(789L, service.current().orElseThrow().seed());
        assertEquals(2, refreshes.get());
    }

    @Test
    void remainsAvailableWhenAnActiveCompanionWithholdsItsSeed(@TempDir final Path temp) {
        final ConfluxConfig config = new ConfluxConfig();
        final ConfigIo configIo = new ConfigIo(temp.resolve("config.json"), LogManager.getLogger());
        final SessionGuard sessions = new SessionGuard();
        final WorldIdentity world = WorldIdentity.companionMultiplayer(
            "play.example.net", "11111111-2222-3333-4444-555555555555"
        );
        sessions.begin(world, DimensionId.OVERWORLD);
        final AtomicInteger refreshes = new AtomicInteger();
        final ManualSeedService service = new ManualSeedService(
            config,
            configIo,
            sessions,
            () -> false,
            () -> true,
            () -> false,
            refreshes::incrementAndGet
        );

        assertTrue(service.available());
        assertTrue(service.apply(world, "123", "1.21.1"));
        assertEquals(123L, service.current().orElseThrow().seed());
        assertEquals(1, refreshes.get());
    }

    @Test
    void remainsAvailableWhenAnActiveCompanionSharesItsSeed(@TempDir final Path temp) {
        final ConfluxConfig config = new ConfluxConfig();
        final ConfigIo configIo = new ConfigIo(temp.resolve("config.json"), LogManager.getLogger());
        final SessionGuard sessions = new SessionGuard();
        final WorldIdentity world = WorldIdentity.companionMultiplayer(
            "play.example.net", "11111111-2222-3333-4444-555555555555"
        );
        sessions.begin(world, DimensionId.OVERWORLD);
        final ManualSeedService service = new ManualSeedService(
            config,
            configIo,
            sessions,
            () -> false,
            () -> true,
            () -> true,
            () -> { }
        );

        assertTrue(service.available());
        assertTrue(service.apply(world, "987", "1.21.1"));
        assertEquals(987L, service.current().orElseThrow().seed());
    }
}
