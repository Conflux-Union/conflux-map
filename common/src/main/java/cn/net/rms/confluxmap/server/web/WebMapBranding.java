package cn.net.rms.confluxmap.server.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.apache.logging.log4j.LogManager;

/** Startup snapshot of the optional icon beside server.json. */
final class WebMapBranding {
    private static final int MAX_ICON_BYTES = 1 << 20;

    record Icon(byte[] body, String contentType) {}

    static Icon loadIcon(final String filename, final Path configDirectory) {
        if (filename.isEmpty()) return null;
        try {
            if (configDirectory == null) {
                throw new IOException("favicon configured without a configuration directory");
            }
            if (filename.equals(".") || filename.equals("..")
                || filename.contains("/") || filename.contains("\\") || filename.contains(":")) {
                throw new IOException("favicon must be a filename beside server.json");
            }
            final Path directory = configDirectory.toRealPath();
            final Path file = directory.resolve(filename).toRealPath();
            if (!directory.equals(file.getParent())) {
                throw new IOException("favicon must stay in the configuration directory");
            }
            final byte[] body;
            try (InputStream input = Files.newInputStream(file)) {
                body = input.readNBytes(MAX_ICON_BYTES + 1);
            }
            if (body.length == 0 || body.length > MAX_ICON_BYTES) {
                throw new IOException("favicon must contain 1 byte to 1 MiB");
            }
            final String lower = filename.toLowerCase(Locale.ROOT);
            final String type;
            if (lower.endsWith(".png") && body.length >= 8
                && body[0] == (byte) 0x89 && body[1] == 'P' && body[2] == 'N'
                && body[3] == 'G' && body[4] == 13 && body[5] == 10
                && body[6] == 26 && body[7] == 10) {
                type = "image/png";
            } else if (lower.endsWith(".ico") && body.length >= 6
                && body[0] == 0 && body[1] == 0 && body[2] == 1 && body[3] == 0
                && ((body[4] & 0xff) != 0 || (body[5] & 0xff) != 0)) {
                type = "image/x-icon";
            } else {
                throw new IOException("favicon must be a PNG or ICO file");
            }
            return new Icon(body, type);
        } catch (final IOException | RuntimeException e) {
            LogManager.getLogger(WebMapBranding.class).warn(
                "[ConfluxMap] Cannot load web-map favicon '{}': {}", filename, e.toString()
            );
            return null;
        }
    }

    static String escapeHtml(final String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private WebMapBranding() {}
}
