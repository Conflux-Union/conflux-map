package cn.net.rms.confluxmap.core.export;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/** Writes a row-major ARGB spool as RGBA PNG using bounded buffers. */
public final class StreamingPngWriter {
    private static final byte[] SIGNATURE = {
        (byte) 137, 80, 78, 71, 13, 10, 26, 10
    };
    private static final int BUFFER_SIZE = 64 * 1024;

    private StreamingPngWriter() {
    }

    public static void write(
        final Path argbSpool,
        final Path output,
        final int width,
        final int height,
        final BooleanSupplier cancelled
    ) throws IOException {
        write(argbSpool, output, width, height, cancelled, ignored -> {});
    }

    public static void write(
        final Path argbSpool,
        final Path output,
        final int width,
        final int height,
        final BooleanSupplier cancelled,
        final LongConsumer completedRows
    ) throws IOException {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("PNG dimensions must be positive");
        }
        final long expectedBytes = Math.multiplyExact(Math.multiplyExact((long) width, height), 4L);
        if (Files.size(argbSpool) != expectedBytes) {
            throw new IllegalArgumentException("ARGB spool length does not match PNG dimensions");
        }

        boolean complete = false;
        try (InputStream pixels = Files.newInputStream(argbSpool);
             DataOutputStream png = new DataOutputStream(new BufferedOutputStream(
                 Files.newOutputStream(output), BUFFER_SIZE
             ))) {
            png.write(SIGNATURE);
            writeHeader(png, width, height);
            writePixels(png, pixels, width, height, cancelled, completedRows);
            writeChunk(png, "IEND", new byte[0], 0);
            complete = true;
        } finally {
            if (!complete) {
                Files.deleteIfExists(output);
            }
        }
    }

    private static void writeHeader(
        final DataOutputStream out,
        final int width,
        final int height
    ) throws IOException {
        final ByteBuffer header = ByteBuffer.allocate(13);
        header.putInt(width);
        header.putInt(height);
        header.put((byte) 8); // bit depth
        header.put((byte) 6); // RGBA
        header.put((byte) 0); // deflate
        header.put((byte) 0); // adaptive filtering
        header.put((byte) 0); // no interlace
        writeChunk(out, "IHDR", header.array(), header.capacity());
    }

    private static void writePixels(
        final DataOutputStream out,
        final InputStream pixels,
        final int width,
        final int height,
        final BooleanSupplier cancelled,
        final LongConsumer completedRows
    ) throws IOException {
        final Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        final byte[] argb = new byte[BUFFER_SIZE - BUFFER_SIZE % 4];
        final byte[] rgba = new byte[argb.length];
        final byte[] compressed = new byte[BUFFER_SIZE];
        try {
            final byte[] filter = {0};
            final long rowBytes = (long) width * 4L;
            for (int y = 0; y < height; y++) {
                checkCancelled(cancelled);
                feed(out, deflater, filter, 1, compressed);
                long remaining = rowBytes;
                while (remaining > 0L) {
                    checkCancelled(cancelled);
                    final int amount = (int) Math.min(argb.length, remaining);
                    readFully(pixels, argb, amount);
                    argbToRgba(argb, rgba, amount);
                    feed(out, deflater, rgba, amount, compressed);
                    remaining -= amount;
                }
                completedRows.accept(y + 1L);
            }
            deflater.finish();
            while (!deflater.finished()) {
                checkCancelled(cancelled);
                final int count = deflater.deflate(compressed);
                if (count > 0) {
                    writeChunk(out, "IDAT", compressed, count);
                }
            }
        } finally {
            deflater.end();
        }
    }

    private static void feed(
        final DataOutputStream out,
        final Deflater deflater,
        final byte[] input,
        final int length,
        final byte[] compressed
    ) throws IOException {
        deflater.setInput(input, 0, length);
        while (!deflater.needsInput()) {
            final int count = deflater.deflate(compressed);
            if (count > 0) {
                writeChunk(out, "IDAT", compressed, count);
            }
        }
    }

    private static void argbToRgba(
        final byte[] argb,
        final byte[] rgba,
        final int length
    ) {
        for (int offset = 0; offset < length; offset += 4) {
            rgba[offset] = argb[offset + 1];
            rgba[offset + 1] = argb[offset + 2];
            rgba[offset + 2] = argb[offset + 3];
            rgba[offset + 3] = argb[offset];
        }
    }

    private static void readFully(
        final InputStream in,
        final byte[] bytes,
        final int length
    ) throws IOException {
        int offset = 0;
        while (offset < length) {
            final int read = in.read(bytes, offset, length - offset);
            if (read < 0) {
                throw new IOException("ARGB spool ended unexpectedly");
            }
            offset += read;
        }
    }

    private static void writeChunk(
        final DataOutputStream out,
        final String type,
        final byte[] data,
        final int length
    ) throws IOException {
        final byte[] typeBytes = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        final CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data, 0, length);
        out.writeInt(length);
        out.write(typeBytes);
        out.write(data, 0, length);
        out.writeInt((int) crc.getValue());
    }

    private static void checkCancelled(final BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("Map export cancelled");
        }
    }
}
