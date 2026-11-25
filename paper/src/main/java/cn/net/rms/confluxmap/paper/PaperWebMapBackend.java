package cn.net.rms.confluxmap.paper;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.shared.SharedWaypoint;
import cn.net.rms.confluxmap.core.net.MapRegionSyncSubscribeC2S;
import cn.net.rms.confluxmap.core.net.MapViewReqC2S;
import cn.net.rms.confluxmap.core.predict.PredictionDimensions;
import cn.net.rms.confluxmap.nativepredict.McVersions;
import cn.net.rms.confluxmap.server.MessageSender;
import cn.net.rms.confluxmap.server.web.AbstractWebMapBackend;
import cn.net.rms.confluxmap.server.web.WebMapManifest;
import cn.net.rms.confluxmap.server.web.WebMapSnapshot;
import cn.net.rms.confluxmap.server.web.WebPlayerSnapshot;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

/** Paper main-thread adapter for the shared HTTP transport. */
final class PaperWebMapBackend extends AbstractWebMapBackend {
    private final ConfluxMapPaperPlugin plugin;
    private final PaperCompanion companion;
    private volatile WebMapManifest manifest;
    private volatile String defaultGameMode;
    private final cn.net.rms.confluxmap.server.web.WebMapFeatures features;
    @Override public cn.net.rms.confluxmap.server.web.WebMapFeatures features(){return features;}

    PaperWebMapBackend(final ConfluxMapPaperPlugin plugin, final PaperCompanion companion) {
        this.plugin = plugin;
        this.companion = companion;
        this.defaultGameMode = Bukkit.getDefaultGameMode().name();
        features=new cn.net.rms.confluxmap.server.web.WebMapFeatures(companion.config().webMap,
            companion.webMapImageDirectory(companion.worldId().toString()), id -> companion.sharedWaypointsEnabled() && companion.sharedWaypoints().snapshot().waypoints().stream().anyMatch(w -> w.id().equals(id)),
            companion::saveWebMapConfig, task -> Bukkit.getScheduler().runTask(plugin,task),
            text -> Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(net.kyori.adventure.text.Component.text(text))));
        features.restrictAttachments((actor, id) -> companion.sharedWaypointsEnabled()
            && companion.sharedWaypoints().snapshot().waypoints().stream()
                .anyMatch(waypoint -> waypoint.id().equals(id) && waypoint.publisherId().equals(actor)));
        refreshManifest();
    }

    void refreshManifest() {
        final List<WebMapManifest.Dimension> dimensions = new ArrayList<>();
        for (final PaperWorldDirectory.Entry entry : companion.worlds().entries()) {
            final DimensionId id = DimensionId.parse(entry.dimensionId());
            dimensions.add(new WebMapManifest.Dimension(
                entry.index(), entry.dimensionId(), entry.dimensionType(),
                PredictionDimensions.supported(id) && entry.preset().predictable(),
                entry.preset()
            ));
        }
        final String worldgenVersion = Bukkit.getMinecraftVersion();
        final java.util.OptionalInt predictionVersion = McVersions.toCubiomes(worldgenVersion);
        final boolean sharePrediction = companion.config().shareSeed
            && companion.config().allowBiomeMap
            && predictionVersion.isPresent();
        manifest = new WebMapManifest(
            companion.worldId().toString(), worldgenVersion,
            sharePrediction ? companion.worldSeed() : null,
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
                    ? serverName(Bukkit.getMotd(), Bukkit.getWorlds().get(0).getName()) : companion.config().webMap.serverDisplayName,
                companion.config().webMap.defaultMode,
                companion.config().webMap.sharePlayers && companion.config().webMap.allowPlayerSearch,
                companion.config().webMap.allowWaypointSearch
            )
        );
    }

    @Override
    public WebMapManifest manifest() {
        return manifest;
    }

    @Override
    public void requestTiles(
        final UUID clientId,
        final MapViewReqC2S request,
        final int requestBytes,
        final Consumer<byte[]> response
    ) {
        Bukkit.getScheduler().runTask(plugin, () -> companion.corrections().requestTiles(
            clientId, request, requestBytes, true, sender(response)
        ));
    }

    @Override
    public void subscribeRegions(
        final UUID clientId,
        final MapRegionSyncSubscribeC2S request,
        final Consumer<byte[]> response
    ) {
        Bukkit.getScheduler().runTask(plugin, () -> companion.corrections().subscribeRegions(
            clientId, request, sender(response)
        ));
    }

    @Override
    public void removeClient(final UUID clientId) {
        Bukkit.getScheduler().runTask(plugin, () -> companion.corrections().remove(clientId));
    }

    private static MessageSender sender(
        final Consumer<byte[]> response
    ) {
        return MessageSender.forwardingTo(response);
    }

    void gameChat(Player player,String text){
        if(!companion.config().webMap.enableChat||!companion.config().webMap.showGameChat)return;
        URI skin=null;try{java.net.URL url=player.getPlayerProfile().getTextures().getSkin();if(url!=null)skin=url.toURI();}catch(Exception ignored){}
        features.gameChat(player.getUniqueId(),player.getName(),text,skin);
    }

    @Override
    public String defaultGameMode() {
        return defaultGameMode;
    }

    void updatePlayers() {
        defaultGameMode = Bukkit.getDefaultGameMode().name();
        final List<WebPlayerSnapshot.Player> result = new ArrayList<>();
        final Map<UUID, URI> currentSkins = new HashMap<>();
        if (companion.config().webMap.sharePlayers) {
            for (final Player player : Bukkit.getOnlinePlayers()) {
                if (companion.webMapHidden(player.getUniqueId())) continue;
                final PaperWorldDirectory.Entry world = companion.worlds().find(player.getWorld());
                if (world == null) continue;
                result.add(new WebPlayerSnapshot.Player(
                    player.getUniqueId().toString(), player.getName(), world.index(),
                    player.getX(), player.getZ(),
                    player.getGameMode() == GameMode.SPECTATOR || player.isInvisible()
                ));
                final java.net.URL skin = player.getPlayerProfile().getTextures().getSkin();
                if (skin != null) {
                    final URI texture = cn.net.rms.confluxmap.server.web.WebSkinTexture.officialUri(skin.toExternalForm());
                    if (texture != null) currentSkins.put(player.getUniqueId(), texture);
                }
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
        publish(result, waypoints, currentSkins);
    }

    private int worldIndex(final DimensionId target) {
        for (final PaperWorldDirectory.Entry entry : companion.worlds().entries()) {
            if (DimensionId.parse(entry.dimensionId()).equals(target)) return entry.index();
        }
        return -1;
    }
}
