package cn.net.rms.confluxmap.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class WebSkinTextureTest {
    @Test
    void extractsOnlyOfficialTextureUrls() {
        assertEquals(
            URI.create("https://textures.minecraft.net/texture/abc123"),
            WebSkinTexture.fromProperty(encoded(
                "{\"textures\":{\"SKIN\":{\"url\":\"https://textures.minecraft.net/texture/abc123\"}}}"
            ))
        );
        assertNull(WebSkinTexture.fromProperty(encoded(
            "{\"url\":\"https://example.invalid/skin.png\"}"
        )));
        assertNull(WebSkinTexture.fromProperty("not-base64"));
    }

    @Test
    void upgradesOfficialHttpSkinUrlsToHttps() {
        final String hash = "33e1dc683c1a410d352cdce543744796bdc2b2725f9c9110a66f8e73aa92ba1f";
        assertEquals(
            URI.create("https://textures.minecraft.net/texture/" + hash),
            WebSkinTexture.fromProperty(encoded(
                "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/"
                    + hash + "\"}}}"
            ))
        );
    }

    @Test
    void rejectsNonOfficialAndMalformedTextureUrls() {
        for (final String url : new String[] {
            "http://example.invalid/texture/abc123",
            "https://textures.minecraft.net.evil.invalid/texture/abc123",
            "http://textures.minecraft.net@evil.invalid/texture/abc123",
            "http://textures.minecraft.net:8080/texture/abc123",
            "http://textures.minecraft.net/texture/abc123?redirect=evil",
            "ftp://textures.minecraft.net/texture/abc123"
        }) {
            assertNull(WebSkinTexture.fromProperty(encoded("{\"url\":\"" + url + "\"}")), url);
        }
    }

    private static String encoded(final String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
