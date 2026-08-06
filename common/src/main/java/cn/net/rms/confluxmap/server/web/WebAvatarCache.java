package cn.net.rms.confluxmap.server.web;

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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;

/** Bounded same-host Minecraft skin fetcher and face-thumbnail cache. */
public final class WebAvatarCache {
    private static final int MAX_SKIN_BYTES = 1 << 20;
    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
    private final Map<URI, CompletableFuture<byte[]>> cache = new ConcurrentHashMap<>();

    public CompletableFuture<byte[]> face(final URI skin) {
        if (!allowed(skin)) return CompletableFuture.completedFuture(null);
        return cache.computeIfAbsent(skin, this::fetch);
    }

    static boolean allowed(final URI uri) {
        return uri != null && "https".equalsIgnoreCase(uri.getScheme())
            && "textures.minecraft.net".equalsIgnoreCase(uri.getHost())
            && uri.getUserInfo() == null && uri.getPort() == -1;
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
