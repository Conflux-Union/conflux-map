package cn.net.rms.confluxmap.mc.world;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.multiworld.ServerAliasResolver;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.mc.net.CompanionSession;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
    private final ClientMultiworldService clientMultiworld;
    private final ServerAliasResolver aliases;
    private Path singleplayerSaveRoot;
    private WorldIdentity singleplayerIdentity;
    private String observedAddress;
    private String observedCompanionWorldId;
    private String observedCompanionInstanceId;
    private String canonicalAddress;
    private Consumer<WorldIdentity> namespaceAdopter = identity -> { };

    public WorldSessionTracker(final SessionGuard guard, final CompanionSession companion) {
        this(guard, companion, null, null);
    }

    public WorldSessionTracker(
        final SessionGuard guard,
        final CompanionSession companion,
        final ClientMultiworldService clientMultiworld
    ) {
        this(guard, companion, clientMultiworld, null);
    }

    /**
     * @param aliases resolves the typed address to the namespace its data lives in, merging the
     *                several addresses of one server; null keeps the raw address, as before
     */
    public WorldSessionTracker(
        final SessionGuard guard,
        final CompanionSession companion,
        final ClientMultiworldService clientMultiworld,
        final ServerAliasResolver aliases
    ) {
        this.guard = guard;
        this.companion = companion;
        this.clientMultiworld = clientMultiworld;
        this.aliases = aliases;
    }

    public void register() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    /** Called on the main thread whenever a session starts or ends. */
    public void addListener(final Consumer<SessionGuard.Session> listener) {
        listeners.add(listener);
    }

    /**
     * Runs once per identity, immediately before its session opens, so stored data can be carried
     * into a namespace whose key changed. It has to precede the session: the region cache binds
     * its directory the moment the session begins.
     */
    public void bindNamespaceAdopter(final Consumer<WorldIdentity> adopter) {
        this.namespaceAdopter = Objects.requireNonNull(adopter, "adopter");
    }

    /** Ends the active session and notifies every lifecycle owner; safe to call repeatedly. */
    public void endSession() {
        if (!guard.current().active()) {
            return;
        }
        guard.end();
        notifyListeners(guard.current());
    }

    private void tick(final MinecraftClient client) {
        // Pump the handshake state machine once per tick so the HELLO_SENT timeout fires on schedule.
        companion.tick();

        final SessionGuard.Session current = guard.current();
        if (client.world == null || client.player == null) {
            singleplayerSaveRoot = null;
            singleplayerIdentity = null;
            forgetCanonicalAddress();
            if (current.active()) {
                endSession();
                ConfluxMapMod.LOGGER.info("Map session ended (token {})", current.token());
            }
            return;
        }
        final DimensionId dimension = toDimensionId(client.world.getRegistryKey().getValue());
        updateSession(resolveWorldIdentity(client), dimension);
    }

    /** Applies one MC-free identity observation to the session state machine. */
    void updateSession(final Optional<WorldIdentity> resolved, final DimensionId dimension) {
        final SessionGuard.Session current = guard.current();
        if (resolved.isEmpty()) {
            if (current.active()) {
                endSession();
                ConfluxMapMod.LOGGER.info(
                    "Map session suspended while waiting for a stable multiplayer world identity (token {})",
                    current.token()
                );
            }
            return;
        }
        final WorldIdentity fresh = resolved.get();
        if (!current.active()) {
            namespaceAdopter.accept(fresh);
            final SessionGuard.Session session = guard.begin(fresh, dimension);
            ConfluxMapMod.LOGGER.info(
                "Map session started: {}/{} in {} (token {})",
                session.world().serverId(), session.world().worldId(), dimension, session.token()
            );
            notifyListeners(session);
        } else if (!current.dimension().equals(dimension) || !current.world().equals(fresh)) {
            if (!current.world().equals(fresh)) {
                namespaceAdopter.accept(fresh);
            }
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
     * Resolves the client's current world identity. Local sessions use the save directory; remote
     * sessions delegate handshake state and companion world ids to {@link CompanionSession}.
     * Empty means a HELLO is outstanding and any existing session must be suspended.
     */
    private Optional<WorldIdentity> resolveWorldIdentity(final MinecraftClient client) {
        if (client.isInSingleplayer() && client.getServer() != null) {
            final Path saveRoot = client.getServer().getSavePath(WorldSavePath.ROOT).normalize();
            if (!saveRoot.equals(singleplayerSaveRoot)) {
                singleplayerSaveRoot = saveRoot;
                singleplayerIdentity = WorldIdentity.singleplayerSave(saveRoot);
            }
            return Optional.of(singleplayerIdentity);
        }
        singleplayerSaveRoot = null;
        singleplayerIdentity = null;
        if (clientMultiworld != null && clientMultiworld.migrationInProgress()) {
            return Optional.empty();
        }
        final String address = canonicalAddress(resolveAddress(client));
        if (companion.state() == CompanionSession.State.ACTIVE
            || companion.state() == CompanionSession.State.HELLO_SENT
            || clientMultiworld == null) {
            return companion.resolveWorldIdentity(address);
        }
        return clientMultiworld.resolve(address);
    }

    /**
     * Maps the typed address onto the storage namespace that owns its data. Resolution is cached
     * against the address and the companion ids it was resolved with, because this runs every tick
     * while only those inputs can change the answer.
     */
    String canonicalAddress(final String rawAddress) {
        if (aliases == null) {
            return rawAddress;
        }
        final String companionInstanceId = companion.companionInstanceId();
        final String companionWorldId = companion.companionWorldId();
        if (rawAddress.equals(observedAddress)
            && Objects.equals(companionInstanceId, observedCompanionInstanceId)
            && Objects.equals(companionWorldId, observedCompanionWorldId)) {
            return canonicalAddress;
        }
        final ServerAliasResolver.Resolution resolution =
            aliases.resolve(rawAddress, companionInstanceId, companionWorldId);
        observedAddress = rawAddress;
        observedCompanionInstanceId = companionInstanceId;
        observedCompanionWorldId = companionWorldId;
        canonicalAddress = resolution.canonicalId();
        logResolution(rawAddress, resolution);
        return canonicalAddress;
    }

    private void forgetCanonicalAddress() {
        observedAddress = null;
        observedCompanionInstanceId = null;
        observedCompanionWorldId = null;
        canonicalAddress = null;
    }

    private static void logResolution(
        final String rawAddress,
        final ServerAliasResolver.Resolution resolution
    ) {
        switch (resolution.origin()) {
            case LEARNED -> ConfluxMapMod.LOGGER.info(
                "Recognized {} as an address of {} from its companion world id; sharing its map data",
                rawAddress, resolution.canonicalId()
            );
            case CONFLICT -> ConfluxMapMod.LOGGER.info(
                "{} is the same server as {}, but both already store map data; keeping them apart "
                    + "until the data is merged explicitly",
                rawAddress, resolution.mergeTarget()
            );
            case ADOPTED_LEGACY -> ConfluxMapMod.LOGGER.info(
                "Reusing existing map data stored for {} under {}", rawAddress, resolution.canonicalId()
            );
            default -> { }
        }
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
