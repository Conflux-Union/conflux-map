package cn.net.rms.confluxmap.server.web;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Published snapshot, player and avatar state shared by the platform web-map backends, and the server-name helper. */
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
        final URI skin = skinUrls.get(playerId);
        final WebMapFeatures features = features();
        return features == null ? avatars.face(skin)
            : avatars.face(skin == null ? features.chatSkin(playerId) : skin,
                snapshot.publicPlayerName(playerId));
    }

    protected final void publish(
        final List<WebPlayerSnapshot.Player> players,
        final List<WebMapSnapshot.Waypoint> waypoints,
        final Map<UUID, URI> currentSkins
    ) {
        snapshot = snapshot.next(players, waypoints);
        skinUrls = Map.copyOf(currentSkins);
    }

    protected static String serverName(final String motd, final String fallback) {
        final String name = motd == null ? ""
            : motd.replaceAll("§.", "").replaceAll("[\\p{Cc}\\p{Cf}]", " ").strip();
        if (name.isBlank()) {
            return fallback;
        }
        return name.codePointCount(0, name.length()) > 80
            ? name.substring(0, name.offsetByCodePoints(0, 80)) : name;
    }
}
