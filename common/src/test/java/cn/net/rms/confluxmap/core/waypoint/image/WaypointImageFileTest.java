package cn.net.rms.confluxmap.core.waypoint.image;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.net.image.WaypointImageProto;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WaypointImageFileTest {
    private static final UUID OWNER = UUID.fromString("0123abcd-4567-789a-bcde-f0123456789a");

    private static StoredImage sample() {
        final byte[] png = new byte[512];
        for (int i = 0; i < png.length; i++) {
            png[i] = (byte) (i * 3);
        }
        return new StoredImage(OWNER, WaypointImageProto.FORMAT_PNG, 320, 180, png);
    }

    @Test
    void saveAndLoadRoundTripsTheContainer(@TempDir final Path tempDir) throws IOException {
        final Path file = tempDir.resolve("sample.img");
        final StoredImage image = sample();

        WaypointImageFile.save(file, image);

        final StoredImage loaded = WaypointImageFile.load(file);
        assertEquals(OWNER, loaded.owner());
        assertEquals(WaypointImageProto.FORMAT_PNG, loaded.formatTag());
        assertEquals(320, loaded.width());
        assertEquals(180, loaded.height());
        assertArrayEquals(image.png(), loaded.png());
        assertTrue(WaypointImageFile.readHeader(file).owner().equals(OWNER));
    }

    @Test
    void containerIsRejectedWhenMagicVersionOrBoundsAreWrong(@TempDir final Path tempDir)
        throws IOException {
        final Path file = tempDir.resolve("sample.img");
        WaypointImageFile.save(file, sample());

        final byte[] bytes = Files.readAllBytes(file);
        final byte[] badMagic = bytes.clone();
        badMagic[0] = 'X';
        Files.write(tempDir.resolve("bad-magic.img"), badMagic);
        assertThrows(IOException.class, () -> WaypointImageFile.load(tempDir.resolve("bad-magic.img")));

        final byte[] badVersion = bytes.clone();
        badVersion[8] = 9;
        Files.write(tempDir.resolve("bad-version.img"), badVersion);
        assertThrows(IOException.class, () -> WaypointImageFile.load(tempDir.resolve("bad-version.img")));

        final byte[] badLength = Arrays.copyOf(bytes, bytes.length - 1);
        // The declared payload length no longer matches the truncated file size.
        Files.write(tempDir.resolve("bad-length.img"), badLength);
        assertThrows(IOException.class, () -> WaypointImageFile.load(tempDir.resolve("bad-length.img")));
    }

    @Test
    void dimensionsOutsideTheSupportedRangeAreRefused(@TempDir final Path tempDir) {
        final Path file = tempDir.resolve("huge.img");
        assertThrows(IllegalArgumentException.class, () -> WaypointImageFile.save(file, new StoredImage(
            OWNER, WaypointImageProto.FORMAT_PNG, 4097, 180, new byte[64]
        )));
    }
}
