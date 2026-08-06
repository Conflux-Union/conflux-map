package cn.net.rms.confluxmap.server.web;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Decodes the signed Mojang textures property without depending on a platform JSON library. */
public final class WebSkinTexture {
    private static final Pattern URL = Pattern.compile(
        "\\\"url\\\"\\s*:\\s*\\\"(https://textures\\.minecraft\\.net/texture/[A-Za-z0-9]+)\\\""
    );

    private WebSkinTexture() {
    }

    public static URI fromProperty(final String encoded) {
        if (encoded == null || encoded.length() > 32_768) return null;
        try {
            final String json = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            final Matcher match = URL.matcher(json);
            final URI result = match.find() ? URI.create(match.group(1)) : null;
            return WebAvatarCache.allowed(result) ? result : null;
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }
}
