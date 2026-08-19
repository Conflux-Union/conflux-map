package cn.net.rms.confluxmap.mc.world;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.core.annotation.AnnotationService;
import cn.net.rms.confluxmap.core.cache.RegionCacheService;
import cn.net.rms.confluxmap.core.color.DaylightModel;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.predict.PredictionTileService;
import cn.net.rms.confluxmap.core.store.MapWorldService;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.tile.TileService;
import cn.net.rms.confluxmap.core.waypoint.WaypointService;
import cn.net.rms.confluxmap.mc.render.TileTextureManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Read-only tile stack used while the fullscreen map views a non-live dimension or sub-world. */
public final class FullscreenMapBrowseService {
    public record Target(WorldIdentity world, DimensionId dimension) {
        public Target {
            if (world == null || dimension == null) {
                throw new IllegalArgumentException("map browse target must be complete");
            }
        }

        public boolean matches(final SessionGuard.Session session) {
            return session.active()
                && world.equals(session.world())
                && dimension.equals(session.dimension());
        }
    }

    private final Path cacheRoot;
    private final MapWorldService mapWorlds = new MapWorldService();
    private final TileService tiles;
    private final RegionCacheService cache;
    private final TileTextureManager textures;
    private final WaypointService waypoints;
    private final AnnotationService annotations;
    private SessionGuard.Session session = SessionGuard.Session.NONE;
    private long nextToken = Long.MIN_VALUE;

    public FullscreenMapBrowseService(
        final Path cacheRoot,
        final Path waypointRoot,
        final Path annotationRoot,
        final MapExecutors executors,
        final ConfluxConfig config,
        final DaylightModel daylightModel,
        final PredictionTileService predictionTiles
    ) {
        this.cacheRoot = cacheRoot;
        tiles = new TileService(mapWorlds, executors, config, daylightModel);
        cache = new RegionCacheService(
            cacheRoot, mapWorlds, executors, tiles, ConfluxMapMod.LOGGER, true
        );
        tiles.bindRegionCache(cache);
        textures = new TileTextureManager(config, tiles, predictionTiles, daylightModel);
        waypoints = new WaypointService(waypointRoot, executors, ConfluxMapMod.LOGGER);
        annotations = new AnnotationService(annotationRoot, executors, ConfluxMapMod.LOGGER);
    }

    public void select(final Target target, final SessionGuard.Session liveSession) {
        if (target.matches(liveSession)) {
            clear();
            return;
        }
        final SessionGuard.Session next = new SessionGuard.Session(
            nextToken++, target.world(), target.dimension()
        );
        cache.onSessionChanged(next);
        tiles.onSessionChanged(next);
        final SessionGuard.Session contentSession = contentSession(next, liveSession);
        waypoints.onSessionChanged(contentSession);
        annotations.onSessionChanged(contentSession);
        textures.releaseAll();
        session = next;
    }

    public void clear() {
        if (!session.active()) {
            return;
        }
        cache.onSessionChanged(SessionGuard.Session.NONE);
        tiles.onSessionChanged(SessionGuard.Session.NONE);
        waypoints.onSessionChanged(SessionGuard.Session.NONE);
        annotations.onSessionChanged(SessionGuard.Session.NONE);
        textures.releaseAll();
        session = SessionGuard.Session.NONE;
    }

    public SessionGuard.Session displayedSession(final SessionGuard.Session liveSession) {
        return session.active() ? session : liveSession;
    }

    public boolean browsing() {
        return session.active();
    }

    public MapWorldService mapWorlds() {
        return mapWorlds;
    }

    public TileService tiles() {
        return tiles;
    }

    public TileTextureManager textures() {
        return textures;
    }

    public WaypointService waypoints() {
        return waypoints;
    }

    public AnnotationService annotations() {
        return annotations;
    }

    static SessionGuard.Session contentSession(
        final SessionGuard.Session viewed,
        final SessionGuard.Session live
    ) {
        return viewed.active() && !viewed.world().equals(live.world())
            ? viewed
            : SessionGuard.Session.NONE;
    }

    /** Current target first, followed by cached/known dimensions in stable insertion order. */
    public List<Target> targets(
        final SessionGuard.Session liveSession,
        final List<WorldIdentity> knownWorlds,
        final List<DimensionId> knownDimensions
    ) {
        return targets(cacheRoot, liveSession, knownWorlds, knownDimensions);
    }

    public List<WorldIdentity> worlds(
        final SessionGuard.Session liveSession,
        final List<WorldIdentity> knownWorlds
    ) {
        return worlds(cacheRoot, liveSession, knownWorlds);
    }

    public List<DimensionId> dimensions(
        final SessionGuard.Session liveSession,
        final WorldIdentity world,
        final List<DimensionId> knownDimensions
    ) {
        return dimensions(cacheRoot, liveSession, world, knownDimensions);
    }

    static List<Target> targets(
        final Path cacheRoot,
        final SessionGuard.Session liveSession,
        final List<WorldIdentity> knownWorlds,
        final List<DimensionId> knownDimensions
    ) {
        if (!liveSession.active()) {
            return List.of();
        }
        final List<Target> result = new ArrayList<>();
        result.add(new Target(liveSession.world(), liveSession.dimension()));
        for (final WorldIdentity world : worlds(cacheRoot, liveSession, knownWorlds)) {
            for (final DimensionId dimension : dimensions(
                cacheRoot, liveSession, world, knownDimensions
            )) {
                final Target target = new Target(world, dimension);
                if (result.contains(target)) {
                    continue;
                }
                result.add(target);
            }
        }
        return List.copyOf(result);
    }

    static List<WorldIdentity> worlds(
        final Path cacheRoot,
        final SessionGuard.Session liveSession,
        final List<WorldIdentity> knownWorlds
    ) {
        if (!liveSession.active()) {
            return List.of();
        }
        final Set<WorldIdentity> result = new LinkedHashSet<>();
        result.add(liveSession.world());
        final Set<WorldIdentity> candidates = new LinkedHashSet<>(knownWorlds);
        candidates.addAll(discoverWorlds(cacheRoot, liveSession.world().serverId()));
        candidates.stream()
            .filter(world -> hasCachedWorld(cacheRoot, world))
            .forEach(result::add);
        return List.copyOf(result);
    }

    static List<DimensionId> dimensions(
        final Path cacheRoot,
        final SessionGuard.Session liveSession,
        final WorldIdentity world,
        final List<DimensionId> knownDimensions
    ) {
        if (!liveSession.active()) {
            return List.of();
        }
        final Set<DimensionId> candidates = new LinkedHashSet<>();
        candidates.add(liveSession.dimension());
        candidates.add(DimensionId.OVERWORLD);
        candidates.add(DimensionId.NETHER);
        candidates.add(DimensionId.END);
        candidates.addAll(knownDimensions);
        if (world.equals(liveSession.world())) {
            return List.copyOf(candidates);
        }
        return candidates.stream()
            .filter(dimension -> hasCachedDimension(
                cacheRoot, new Target(world, dimension)
            ))
            .toList();
    }

    private static List<WorldIdentity> discoverWorlds(
        final Path cacheRoot,
        final String serverId
    ) {
        final Path serverRoot = cacheRoot.resolve(serverId);
        if (!Files.isDirectory(serverRoot)) {
            return List.of();
        }
        try (var children = Files.list(serverRoot)) {
            return children
                .filter(Files::isDirectory)
                .map(Path::getFileName)
                .filter(name -> name != null)
                .map(Path::toString)
                .sorted()
                .map(worldId -> new WorldIdentity(serverId, worldId))
                .toList();
        } catch (final IOException | SecurityException error) {
            ConfluxMapMod.LOGGER.warn("Could not list map cache worlds under {}", serverRoot, error);
            return List.of();
        }
    }

    private static boolean hasCachedDimension(final Path cacheRoot, final Target target) {
        return Files.isDirectory(
            cacheRoot.resolve(target.world().serverId())
                .resolve(target.world().worldId())
                .resolve(target.dimension().fileName())
        );
    }

    private static boolean hasCachedWorld(final Path cacheRoot, final WorldIdentity world) {
        final Path worldRoot = cacheRoot.resolve(world.serverId()).resolve(world.worldId());
        if (!Files.isDirectory(worldRoot)) {
            return false;
        }
        try (var children = Files.list(worldRoot)) {
            return children.anyMatch(Files::isDirectory);
        } catch (final IOException | SecurityException error) {
            ConfluxMapMod.LOGGER.warn("Could not inspect map cache world {}", worldRoot, error);
            return false;
        }
    }
}
