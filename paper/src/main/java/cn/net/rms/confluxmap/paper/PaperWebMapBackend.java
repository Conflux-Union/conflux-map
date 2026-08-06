package cn.net.rms.confluxmap.paper;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.net.CorrectionProfile;
import cn.net.rms.confluxmap.core.net.MapRegionViewReqC2S;
import cn.net.rms.confluxmap.core.net.Message;
import cn.net.rms.confluxmap.core.predict.PredictionDimensions;
import cn.net.rms.confluxmap.server.web.WebMapBackend;
import cn.net.rms.confluxmap.server.web.WebMapManifest;
import cn.net.rms.confluxmap.server.web.WebRegionResponseCollector;
import cn.net.rms.confluxmap.server.web.WebPlayerSnapshot;
import cn.net.rms.confluxmap.server.web.WebAvatarCache;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

/** Paper main-thread adapter for the shared HTTP transport. */
final class PaperWebMapBackend implements WebMapBackend {
    private final ConfluxMapPaperPlugin plugin;
    private final PaperCompanion companion;
    private final WebMapManifest manifest;
    private volatile WebPlayerSnapshot players = WebPlayerSnapshot.EMPTY;
    private volatile Map<UUID, URI> skinUrls = Map.of();
    private final WebAvatarCache avatars = new WebAvatarCache();

    PaperWebMapBackend(final ConfluxMapPaperPlugin plugin, final PaperCompanion companion) {
        this.plugin = plugin;
        this.companion = companion;
        final List<WebMapManifest.Dimension> dimensions = new ArrayList<>();
        for (final PaperWorldDirectory.Entry entry : companion.worlds().entries()) {
            final DimensionId id = DimensionId.parse(entry.dimensionId());
            dimensions.add(new WebMapManifest.Dimension(
                entry.index(), entry.dimensionId(), entry.dimensionType(),
                PredictionDimensions.supported(id) && entry.preset().predictable(),
                entry.preset()
            ));
        }
        manifest = new WebMapManifest(
            companion.worldId().toString(), Bukkit.getMinecraftVersion(), false, dimensions
        );
    }

    @Override
    public WebMapManifest manifest() {
        return manifest;
    }

    @Override
    public CompletableFuture<List<byte[]>> requestRegions(
        final UUID clientId,
        final MapRegionViewReqC2S request,
        final int requestBytes
    ) {
        final WebRegionResponseCollector collector = new WebRegionResponseCollector(
            request.regions().size()
        );
        Bukkit.getScheduler().runTask(plugin, () -> companion.corrections().requestRegions(
            clientId, request, requestBytes, true, CorrectionProfile.SOURCE_LIGHT_V2,
            new PaperCorrectionService.MessageSender() {
                @Override
                public void send(final Message message) {
                    collector.send(message);
                }

                @Override
                public void sendEncoded(final Message message, final byte[] payload) {
                    collector.sendEncoded(message, payload);
                }
            }
        ));
        return collector.future();
    }

    @Override
    public void removeClient(final UUID clientId) {
        Bukkit.getScheduler().runTask(plugin, () -> companion.corrections().remove(clientId));
    }

    @Override
    public WebPlayerSnapshot players() {
        return players;
    }

    @Override
    public CompletableFuture<byte[]> avatar(final UUID playerId) {
        return avatars.face(skinUrls.get(playerId));
    }

    void updatePlayers(final long revision) {
        final List<WebPlayerSnapshot.Player> result = new ArrayList<>();
        final Map<UUID, URI> currentSkins = new HashMap<>();
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
                try {
                    currentSkins.put(player.getUniqueId(), skin.toURI());
                } catch (final URISyntaxException ignored) {
                    // The cache independently rejects non-Minecraft texture origins.
                }
            }
        }
        players = new WebPlayerSnapshot(revision, result);
        skinUrls = Map.copyOf(currentSkins);
    }
}
