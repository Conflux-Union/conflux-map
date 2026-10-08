package cn.net.rms.confluxmap.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WebMapBrandingTest {
    @TempDir Path directory;

    @Test
    void acceptsIcoAndRejectsPathsUrlsAndUnsupportedFiles() throws Exception {
        Files.write(directory.resolve("favicon.ico"), new byte[] {0, 0, 1, 0, 1, 0, 0});
        assertEquals("image/x-icon", WebMapBranding.loadIcon("favicon.ico", directory).contentType());
        for (final String filename : new String[] {
            "../favicon.ico", "/favicon.ico", "folder/favicon.ico", "folder\\favicon.ico",
            "https://example.com/favicon.ico", ".", ".."
        }) {
            assertNull(WebMapBranding.loadIcon(filename, directory), filename);
        }
        Files.writeString(directory.resolve("favicon.png"), "not a PNG");
        assertNull(WebMapBranding.loadIcon("favicon.png", directory));
        Files.writeString(directory.resolve("favicon.svg"), "<svg/>");
        assertNull(WebMapBranding.loadIcon("favicon.svg", directory));
        Files.write(directory.resolve("large.ico"), new byte[(1 << 20) + 1]);
        assertNull(WebMapBranding.loadIcon("large.ico", directory));
    }

    @Test
    void rejectsSymlinksOutsideTheConfigurationDirectory() throws Exception {
        final Path outside = Files.createDirectory(directory.resolve("outside"));
        Files.write(outside.resolve("favicon.ico"), new byte[] {0, 0, 1, 0, 1, 0, 0});
        Files.createSymbolicLink(directory.resolve("favicon.ico"), outside.resolve("favicon.ico"));
        assertNull(WebMapBranding.loadIcon("favicon.ico", directory));
    }
}
