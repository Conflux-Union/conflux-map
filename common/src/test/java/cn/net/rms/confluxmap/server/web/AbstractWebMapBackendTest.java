package cn.net.rms.confluxmap.server.web;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import cn.net.rms.confluxmap.core.net.MapRegionSyncSubscribeC2S;
import cn.net.rms.confluxmap.core.net.MapViewReqC2S;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

final class AbstractWebMapBackendTest {
    private static final UUID PLAYER_ID =
        UUID.fromString("d1a7b6e0-5c1f-4a2e-9b3d-8e7f6a5b4c3d");
    private static final URI SKIN =
        URI.create("https://textures.minecraft.net/texture/aa");

    @Test
    void startsEmpty() {
        final Backend backend = backend();
        assertSame(WebMapSnapshot.EMPTY, backend.snapshot());
        assertEquals(new WebPlayerSnapshot(0L, List.of()), backend.players());
    }

    @Test
    void publishMakesThePlayersVisibleWithANewRevision() {
        final Backend backend = backend();
        backend.publish(List.of(player()), List.of(), Map.of());
        assertEquals(new WebPlayerSnapshot(1L, List.of(player())), backend.players());
        assertEquals(0L, backend.snapshot().waypointRevision());
    }

    @Test
    void publishWithUnchangedContentKeepsTheSnapshotInstance() {
        final Backend backend = backend();
        backend.publish(List.of(player()), List.of(), Map.of());
        final WebMapSnapshot first = backend.snapshot();
        backend.publish(List.of(player()), List.of(), Map.of());
        assertSame(first, backend.snapshot());
    }

    @Test
    void publishReplacesTheSkinTable() {
        final AtomicInteger loads = new AtomicInteger();
        final Backend backend = new Backend(new WebAvatarCache(uri -> {
            loads.incrementAndGet();
            return CompletableFuture.completedFuture(new byte[] {1});
        }));

        backend.publish(List.of(player()), List.of(), Map.of(PLAYER_ID, SKIN));
        assertArrayEquals(new byte[] {1}, backend.avatar(PLAYER_ID).join());
        assertEquals(1, loads.get());

        backend.publish(List.of(player()), List.of(), Map.of());
        final CompletableFuture<byte[]> gone = backend.avatar(PLAYER_ID);
        assertNull(gone.join());
        assertEquals(1, loads.get());
    }

    private static Backend backend() {
        return new Backend(new WebAvatarCache(uri -> {
            throw new AssertionError("unexpected avatar load: " + uri);
        }));
    }

    private static WebPlayerSnapshot.Player player() {
        return new WebPlayerSnapshot.Player(PLAYER_ID.toString(), "Steve", 0, 1.5, 2.5, false);
    }

    private static final class Backend extends AbstractWebMapBackend {
        Backend(final WebAvatarCache avatars) {
            super(avatars);
        }

        @Override
        public WebMapManifest manifest() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void requestTiles(
            final UUID clientId,
            final MapViewReqC2S request,
            final int requestBytes,
            final Consumer<byte[]> response
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void subscribeRegions(
            final UUID clientId,
            final MapRegionSyncSubscribeC2S request,
            final Consumer<byte[]> response
        ) {
            throw new UnsupportedOperationException();
        }
    }
}
