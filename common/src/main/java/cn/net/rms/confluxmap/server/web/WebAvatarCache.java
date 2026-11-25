package cn.net.rms.confluxmap.server.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import javax.imageio.ImageIO;

/** Bounded same-host Minecraft skin fetcher and face-thumbnail cache. */
public final class WebAvatarCache {
    private static final int MAX_SKIN_BYTES = 1 << 20;
    private static final int MAX_CACHE_ENTRIES = 256;
    private static final long PROFILE_TTL_NANOS = Duration.ofMinutes(15).toNanos();
    private static final long MISSING_PROFILE_TTL_NANOS = Duration.ofMinutes(1).toNanos();
    private static final Pattern PLAYER_NAME = Pattern.compile("[a-z0-9_]{1,16}");
    private static final Pattern PROFILE_ID = Pattern.compile("[a-fA-F0-9]{32}");

    private record ProfileFace(long started, CompletableFuture<byte[]> result) {
        boolean reusable(final long now) {
            if (!result.isDone()) return true;
            return now - started < (result.getNow(null) == null ? MISSING_PROFILE_TTL_NANOS : PROFILE_TTL_NANOS);
        }
    }
    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
    private final Function<URI, CompletableFuture<byte[]>> loader;
    private final Function<String, CompletableFuture<URI>> profiles;
    private final Map<String, ProfileFace> profileFaces = new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<String, ProfileFace> eldest) {
            return size() > MAX_CACHE_ENTRIES;
        }
    };
    private final Map<URI, CompletableFuture<byte[]>> cache = new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(
            final Map.Entry<URI, CompletableFuture<byte[]>> eldest
        ) {
            return size() > MAX_CACHE_ENTRIES;
        }
    };

    public WebAvatarCache() {
        this.loader = this::fetch;
        this.profiles = this::findOfficialSkin;
    }

    WebAvatarCache(final Function<URI, CompletableFuture<byte[]>> loader,
                   final Function<String, CompletableFuture<URI>> profiles) {
        this.loader = loader;
        this.profiles = profiles;
    }

    public CompletableFuture<byte[]> face(final URI skin) {
        if (!allowed(skin)) return CompletableFuture.completedFuture(null);
        synchronized (cache) {
            final CompletableFuture<byte[]> cached = cache.get(skin);
            if (cached != null) return cached;
            final CompletableFuture<byte[]> pending = loader.apply(skin);
            cache.put(skin, pending);
            pending.whenComplete((result, error) -> {
                if (result == null || error != null) {
                    synchronized (cache) {
                        cache.remove(skin, pending);
                    }
                }
            });
            return pending;
        }
    }

    /** The fallback name must come from the server's public player snapshot. */
    public CompletableFuture<byte[]> face(final URI skin, final String publicPlayerName) {
        if (allowed(skin) || publicPlayerName == null) return face(skin);
        final String name = publicPlayerName.toLowerCase(Locale.ROOT);
        if (!PLAYER_NAME.matcher(name).matches()) return CompletableFuture.completedFuture(null);
        synchronized (profileFaces) {
            final long now = System.nanoTime();
            final ProfileFace previous = profileFaces.get(name);
            if (previous != null && previous.reusable(now)) return previous.result();
            final CompletableFuture<byte[]> pending = profiles.apply(name)
                .thenCompose(this::face)
                .completeOnTimeout(null, 8L, TimeUnit.SECONDS)
                .exceptionally(error -> null);
            profileFaces.put(name, new ProfileFace(now, pending));
            return pending;
        }
    }

    private CompletableFuture<URI> findOfficialSkin(final String name) {
        final URI identity = URI.create("https://api.minecraftservices.com/minecraft/profile/lookup/name/" + name);
        return fetchProfile(identity).thenCompose(profile -> {
            if (profile == null || !profile.has("id")) return CompletableFuture.completedFuture(null);
            final String id = profile.get("id").getAsString();
            if (!PROFILE_ID.matcher(id).matches()) return CompletableFuture.completedFuture(null);
            final URI session = URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + id);
            return fetchProfile(session).thenApply(WebAvatarCache::skinFromProfile);
        });
    }

    private CompletableFuture<JsonObject> fetchProfile(final URI uri) {
        final HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).GET().build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()).thenApply(response -> {
            if (response.statusCode() != 200 || response.body().length > 65_536) return null;
            return JsonParser.parseString(new String(response.body(), java.nio.charset.StandardCharsets.UTF_8))
                .getAsJsonObject();
        });
    }

    static URI skinFromProfile(final JsonObject profile) {
        if (profile == null || !(profile.get("properties") instanceof final JsonArray properties)) return null;
        for (final var entry : properties) {
            if (entry instanceof final JsonObject property
                && property.has("name") && "textures".equals(property.get("name").getAsString())
                && property.has("value")) {
                final URI skin = WebSkinTexture.fromProperty(property.get("value").getAsString());
                if (skin != null) return skin;
            }
        }
        return null;
    }

    static boolean allowed(final URI uri) {
        return uri != null && "https".equalsIgnoreCase(uri.getScheme())
            && "textures.minecraft.net".equalsIgnoreCase(uri.getHost())
            && uri.getUserInfo() == null && uri.getPort() == -1
            && uri.getRawQuery() == null && uri.getRawFragment() == null
            && uri.getRawPath().matches("/texture/[A-Za-z0-9]+");
    }

    private CompletableFuture<byte[]> fetch(final URI skin) {
        final HttpRequest request = HttpRequest.newBuilder(skin)
            .timeout(Duration.ofSeconds(10)).GET().build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
            .thenApply(response -> response.statusCode() == 200
                && response.body().length <= MAX_SKIN_BYTES
                    ? crop(response.body()) : null)
            .exceptionally(ignored -> null);
    }

    private static byte[] crop(final byte[] png) {
        try {
            final BufferedImage skin = ImageIO.read(new ByteArrayInputStream(png));
            if (skin == null || skin.getWidth() < 48 || skin.getHeight() < 16
                || skin.getWidth() > 1024 || skin.getHeight() > 1024) return null;
            final int scale = skin.getWidth() / 64;
            if (scale < 1 || skin.getWidth() % 64 != 0) return null;
            final BufferedImage face = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
            final Graphics2D graphics = face.createGraphics();
            try {
                graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                );
                graphics.drawImage(
                    skin, 0, 0, 64, 64,
                    8 * scale, 8 * scale, 16 * scale, 16 * scale, null
                );
                graphics.setComposite(AlphaComposite.SrcOver);
                graphics.drawImage(
                    skin, 0, 0, 64, 64,
                    40 * scale, 8 * scale, 48 * scale, 16 * scale, null
                );
            } finally {
                graphics.dispose();
            }
            final ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
            return ImageIO.write(face, "png", out) ? out.toByteArray() : null;
        } catch (final IOException | RuntimeException e) {
            return null;
        }
    }
}
