package cn.net.rms.confluxmap.server.web;

import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/** Validate dimensions and format before decoding; re-encode without original metadata. */
final class WaypointImageCodec {
    private WaypointImageCodec() {}

    static byte[] reviewThumbnail(java.nio.file.Path path) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(path.toFile())) {
            if (input == null) throw new IOException("Missing review image");
            final Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Invalid review image");
            final ImageReader reader = readers.next();
            try {
                reader.setInput(input, false, true);
                final int width = reader.getWidth(0), height = reader.getHeight(0);
                for (int limit : new int[]{128, 64}) {
                    final int stride = 1 + (Math.max(width, height) - 1) / limit;
                    final var params = reader.getDefaultReadParam();
                    params.setSourceSubsampling(stride, stride, 0, 0);
                    final BufferedImage scaled = reader.read(0, params);
                    final BufferedImage jpeg =
                        new BufferedImage(scaled.getWidth(), scaled.getHeight(), BufferedImage.TYPE_INT_RGB);
                    final var graphics = jpeg.createGraphics();
                    try {
                        graphics.drawImage(scaled, 0, 0, null);
                    } finally {
                        graphics.dispose();
                    }
                    final var output = new ByteArrayOutputStream();
                    if (ImageIO.write(jpeg, "jpeg", output) && output.size() <= 10000)
                        return output.toByteArray();
                }
                throw new IOException("Review preview is too large");
            } finally {
                reader.dispose();
            }
        }
    }

    static byte[] decode(byte[] data) throws IOException {
        try (ImageInputStream in =
                new javax.imageio.stream.MemoryCacheImageInputStream(
                        new ByteArrayInputStream(data))) {
            if (in == null) throw new IOException();
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) throw new IOException();
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                String fmt = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!fmt.equals("png") && !fmt.equals("jpeg")) throw new IOException();
                int w = reader.getWidth(0), h = reader.getHeight(0);
                if (w < 1 || h < 1 || w > 2048 || h > 2048 || ((long) w * h) > 4194304)
                    throw new IOException();
                BufferedImage im = reader.read(0);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                if (!ImageIO.write(im, "png", out) || out.size() > 8 * 1024 * 1024)
                    throw new IOException();
                return out.toByteArray();
            } finally {
                reader.dispose();
            }
        }
    }
}
