package cn.net.rms.confluxmap.core.waypoint;

import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.config.CrossWorldSeedObservations;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldProfile;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldProfileRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import org.apache.logging.log4j.Logger;

/**
 * Read-only view of the waypoints owned by the current world's seed siblings: other world
 * namespaces under the same server address whose observed hashed seed matches the current
 * session's. Same-seed worlds share terrain, so sibling coordinates are exact in the matching
 * dimension and obey the usual portal-linked conversion otherwise.
 *
 * <p>Called once per session change (after {@link WaypointService} has loaded the current store).
 * It records the session's hashed seed into {@code ConfluxConfig.crossWorldSeedObservations}
 * — companion-instance namespaces are absent from client profile bindings, so the config log is
 * what covers them — then enumerates siblings from both sources and loads each sibling's waypoint
 * file with {@link WaypointIo}. Death points stay home: they mean "where I died in this
 * sub-world", not a place to navigate to from a sibling.
 */
public final class CrossWorldWaypointService {
    private final Path waypointRoot;
    private final ClientWorldProfileRegistry profiles;
    private final ConfluxConfig config;
    private final Runnable configSaver;
    private final Logger logger;

    private volatile List<SiblingWaypoint> siblings = List.of();

    public CrossWorldWaypointService(
        final Path waypointRoot,
        final ClientWorldProfileRegistry profiles,
        final ConfluxConfig config,
        final Runnable configSaver,
        final Logger logger
    ) {
        this.waypointRoot = Objects.requireNonNull(waypointRoot, "waypointRoot");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.config = Objects.requireNonNull(config, "config");
        this.configSaver = Objects.requireNonNull(configSaver, "configSaver");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** Main thread only: session listener, run after the current world's own store has loaded. */
    public void update(final WorldIdentity current, final OptionalLong seedHash) {
        siblings = List.of();
        if (current == null || !current.isPresent()
            || current.isSingleplayer() || seedHash == null || seedHash.isEmpty()) {
            return;
        }
        final long hash = seedHash.getAsLong();
        if (config.crossWorldSeedObservations.record(current, hash)) {
            configSaver.run();
        }
        siblings = loadSiblings(current, hash);
    }

    /** Current session's sibling waypoints; empty between sessions and when no seed is known. */
    public List<SiblingWaypoint> siblings() {
        return siblings;
    }

    private List<SiblingWaypoint> loadSiblings(final WorldIdentity current, final long hash) {
        final String serverId = current.serverId();
        final Map<WorldIdentity, String> candidates = new LinkedHashMap<>();
        for (final ClientWorldProfile profile : profiles.profiles(serverId)) {
            if (profile.matchesSeed(hash)) {
                candidates.putIfAbsent(
                    WorldIdentity.multiplayer(serverId, profile.storageId()),
                    profile.displayName()
                );
            }
        }
        for (final Map.Entry<String, CrossWorldSeedObservations.Entry> observed
            : config.crossWorldSeedObservations.observations(serverId).entrySet()) {
            if (observed.getValue().seedHash() == hash) {
                final WorldIdentity sibling = WorldIdentity.multiplayer(serverId, observed.getKey());
                candidates.putIfAbsent(
                    sibling,
                    labelFor(serverId, observed.getKey(), observed.getValue().nameKeys())
                );
            }
        }
        candidates.remove(current);
        for (final String legacyId : current.legacyStorageIds()) {
            candidates.remove(WorldIdentity.multiplayer(serverId, legacyId));
        }

        final List<SiblingWaypoint> loaded = new ArrayList<>();
        for (final Map.Entry<WorldIdentity, String> candidate : candidates.entrySet()) {
            final Path file = fileFor(candidate.getKey());
            if (!Files.isRegularFile(file)) {
                continue;
            }
            for (final Waypoint waypoint : WaypointIo.load(file, logger)) {
                if (waypoint.type != Waypoint.Type.DEATH) {
                    loaded.add(new SiblingWaypoint(waypoint, candidate.getValue()));
                }
            }
        }
        loaded.sort(Comparator
            .comparing((SiblingWaypoint sibling) -> sibling.worldLabel())
            .thenComparing(sibling -> sibling.waypoint().name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(loaded);
    }

    /**
     * Player-given names for companion worlds are keyed by the world UUID, which only travels
     * inside {@link WorldIdentity#legacyStorageIds()} - so try every name key the observation
     * recorded before falling back to a truncated storage id. Client profiles keep their own
     * {@link ClientWorldProfile#displayName()} and never reach this fallback for named worlds.
     */
    private String labelFor(
        final String serverId,
        final String storageId,
        final List<String> nameKeys
    ) {
        for (final String key : nameKeys) {
            final Optional<String> named = profiles.serverWorldName(serverId, key);
            if (named.isPresent()) {
                return named.get();
            }
        }
        return storageId.length() <= 8 ? storageId : storageId.substring(0, 8);
    }

    private Path fileFor(final WorldIdentity world) {
        return waypointRoot.resolve(world.serverId()).resolve(world.worldId() + ".json");
    }
}
