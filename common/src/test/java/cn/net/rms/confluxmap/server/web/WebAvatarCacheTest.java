package cn.net.rms.confluxmap.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        });

        for (int i = 0; i <= 256; i++) {
            cache.face(skin(i));
        }
        assertEquals(257, loads.get());

        cache.face(skin(0));
        assertEquals(258, loads.get());
        cache.face(skin(256));
        assertEquals(258, loads.get());
    }

    private static URI skin(final int id) {
        return URI.create("https://textures.minecraft.net/texture/" + id);
    }
}
