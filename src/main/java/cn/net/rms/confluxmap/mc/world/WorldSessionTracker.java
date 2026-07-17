package cn.net.rms.confluxmap.mc.world;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.util.Identifier;

/**
 * Watches the client for world joins, disconnects and dimension changes and
 * rotates the {@link SessionGuard} session accordingly. Detection is tick-based
 * so it also covers paths that fire no Fabric event (e.g. some proxy setups).
 */
public final class WorldSessionTracker {
    private final SessionGuard guard;
    private final List<Consumer<SessionGuard.Session>> listeners = new ArrayList<>();

    public WorldSessionTracker(final SessionGuard guard) {
        this.guard = guard;
    }

    public void register() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    /** Called on the main thread whenever a session starts or ends. */
    public void addListener(final Consumer<SessionGuard.Session> listener) {
        listeners.add(listener);
    }

    private void tick(final MinecraftClient client) {
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
        if (!current.active()) {
            final SessionGuard.Session session = guard.begin(resolveWorldIdentity(client), dimension);
            ConfluxMapMod.LOGGER.info(
                "Map session started: {}/{} in {} (token {})",
                session.world().serverId(), session.world().worldId(), dimension, session.token()
            );
            notifyListeners(session);
        } else if (!current.dimension().equals(dimension)) {
            final SessionGuard.Session session = guard.begin(current.world(), dimension);
            ConfluxMapMod.LOGGER.info("Map session dimension change: {} (token {})", dimension, session.token());
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

    private static WorldIdentity resolveWorldIdentity(final MinecraftClient client) {
        if (client.isInSingleplayer() && client.getServer() != null) {
            return WorldIdentity.singleplayer(client.getServer().getSaveProperties().getLevelName());
        }
        final ServerInfo server = client.getCurrentServerEntry();
        if (server != null) {
            return WorldIdentity.multiplayer(server.address);
        }
        if (client.getNetworkHandler() != null) {
            return WorldIdentity.multiplayer(client.getNetworkHandler().getConnection().getAddress().toString());
        }
        return WorldIdentity.multiplayer("unknown");
    }
}
