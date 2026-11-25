package cn.net.rms.confluxmap.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class WebAvatarCacheTest {
    @Test
    void evictsTheLeastRecentlyUsedFaceAfterTheEntryLimit() {
        final AtomicInteger loads = new AtomicInteger();
        final WebAvatarCache cache = new WebAvatarCache(uri -> {
            loads.incrementAndGet();
            return CompletableFuture.completedFuture(new byte[] {1});
        }, name -> CompletableFuture.completedFuture(null));

        for (int i = 0; i <= 256; i++) {
            cache.face(skin(i));
        }
        assertEquals(257, loads.get());

        cache.face(skin(0));
        assertEquals(258, loads.get());
        cache.face(skin(256));
        assertEquals(258, loads.get());
    }

    @Test
    void reusesPendingAndCachedOfficialLookupsForTheSamePublicPlayer() {
        final AtomicInteger lookups = new AtomicInteger();
        final CompletableFuture<URI> profile = new CompletableFuture<>();
        final WebAvatarCache cache = new WebAvatarCache(
            texture -> CompletableFuture.completedFuture(new byte[] {2}), name -> {
                assertEquals("half_kite", name);
                lookups.incrementAndGet();
                return profile;
            }
        );
        final CompletableFuture<byte[]> first = cache.face(null, "half_Kite");
        assertSame(first, cache.face(null, "half_kite"));
        profile.complete(skin(42));
        assertArrayEquals(new byte[] {2}, first.join());
        assertSame(first, cache.face(null, "HALF_KITE"));
        assertEquals(1, lookups.get());
    }

    @Test
    void prefersSessionSkinAndCachesMissingProfilesWithoutQueryingPrivateNames() {
        final AtomicInteger lookups = new AtomicInteger();
        final WebAvatarCache cache = new WebAvatarCache(
            texture -> CompletableFuture.completedFuture(new byte[] {3}), name -> {
                lookups.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            }
        );
        assertArrayEquals(new byte[] {3}, cache.face(skin(42), "half_kite").join());
        assertNull(cache.face(null, null).join());
        assertNull(cache.face(null, "../half_kite").join());
        assertEquals(0, lookups.get());
        assertNull(cache.face(null, "half_kite").join());
        assertNull(cache.face(null, "HALF_KITE").join());
        assertEquals(1, lookups.get());
    }

    private static URI skin(final int id) {
        return URI.create("https://textures.minecraft.net/texture/" + id);
    }
}
