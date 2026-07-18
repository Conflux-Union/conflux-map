package cn.net.rms.confluxmap.mc.world;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.mc.net.CompanionSession;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;

/**
 * Watches the client for world joins, disconnects, dimension changes and identity rotations
 * and rotates the {@link SessionGuard} session accordingly. Detection is tick-based so it also
 * covers paths that fire no Fabric event (e.g. some proxy setups).
 *
 * <p>Identity rotation: when a companion server is active, its advertised {@code worldId}
 * overrides the non-companion fallback. If a proxy world switch re-fires JOIN and the server
 * advertises a different {@code worldId} on the new backend, the freshly-resolved identity will
 * no longer {@code equals} the session's current one - this tracker detects that and rotates the
 * session, so caches are namespaced under the new world.
 */
public final class WorldSessionTracker {
    private final SessionGuard guard;
    private final List<Consumer<SessionGuard.Session>> listeners = new ArrayList<>();
    private final CompanionSession companion;

    public WorldSessionTracker(final SessionGuard guard, final CompanionSession companion) {
        this.guard = guard;
        this.companion = companion;
    }

    public void register() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    /** Called on the main thread whenever a session starts or ends. */
    public void addListener(final Consumer<SessionGuard.Session> listener) {
        listeners.add(listener);
    }

    private void tick(final MinecraftClient client) {
        // Pump the handshake state machine once per tick so the HELLO_SENT timeout fires on schedule.
        companion.tick();

        final SessionGuard.Session current = guard.current();
        if (client.world == null || client.player == null) {
            if (current.active()) {
                guard.end();
                ConfluxMapMod.LOGGER.info("Map session ended (token {})", current.token());
                notifyListeners(guard.current());
            }
            return;
        }
        final DimensionId dimension = toDimensionId(client.world.getRegistryKey().getValue());
        final WorldIdentity fresh = resolveWorldIdentity(client);
        if (!current.active()) {
            final SessionGuard.Session session = guard.begin(fresh, dimension);
            ConfluxMapMod.LOGGER.info(
                "Map session started: {}/{} in {} (token {})",
                session.world().serverId(), session.world().worldId(), dimension, session.token()
            );
            notifyListeners(session);
        } else if (!current.dimension().equals(dimension) || !current.world().equals(fresh)) {
            final SessionGuard.Session session = guard.begin(fresh, dimension);
            if (!current.dimension().equals(dimension) && current.world().equals(fresh)) {
                ConfluxMapMod.LOGGER.info("Map session dimension change: {} (token {})", dimension, session.token());
            } else {
                ConfluxMapMod.LOGGER.info(
                    "Map session identity change: {}/{} in {} (token {})",
                    session.world().serverId(), session.world().worldId(), dimension, session.token()
                );
            }
            notifyListeners(session);
        }
    }

    private void notifyListeners(final SessionGuard.Session session) {
        for (final Consumer<SessionGuard.Session> listener : listeners) {
            listener.accept(session);
        }
    }

    private static DimensionId toDimensionId(final Identifier id) {
        return DimensionId.of(id.getNamespace(), id.getPath());
    }

    /**
     * Resolves the client's current world identity, honoring a companion-provided {@code worldId}
     * when one is available. Pure-MC accessors live here; the {@code companion -> worldId}
     * selection is the only S3 addition and is covered by {@link WorldIdentity#multiplayer(String, String)}.
     */
    private WorldIdentity resolveWorldIdentity(final MinecraftClient client) {
        if (client.isInSingleplayer() && client.getServer() != null) {
            return WorldIdentity.singleplayerSave(client.getServer().getSavePath(WorldSavePath.ROOT));
        }
        final String address = resolveAddress(client);
        final Optional<String> override = companion.worldIdOverride();
        return override
            .<WorldIdentity>map(id -> WorldIdentity.multiplayer(address, id))
            .orElseGet(() -> WorldIdentity.multiplayer(address));
    }

    private static String resolveAddress(final MinecraftClient client) {
        final ServerInfo server = client.getCurrentServerEntry();
        if (server != null) {
            return server.address;
        }
        if (client.getNetworkHandler() != null && client.getNetworkHandler().getConnection() != null) {
            return client.getNetworkHandler().getConnection().getAddress().toString();
        }
        return "unknown";
    }
}
