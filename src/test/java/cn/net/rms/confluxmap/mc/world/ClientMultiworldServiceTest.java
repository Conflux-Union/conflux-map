package cn.net.rms.confluxmap.mc.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldObservation;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldProfile;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldProfileRegistry;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldProfileResolver;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldResolution;
import cn.net.rms.confluxmap.mc.net.CompanionSession;
import java.nio.file.Path;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientMultiworldServiceTest {
    private static final String ADDRESS = "proxy.example.net:25565";

    @TempDir
    Path tempDir;

    @Test
    void differentSeedProxyJoinStillSelectsASeparateProfile() {
        final ClientMultiworldService service = service(new ClientWorldProfileResolver(
            new ClientWorldProfileRegistry(), ids()
        ));

        service.onGameJoin(11L);
        final WorldIdentity first = service.resolve(ADDRESS).orElseThrow();
        service.onGameJoin(22L);
        final WorldIdentity second = service.resolve(ADDRESS).orElseThrow();

        assertNotEquals(first.worldId(), second.worldId());
    }

    @Test
    void differentRespawnSeedCompletesARepeatedSeedProxyTransition() {
        final ClientMultiworldService service = service(new ClientWorldProfileResolver(
            new ClientWorldProfileRegistry(), ids()
        ));
        service.onGameJoin(11L);
        final WorldIdentity first = service.resolve(ADDRESS).orElseThrow();

        service.onGameJoin(11L);
        assertEquals(
            ClientWorldResolution.State.AMBIGUOUS,
            service.resolveProfile(ADDRESS).state()
        );
        service.onRespawn(22L);
        final WorldIdentity second = service.resolve(ADDRESS).orElseThrow();

        assertNotEquals(first.worldId(), second.worldId());
    }

    @Test
    void resolvedVisitDoesNotRotateWhenLateSignalsFavorAnotherProfile() {
        final ClientWorldProfileRegistry registry = new ClientWorldProfileRegistry();
        final ClientWorldProfileResolver resolver = new ClientWorldProfileResolver(registry, ids());
        final ClientMultiworldService service = service(resolver);
        service.onGameJoin(11L);
        final WorldIdentity locked = service.resolve(ADDRESS).orElseThrow();
        final String serverId = locked.serverId();
        final ClientWorldProfile competing = resolver.resolve(
            serverId, observation(22L, Map.of("brand", "other"))
        ).profile();
        final Map<String, String> lateSignals = Map.of(
            "brand", "target", "commands", "target", "dimension", "target"
        );
        resolver.select(serverId, competing.id(), observation(11L, lateSignals));

        service.onRespawn(11L);
        service.observeSignals(lateSignals);

        assertEquals(locked, service.resolve(ADDRESS).orElseThrow());
    }

    @Test
    void profileLockDoesNotLeakAcrossServerAddresses() {
        final ClientMultiworldService service = service(new ClientWorldProfileResolver(
            new ClientWorldProfileRegistry(), ids()
        ));
        service.onGameJoin(11L);
        final WorldIdentity first = service.resolve(ADDRESS).orElseThrow();

        final WorldIdentity second = service.resolve("other.example.net:25565").orElseThrow();

        assertNotEquals(first.serverId(), second.serverId());
    }

    private ClientMultiworldService service(final ClientWorldProfileResolver resolver) {
        return new ClientMultiworldService(
            null, new CompanionSession(), resolver, tempDir.resolve("cache"), Runnable::run
        );
    }

    private static ClientWorldObservation observation(final long seed, final Map<String, String> signals) {
        return new ClientWorldObservation(OptionalLong.of(seed), signals);
    }

    private static java.util.function.Supplier<UUID> ids() {
        final AtomicLong value = new AtomicLong(1L);
        return () -> new UUID(0L, value.getAndIncrement());
    }
}
