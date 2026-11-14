package cn.net.rms.confluxmap.core.waypoint.image;

import cn.net.rms.confluxmap.core.export.StreamingPngWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Encodes an ARGB raster as a PNG that fits a byte budget. Long screenshots
 * shrink stepwise (longest edge ×3/4, floor 96px) because a 320px crop of a
 * high-detail world can exceed any fixed budget; the largest compliant result
 * wins. {@code png} bytes stay opaque to everything downstream — servers never
 * decode them.
 */
public final class WaypointImageEncode {
    public static final int MAX_EDGE_START = 320;
    public static final int MIN_EDGE = 96;
    public static final int TARGET_BYTES = 48 * 1024;

    private WaypointImageEncode() {
    }

    public record Encoded(int width, int height, byte[] png) {
    }

    /**
     * Encodes within the byte budget, or empty when even the 96px floor
     * exceeds it (callers treat that as TOO_LARGE and keep the previous image).
     * Encode IO failures propagate — they are a storage fault, not a size
     * verdict. Scratch files live in {@code scratchDir} and are always cleaned
     * up.
     */
    public static Optional<Encoded> encodeWithin(
        final ImageScale.Raster raster,
        final Path scratchDir,
        final int maxEdgeStart,
        final int maxBytes
    ) throws IOException {
        int edge = Math.min(maxEdgeStart, Math.max(raster.width(), raster.height()));
        while (true) {
            final int[] target = ImageScale.fitMaxEdge(raster.width(), raster.height(), edge);
            final ImageScale.Raster downscaled = ImageScale.downscale(raster, target[0], target[1]);
            final byte[] png = encode(downscaled, scratchDir);
            if (png.length <= maxBytes) {
                return Optional.of(new Encoded(downscaled.width(), downscaled.height(), png));
            }
            if (edge <= MIN_EDGE) {
                return Optional.empty();
            }
            edge = Math.max(MIN_EDGE, edge * 3 / 4);
        }
    }

    private static byte[] encode(final ImageScale.Raster raster, final Path scratchDir)
        throws IOException {
        Files.createDirectories(scratchDir);
        final Path spool = Files.createTempFile(scratchDir, "waypoint-image", ".spool");
        final Path output = Files.createTempFile(scratchDir, "waypoint-image", ".png");
        try {
            try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
                spool,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.WRITE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
            )) {
                final java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(16 * 1024);
                int offset = 0;
                while (offset < raster.argb().length) {
                    buffer.clear();
                    while (buffer.remaining() >= 4 && offset < raster.argb().length) {
                        buffer.putInt(raster.argb()[offset++]);
                    }
                    buffer.flip();
                    while (buffer.hasRemaining()) {
                        channel.write(buffer);
                    }
                }
            }
            StreamingPngWriter.write(spool, output, raster.width(), raster.height(), () -> false);
            return Files.readAllBytes(output);
        } finally {
            Files.deleteIfExists(spool);
            Files.deleteIfExists(output);
        }
    }
}
