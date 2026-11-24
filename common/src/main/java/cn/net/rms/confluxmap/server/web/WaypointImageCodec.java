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
