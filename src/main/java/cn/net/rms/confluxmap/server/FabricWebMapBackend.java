package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.compat.MinecraftVersion;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.shared.SharedWaypoint;
import cn.net.rms.confluxmap.core.net.MapRegionSyncSubscribeC2S;
import cn.net.rms.confluxmap.core.net.MapViewReqC2S;
import cn.net.rms.confluxmap.core.net.Message;
import cn.net.rms.confluxmap.core.net.MsgCodec;
import cn.net.rms.confluxmap.core.net.ProtoException;
import cn.net.rms.confluxmap.core.predict.PredictionDimensions;
import cn.net.rms.confluxmap.core.predict.WorldPreset;
import cn.net.rms.confluxmap.nativepredict.McVersions;
import cn.net.rms.confluxmap.server.web.WebMapBackend;
import cn.net.rms.confluxmap.server.web.WebMapManifest;
import cn.net.rms.confluxmap.server.web.WebMapSnapshot;
import cn.net.rms.confluxmap.server.web.WebPlayerSnapshot;
import cn.net.rms.confluxmap.server.web.WebAvatarCache;
import cn.net.rms.confluxmap.server.web.WebSkinTexture;
import java.net.URI;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.network.ServerPlayerEntity;

/** Fabric main-thread adapter for the shared HTTP transport. */
final class FabricWebMapBackend implements WebMapBackend {
    private final MinecraftServer server;
    private final ConfluxMapCompanion companion;
    private final WebMapManifest manifest;
    private volatile WebMapSnapshot snapshot = WebMapSnapshot.EMPTY;
    private volatile Map<UUID, URI> skinUrls = Map.of();
    private final WebAvatarCache avatars = new WebAvatarCache();

    FabricWebMapBackend(final MinecraftServer server, final ConfluxMapCompanion companion) {
        this.server = server;
        this.companion = companion;
        this.manifest = buildManifest();
    }

    @Override
    public WebMapManifest manifest() {
        return manifest;
    }

    private WebMapManifest buildManifest() {
        final String worldgenVersion = MinecraftVersion.current();
        final java.util.OptionalInt predictionVersion = McVersions.toCubiomes(worldgenVersion);
        final boolean sharePrediction = companion.config().shareSeed
            && companion.config().allowBiomeMap
            && predictionVersion.isPresent();
        final List<WebMapManifest.Dimension> dimensions = new ArrayList<>();
        int index = 0;
        for (final ServerWorld world : server.getWorlds()) {
            final String id = world.getRegistryKey().getValue().toString();
            final DimensionId dimensionId = DimensionId.parse(id);
            final WorldPreset preset = WorldPresetDetector.detect(world);
            dimensions.add(new WebMapManifest.Dimension(
                index++, id, world.getRegistryKey().getValue().getPath(),
                PredictionDimensions.supported(dimensionId) && preset.predictable(), preset
            ));
        }
        return new WebMapManifest(
            companion.worldIds().get(server).toString(), worldgenVersion,
            sharePrediction ? server.getOverworld().getSeed() : null,
            sharePrediction ? predictionVersion.getAsInt() : -1,
            dimensions
        );
    }

    @Override
    public void requestTiles(
        final UUID clientId,
        final MapViewReqC2S request,
        final int requestBytes,
        final Consumer<byte[]> response
    ) {
        server.execute(() -> companion.summaries().request(
            server, clientId, request, requestBytes, true, sender(response)
        ));
    }

    @Override
    public void subscribeRegions(
        final UUID clientId,
        final MapRegionSyncSubscribeC2S request,
        final Consumer<byte[]> response
    ) {
        server.execute(() -> companion.summaries().subscribeRegions(
            server, clientId, request, sender(response)
        ));
    }

    @Override
    public void removeClient(final UUID clientId) {
        server.execute(() -> companion.summaries().remove(clientId));
    }

    @Override
    public WebPlayerSnapshot players() {
        return new WebPlayerSnapshot(snapshot.revision(), snapshot.players());
    }

    @Override
    public WebMapSnapshot snapshot() {
        return snapshot;
    }

    @Override
    public CompletableFuture<byte[]> avatar(final UUID playerId) {
        return avatars.face(skinUrls.get(playerId));
    }

    private static RegionSummaryService.MessageSender sender(
        final Consumer<byte[]> response
    ) {
        return new RegionSummaryService.MessageSender() {
            @Override
            public void send(final Message message) {
                try {
                    response.accept(MsgCodec.encode(message));
                } catch (final ProtoException ignored) {
                    // Every server-originated message is validated before it reaches this seam.
                }
            }

            @Override
            public void sendEncoded(final Message message, final byte[] payload) {
                response.accept(payload);
            }
        };
    }

    void updatePlayers(final long revision) {
        final List<WebPlayerSnapshot.Player> result = new ArrayList<>();
        final Map<UUID, URI> currentSkins = new HashMap<>();
        if (companion.config().webMap.sharePlayers) {
            for (final ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                if (companion.webMapHidden(player.getUuid())) continue;
                result.add(new WebPlayerSnapshot.Player(
                    player.getUuid().toString(), player.getName().getString(),
                    worldIndex(player.getServerWorld()), player.getX(), player.getZ(),
                    player.isSpectator() || player.isInvisible()
                ));
                final URI skin = skin(player);
                if (skin != null) currentSkins.put(player.getUuid(), skin);
            }
        }
        final List<WebMapSnapshot.Waypoint> waypoints = new ArrayList<>();
        if (companion.sharedWaypointsEnabled()) {
            for (final SharedWaypoint waypoint : companion.sharedWaypoints().snapshot().waypoints()) {
                final int dimension = worldIndex(waypoint.dimensionId());
                if (dimension >= 0) {
                    waypoints.add(new WebMapSnapshot.Waypoint(
                        waypoint.id().toString(), waypoint.name(), dimension,
                        waypoint.x(), waypoint.y(), waypoint.z(), waypoint.colorArgb(),
                        waypoint.type().name()
                    ));
                }
            }
        }
        snapshot = new WebMapSnapshot(revision, result, waypoints);
        skinUrls = Map.copyOf(currentSkins);
    }

    private static URI skin(final ServerPlayerEntity player) {
        try {
            final Object profile = player.getGameProfile();
            Method propertiesAccessor;
            try {
                propertiesAccessor = profile.getClass().getMethod("properties");
            } catch (final NoSuchMethodException ignored) {
                propertiesAccessor = profile.getClass().getMethod("getProperties");
            }
            final Object properties = propertiesAccessor.invoke(profile);
            final Object values = properties.getClass().getMethod("get", Object.class)
                .invoke(properties, "textures");
            for (final Object property : (Iterable<?>) values) {
                Method accessor;
                try {
                    accessor = property.getClass().getMethod("value");
                } catch (final NoSuchMethodException ignored) {
                    accessor = property.getClass().getMethod("getValue");
                }
                return WebSkinTexture.fromProperty((String) accessor.invoke(property));
            }
        } catch (final ReflectiveOperationException | ClassCastException ignored) {
            // Authlib changed GameProfile and Property from beans to records across versions.
        }
        return null;
    }

    private int worldIndex(final ServerWorld target) {
        int index = 0;
        for (final ServerWorld world : server.getWorlds()) {
            if (world == target) return index;
            index++;
        }
        return -1;
    }

    private int worldIndex(final DimensionId target) {
        int index = 0;
        for (final ServerWorld world : server.getWorlds()) {
            if (DimensionId.parse(world.getRegistryKey().getValue().toString()).equals(target)) return index;
            index++;
        }
        return -1;
    }
}
