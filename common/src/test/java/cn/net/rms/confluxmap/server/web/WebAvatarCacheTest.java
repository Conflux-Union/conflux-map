package cn.net.rms.confluxmap.server.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import org.junit.jupiter.api.Test;

class WebAvatarCacheTest {
    @Test
    void permitsOnlyTheOfficialHttpsTextureOrigin() {
        assertTrue(WebAvatarCache.allowed(URI.create(
            "https://textures.minecraft.net/texture/abc"
        )));
        assertFalse(WebAvatarCache.allowed(URI.create(
            "http://textures.minecraft.net/texture/abc"
        )));
        assertFalse(WebAvatarCache.allowed(URI.create(
            "https://textures.minecraft.net.attacker.invalid/texture/abc"
        )));
        assertFalse(WebAvatarCache.allowed(URI.create("https://127.0.0.1/skin.png")));
    }
}
