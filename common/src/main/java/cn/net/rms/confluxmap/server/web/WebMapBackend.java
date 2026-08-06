package cn.net.rms.confluxmap.server.web;

import cn.net.rms.confluxmap.core.net.MapRegionViewReqC2S;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Platform adapter consumed by the transport-only web server. */
public interface WebMapBackend {
    WebMapManifest manifest();

    CompletableFuture<List<byte[]>> requestRegions(
        UUID clientId,
        MapRegionViewReqC2S request,
        int requestBytes
    );

    default void removeClient(final UUID clientId) {
    }

    default WebPlayerSnapshot players() {
        return WebPlayerSnapshot.EMPTY;
    }

    default CompletableFuture<byte[]> avatar(final UUID playerId) {
        return CompletableFuture.completedFuture(null);
    }
}
