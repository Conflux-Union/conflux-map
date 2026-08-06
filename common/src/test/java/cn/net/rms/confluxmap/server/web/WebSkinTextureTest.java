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

    private static String encoded(final String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
