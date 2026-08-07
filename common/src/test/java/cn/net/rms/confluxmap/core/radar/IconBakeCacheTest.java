package cn.net.rms.confluxmap.core.radar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class IconBakeCacheTest {
    @Test
    void queuesOneMissingIconAndPublishesItAfterBake() {
        final IconBakeCache<String, String> cache = new IconBakeCache<>(2, 200);

        assertTrue(cache.request("wolf", 0).isEmpty());
        assertEquals("wolf", cache.pollNext(0).orElseThrow());
        assertTrue(cache.pollNext(0).isEmpty(), "one request must be consumed only once");

        cache.complete("wolf", "wolf-icon", 0);
        assertEquals("wolf-icon", cache.request("wolf", 1).orElseThrow());
    }

    @Test
    void expiredIconsStayVisibleWhileARefreshIsQueued() {
        final IconBakeCache<String, String> cache = new IconBakeCache<>(2, 200);
        cache.request("frog", 0);
        cache.pollNext(0);
        cache.complete("frog", "temperate", 0);

        assertEquals("temperate", cache.request("frog", 199).orElseThrow());
        assertEquals("temperate", cache.request("frog", 200).orElseThrow());
        assertEquals("frog", cache.pollNext(200).orElseThrow());
    }

    @Test
    void failedRefreshKeepsTheLastPublishedIconDuringBackoff() {
        final IconBakeCache<String, String> cache = new IconBakeCache<>(2, 200);
        cache.request("frog", 0);
        cache.pollNext(0);
        cache.complete("frog", "temperate", 0);
        cache.request("frog", 200);
        cache.pollNext(200);

        cache.fail("frog", 200);

        assertEquals("temperate", cache.request("frog", 201).orElseThrow());
        assertTrue(cache.pollNext(201).isEmpty());
        assertEquals("temperate", cache.request("frog", 400).orElseThrow());
        assertEquals("frog", cache.pollNext(400).orElseThrow());
    }

    @Test
    void failuresBackOffAndLruEvictionReleasesTheOldValue() {
        final IconBakeCache<String, String> cache = new IconBakeCache<>(1, 200);
        cache.request("broken", 0);
        cache.pollNext(0);
        cache.fail("broken", 0);

        cache.request("broken", 199);
        assertTrue(cache.pollNext(199).isEmpty());
        cache.request("broken", 200);
        assertEquals("broken", cache.pollNext(200).orElseThrow());

        cache.complete("broken", "old", 200);
        cache.request("new", 201);
        cache.pollNext(201);
        final var evicted = cache.complete("new", "new-icon", 201);
        assertEquals("old", evicted.orElseThrow());
        assertFalse(cache.request("new", 202).isEmpty());
    }
}
