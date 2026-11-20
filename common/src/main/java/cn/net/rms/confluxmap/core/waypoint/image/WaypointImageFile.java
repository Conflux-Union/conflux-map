package cn.net.rms.confluxmap.core.waypoint.image;

import cn.net.rms.confluxmap.core.net.image.WaypointImageProto;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * On-disk container for one stored waypoint image. The owner lives in the
 * header so the server can enforce its owner rules and rebuild its accounting
 * without any index file. Writes are atomic (tmp + move) and every read
 * validates magic, version, and bounds; garbage containers throw IOException
 * so callers quarantine or ignore them.
 */
public final class WaypointImageFile {
    public static final int VERSION = 1;
    private static final byte[] MAGIC = "CFXWIMG1".getBytes(StandardCharsets.US_ASCII);
    private static final int HEADER_BYTES = MAGIC.length + 1 + 1 + 4 + 4 + 16 + 4;

    private WaypointImageFile() {
    }

    public static void save(final Path file, final StoredImage image) throws IOException {
        final Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
            Files.newOutputStream(tmp), 8 * 1024
        ))) {
            out.write(MAGIC);
            out.writeByte(VERSION);
            out.writeByte(image.formatTag());
            out.writeInt(image.width());
            out.writeInt(image.height());
            out.writeLong(image.owner().getMostSignificantBits());
            out.writeLong(image.owner().getLeastSignificantBits());
            out.writeInt(image.png().length);
            out.write(image.png());
        }
        move(tmp, file);
    }

    /** Loads the full container; unreadable files throw IOException. */
    public static StoredImage load(final Path file) throws IOException {
        final byte[] bytes = Files.readAllBytes(file);
        if (bytes.length < HEADER_BYTES || !startsWithMagic(bytes)) {
            throw new IOException("waypoint image " + file.getFileName() + " has a bad header");
        }
        final DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(bytes));
        skipFully(in, MAGIC.length);
        final int version = in.readUnsignedByte();
        if (version != VERSION) {
            throw new IOException("waypoint image " + file.getFileName() + " has version " + version);
        }
        final int formatTag = in.readUnsignedByte();
        final int width = in.readInt();
        final int height = in.readInt();
        final UUID owner = new UUID(in.readLong(), in.readLong());
        final int payloadBytes = in.readInt();
        if (width < 1 || width > WaypointImageProto.MAX_DIMENSION
            || height < 1 || height > WaypointImageProto.MAX_DIMENSION) {
            throw new IOException(
                "waypoint image " + file.getFileName() + " has dimensions " + width + "x" + height
            );
        }
        if (payloadBytes < 1
            || payloadBytes > WaypointImageProto.MAX_IMAGE_BYTES
            || bytes.length != HEADER_BYTES + payloadBytes) {
            throw new IOException(
                "waypoint image " + file.getFileName() + " has an inconsistent payload length"
            );
        }
        try {
            return new StoredImage(owner, formatTag, width, height, readFully(in, payloadBytes));
        } catch (final IllegalArgumentException e) {
            throw new IOException(
                "waypoint image " + file.getFileName() + " has an invalid field", e
            );
        }
    }

    /** Header-only read; used to rebuild owner accounting without touching payloads. */
    public static Header readHeader(final Path file) throws IOException {
        final byte[] header = new byte[HEADER_BYTES];
        try (InputStream in = Files.newInputStream(file)) {
            final int read = in.readNBytes(header, 0, HEADER_BYTES);
            if (read < HEADER_BYTES || !startsWithMagic(header)) {
                throw new IOException("waypoint image " + file.getFileName() + " has a bad header");
            }
        }
        final DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(header));
        skipFully(in, MAGIC.length);
        final int version = in.readUnsignedByte();
        if (version != VERSION) {
            throw new IOException("waypoint image " + file.getFileName() + " has version " + version);
        }
        final int formatTag = in.readUnsignedByte();
        final int width = in.readInt();
        final int height = in.readInt();
        final UUID owner = new UUID(in.readLong(), in.readLong());
        final int payloadBytes = in.readInt();
        return new Header(owner, formatTag, width, height, payloadBytes);
    }

    public record Header(UUID owner, int formatTag, int width, int height, int payloadBytes) {
    }

    private static boolean startsWithMagic(final byte[] bytes) {
        for (int i = 0; i < MAGIC.length; i++) {
            if (bytes[i] != MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] readFully(final DataInputStream in, final int length) throws IOException {
        final byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }

    private static void skipFully(final DataInputStream in, final int count) throws IOException {
        for (int skipped = 0; skipped < count; skipped++) {
            in.readByte();
        }
    }

    private static void move(final Path tmp, final Path file) throws IOException {
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
