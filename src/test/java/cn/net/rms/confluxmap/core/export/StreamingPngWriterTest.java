package cn.net.rms.confluxmap.core.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.image.BufferedImage;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class StreamingPngWriterTest {
    @TempDir
    Path temp;

    @Test
    void writesArgbSpoolAsDecodableRgbaPng() throws Exception {
        final Path spool = temp.resolve("pixels.argb");
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(spool))) {
            out.writeInt(0xFFFF0000);
            out.writeInt(0x8000FF00);
            out.writeInt(0x400000FF);
            out.writeInt(0x00000000);
        }
        final Path png = temp.resolve("map.png");
        final AtomicLong completedRows = new AtomicLong();

        StreamingPngWriter.write(spool, png, 2, 2, () -> false, completedRows::set);

        final BufferedImage image = ImageIO.read(png.toFile());
        assertEquals(2, image.getWidth());
        assertEquals(2, image.getHeight());
        assertEquals(0xFFFF0000, image.getRGB(0, 0));
        assertEquals(0x8000FF00, image.getRGB(1, 0));
        assertEquals(0x400000FF, image.getRGB(0, 1));
        assertEquals(0x00000000, image.getRGB(1, 1));
        assertEquals(2L, completedRows.get());
    }

    @Test
    void cancellationRemovesPartialOutput() throws Exception {
        final Path spool = temp.resolve("pixels.argb");
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(spool))) {
            out.writeInt(0xFFFFFFFF);
        }
        final Path png = temp.resolve("map.png.part");

        assertThrows(
            CancellationException.class,
            () -> StreamingPngWriter.write(spool, png, 1, 1, () -> true)
        );
        assertFalse(Files.exists(png));
    }

    @Test
    void rejectsSpoolWithWrongLength() throws Exception {
        final Path spool = temp.resolve("pixels.argb");
        Files.write(spool, new byte[3]);

        assertThrows(
            IllegalArgumentException.class,
            () -> StreamingPngWriter.write(
                spool, temp.resolve("map.png"), 1, 1, () -> false
            )
        );
    }
}
