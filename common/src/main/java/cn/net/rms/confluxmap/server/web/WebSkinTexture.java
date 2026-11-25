package cn.net.rms.confluxmap.server.web;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Reads the skin, rather than other textures such as a cape, from a Minecraft profile. */
public final class WebSkinTexture {
    private WebSkinTexture() {
    }

    public static URI fromProperty(final String encoded) {
        if (encoded == null || encoded.length() > 32_768) return null;
        try {
            final String json = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            if (JsonParser.parseString(json) instanceof final JsonObject profile
                && profile.get("textures") instanceof final JsonObject textures
                && textures.get("SKIN") instanceof final JsonObject skin
                && skin.get("url") instanceof final com.google.gson.JsonPrimitive url && url.isString()) {
                return officialUri(url.getAsString());
            }
        } catch (final IllegalArgumentException | JsonParseException invalid) {
            return null;
        }
        return null;
    }

    public static URI officialUri(final String url) {
        try {
            // Older profiles can advertise HTTP. Always download the official texture over TLS.
            final URI uri = URI.create(url.startsWith("http://") ? "https://" + url.substring(7) : url);
            return WebAvatarCache.allowed(uri) ? uri : null;
        } catch (final IllegalArgumentException invalid) {
            return null;
        }
    }
}
