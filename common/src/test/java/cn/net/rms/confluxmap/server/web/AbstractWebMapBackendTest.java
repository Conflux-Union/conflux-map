package cn.net.rms.confluxmap.server.web;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import cn.net.rms.confluxmap.core.net.MapRegionSyncSubscribeC2S;
import cn.net.rms.confluxmap.core.net.MapViewReqC2S;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AbstractWebMapBackendTest {
    private static final UUID PLAYER_ID =
        UUID.fromString("d1a7b6e0-5c1f-4a2e-9b3d-8e7f6a5b4c3d");
    private static final UUID OTHER_ID =
        UUID.fromString("6f3c2b1a-0d9e-4f7a-8b6c-5d4e3f2a1b0c");
    private static final URI SKIN =
        URI.create("https://textures.minecraft.net/texture/aa");
    private static final URI CHAT_SKIN =
        URI.create("https://textures.minecraft.net/texture/bb");

    @TempDir Path directory;

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
        }, name -> {
            throw new AssertionError("unexpected profile lookup: " + name);
        }), null);

        backend.publish(List.of(player()), List.of(), Map.of(PLAYER_ID, SKIN));
        assertArrayEquals(new byte[] {1}, backend.avatar(PLAYER_ID).join());
        assertEquals(1, loads.get());

        backend.publish(List.of(player()), List.of(), Map.of());
        final CompletableFuture<byte[]> gone = backend.avatar(PLAYER_ID);
        assertNull(gone.join());
        assertEquals(1, loads.get());
    }

    @Test
    void avatarPrefersTheSessionSkinThenTheChatSkinThenThePublicName() {
        final WebMapConfig config = new WebMapConfig();
        config.enableChat = true;
        try (WebMapFeatures features = new WebMapFeatures(
                config, directory, id -> true, () -> true, Runnable::run, message -> {})) {
            features.gameChat(PLAYER_ID, "Steve", "hello", CHAT_SKIN);
            final List<URI> loads = new ArrayList<>();
            final List<String> lookups = new ArrayList<>();
            final Backend backend = new Backend(new WebAvatarCache(uri -> {
                loads.add(uri);
                return CompletableFuture.completedFuture(new byte[] {1});
            }, name -> {
                lookups.add(name);
                return CompletableFuture.completedFuture(null);
            }), features);

            backend.publish(List.of(player(), other()), List.of(), Map.of(PLAYER_ID, SKIN));
            assertArrayEquals(new byte[] {1}, backend.avatar(PLAYER_ID).join());
            assertEquals(List.of(SKIN), loads);

            backend.publish(List.of(player(), other()), List.of(), Map.of());
            assertArrayEquals(new byte[] {1}, backend.avatar(PLAYER_ID).join());
            assertEquals(List.of(SKIN, CHAT_SKIN), loads);

            assertNull(backend.avatar(OTHER_ID).join());
            assertEquals(List.of("alex"), lookups);
        }
    }

    @Test
    void serverNameStripsTheMotdAndCapsItAtEightyCodePoints() {
        assertEquals("My Server", AbstractWebMapBackend.serverName("§aMy §lServer§r", "world"));
        assertEquals("world", AbstractWebMapBackend.serverName("§a§b", "world"));
        assertEquals("world", AbstractWebMapBackend.serverName(null, "world"));
        final String face = new String(Character.toChars(0x1F600));
        assertEquals(face.repeat(80), AbstractWebMapBackend.serverName(face.repeat(85), "world"));
    }

    private static Backend backend() {
        return new Backend(new WebAvatarCache(uri -> {
            throw new AssertionError("unexpected avatar load: " + uri);
        }, name -> {
            throw new AssertionError("unexpected profile lookup: " + name);
        }), null);
    }

    private static WebPlayerSnapshot.Player player() {
        return new WebPlayerSnapshot.Player(PLAYER_ID.toString(), "Steve", 0, 1.5, 2.5, false);
    }

    private static WebPlayerSnapshot.Player other() {
        return new WebPlayerSnapshot.Player(OTHER_ID.toString(), "Alex", 0, 3.5, 4.5, false);
    }

    private static final class Backend extends AbstractWebMapBackend {
        private final WebMapFeatures features;

        Backend(final WebAvatarCache avatars, final WebMapFeatures features) {
            super(avatars);
            this.features = features;
        }

        @Override
        public WebMapFeatures features() {
            return features;
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
