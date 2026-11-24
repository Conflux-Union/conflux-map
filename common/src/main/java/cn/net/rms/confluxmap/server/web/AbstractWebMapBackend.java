package cn.net.rms.confluxmap.server.web;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Published snapshot, player and avatar state shared by the platform web-map backends. */
public abstract class AbstractWebMapBackend implements WebMapBackend {
    private volatile WebMapSnapshot snapshot = WebMapSnapshot.EMPTY;
    private volatile Map<UUID, URI> skinUrls = Map.of();
    private final WebAvatarCache avatars;

    protected AbstractWebMapBackend() {
        this(new WebAvatarCache());
    }

    AbstractWebMapBackend(final WebAvatarCache avatars) {
        this.avatars = avatars;
    }

    @Override
    public final WebPlayerSnapshot players() {
        return new WebPlayerSnapshot(snapshot.playerRevision(), snapshot.players());
    }

    @Override
    public final WebMapSnapshot snapshot() {
        return snapshot;
    }

    @Override
    public final CompletableFuture<byte[]> avatar(final UUID playerId) {
        return avatars.face(skinUrls.get(playerId));
    }

    protected final void publish(
        final List<WebPlayerSnapshot.Player> players,
        final List<WebMapSnapshot.Waypoint> waypoints,
        final Map<UUID, URI> currentSkins
    ) {
        snapshot = snapshot.next(players, waypoints);
        skinUrls = Map.copyOf(currentSkins);
    }
}
