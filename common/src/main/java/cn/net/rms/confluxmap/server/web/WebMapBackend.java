package cn.net.rms.confluxmap.server.web;

import cn.net.rms.confluxmap.core.net.MapRegionSyncSubscribeC2S;
import cn.net.rms.confluxmap.core.net.MapViewReqC2S;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Platform adapter consumed by the transport-only web server. */
public interface WebMapBackend {
    WebMapManifest manifest();

    void requestTiles(
        UUID clientId,
        MapViewReqC2S request,
        int requestBytes,
        Consumer<byte[]> response
    );

    void subscribeRegions(
        UUID clientId,
        MapRegionSyncSubscribeC2S request,
        Consumer<byte[]> response
    );

    default void removeClient(final UUID clientId) {
    }

    default WebPlayerSnapshot players() {
        return WebPlayerSnapshot.EMPTY;
    }

    default List<WebMapSnapshot.Waypoint> waypoints() {
        return List.of();
    }

    default WebMapSnapshot snapshot() {
        final WebPlayerSnapshot players = players();
        return new WebMapSnapshot(players.revision(), players.players(), waypoints());
    }

    default CompletableFuture<byte[]> avatar(final UUID playerId) {
        return CompletableFuture.completedFuture(null);
    }
}
