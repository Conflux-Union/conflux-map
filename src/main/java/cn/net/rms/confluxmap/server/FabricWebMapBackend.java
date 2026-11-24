package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.compat.Dimensions;
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
    private final cn.net.rms.confluxmap.server.web.WebMapFeatures features;
    @Override public cn.net.rms.confluxmap.server.web.WebMapFeatures features(){return features;}

    FabricWebMapBackend(final MinecraftServer server, final ConfluxMapCompanion companion) {
        this.server = server;
        this.companion = companion;
        features=new cn.net.rms.confluxmap.server.web.WebMapFeatures(companion.config().webMap,
            companion.webMapImageDirectory(companion.worldIds().get(server).toString()), id -> companion.sharedWaypointsEnabled() && companion.sharedWaypoints().snapshot().waypoints().stream().anyMatch(w -> w.id().equals(id)),
            companion::saveWebMapConfig, server::execute,
            text -> server.getPlayerManager().getPlayerList().forEach(player ->
                cn.net.rms.confluxmap.compat.MinecraftAccess.sendSystemMessage(
                    player, cn.net.rms.confluxmap.compat.Texts.literal(text)
                )
            )
        );
        features.restrictAttachments((actor, id) -> companion.sharedWaypointsEnabled()
            && companion.sharedWaypoints().snapshot().waypoints().stream()
                .anyMatch(waypoint -> waypoint.id().equals(id) && waypoint.publisherId().equals(actor)));
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
            final DimensionId dimensionId = Dimensions.of(world);
            final WorldPreset preset = WorldPresetDetector.detect(world);
            dimensions.add(new WebMapManifest.Dimension(
                index++, dimensionId.toString(), dimensionId.path(),
                PredictionDimensions.supported(dimensionId) && preset.predictable(), preset
            ));
        }
        return new WebMapManifest(
            companion.worldIds().get(server).toString(), worldgenVersion,
            sharePrediction ? server.getOverworld().getSeed() : null,
            sharePrediction ? predictionVersion.getAsInt() : -1,
            dimensions,
            new WebMapManifest.Limits(
                companion.config().maxTilesPerRequest,
                Math.max(
                    companion.config().minRequestIntervalMs,
                    companion.config().webMap.minRequestIntervalMs
                )
            ),
            new WebMapManifest.Presentation(
                companion.instanceId().toString(),
                companion.config().webMap.serverDisplayName.isBlank()
                    ? serverName(server.getServerMotd(), server.getSavePath(net.minecraft.util.WorldSavePath.ROOT).toAbsolutePath().normalize().getFileName().toString()) : companion.config().webMap.serverDisplayName,
                companion.config().webMap.defaultMode,
                companion.config().webMap.sharePlayers && companion.config().webMap.allowPlayerSearch,
                companion.config().webMap.allowWaypointSearch
            )
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
        return new WebPlayerSnapshot(snapshot.playerRevision(), snapshot.players());
    }

    @Override
    public WebMapSnapshot snapshot() {
        return snapshot;
    }

    @Override
    public CompletableFuture<byte[]> avatar(final UUID playerId) {
        URI skin=skinUrls.get(playerId);
        return avatars.face(skin==null?features.chatSkin(playerId):skin);
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

    private static String serverName(String motd,String fallback){String name=motd==null?"":motd.replaceAll("§.","").replaceAll("[\\p{Cc}\\p{Cf}]"," ").strip();if(name.isBlank())return fallback;return name.codePointCount(0,name.length())>80?name.substring(0,name.offsetByCodePoints(0,80)):name;}
    void gameChat(ServerPlayerEntity player,String text){if(!companion.config().webMap.enableChat||!companion.config().webMap.showGameChat)return;features.gameChat(player.getUuid(),player.getName().getString(),text,skin(player));}

    void updatePlayers() {
        final List<WebPlayerSnapshot.Player> result = new ArrayList<>();
        final Map<UUID, URI> currentSkins = new HashMap<>();
        if (companion.config().webMap.sharePlayers) {
            for (final ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                if (companion.webMapHidden(player.getUuid())) continue;
                result.add(new WebPlayerSnapshot.Player(
                    player.getUuid().toString(), player.getName().getString(),
                    Dimensions.indexOf(server, (ServerWorld) player.getServerWorld()),
                    player.getX(), player.getZ(),
                    player.isSpectator() || player.isInvisible()
                ));
                final URI skin = skin(player);
                if (skin != null) currentSkins.put(player.getUuid(), skin);
            }
        }
        final List<WebMapSnapshot.Waypoint> waypoints = new ArrayList<>();
        if (companion.sharedWaypointsEnabled()) {
            for (final SharedWaypoint waypoint : companion.sharedWaypoints().snapshot().waypoints()) {
                final int dimension = Dimensions.indexOf(server, waypoint.dimensionId());
                if (dimension >= 0) {
                    waypoints.add(new WebMapSnapshot.Waypoint(
                        waypoint.id().toString(), waypoint.name(), dimension,
                        waypoint.x(), waypoint.y(), waypoint.z(), waypoint.colorArgb(),
                        waypoint.type().name()
                    ));
                }
            }
        }
        snapshot = snapshot.next(result, waypoints);
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
}
