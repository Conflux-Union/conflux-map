package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.net.PatchCodec;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.net.ProtoException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/** Atomic on-disk codec for one client's absolute prediction correction tile. */
public final class PredictionTileCodec {
    public static final byte[] MAGIC = {'C', 'F', 'P', 'T'};
    /** Bumped whenever persisted correction semantics change; old corrections are non-authoritative. */
    public static final int FORMAT_VERSION = 6;

    private PredictionTileCodec() {
    }

    public record FileData(int lod, int tileX, int tileZ, long revision, byte[] presence, PatchCodec.Patch patch) {
        public FileData {
            if (presence == null || presence.length != Proto.PATCH_PRESENCE_BYTES) {
                throw new IllegalArgumentException("presence must be 32 bytes");
            }
            presence = presence.clone();
        }
    }

    public static byte[] encode(final FileData data) {
        try {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final DataOutputStream header = new DataOutputStream(out);
            header.write(MAGIC);
            header.writeByte(FORMAT_VERSION);
            header.writeByte(data.lod());
            header.writeInt(data.tileX());
            header.writeInt(data.tileZ());
            header.writeLong(data.revision());
            header.write(data.presence());
            final byte[] body = PatchCodec.encode(data.patch());
            header.writeInt(body.length);
            header.write(body);
            header.flush();
            return out.toByteArray();
        } catch (final IOException e) {
            throw new IllegalStateException("in-memory correction encoding failed", e);
        }
    }

    public static FileData decode(final byte[] bytes) throws ProtoException {
        try {
            final DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            final byte[] magic = new byte[MAGIC.length];
            in.readFully(magic);
            if (!Arrays.equals(MAGIC, magic) || in.readUnsignedByte() != FORMAT_VERSION) {
                throw new ProtoException("invalid correction header");
            }
            final int lod = in.readUnsignedByte();
            final int tileX = in.readInt();
            final int tileZ = in.readInt();
            final long revision = in.readLong();
            final byte[] presence = new byte[Proto.PATCH_PRESENCE_BYTES];
            in.readFully(presence);
            final int bodyLength = in.readInt();
            if (bodyLength <= 0 || bodyLength > PatchCodec.MAX_COMPRESSED_BYTES || bodyLength > in.available()) {
                throw new ProtoException("invalid correction body length " + bodyLength);
            }
            final byte[] body = new byte[bodyLength];
            in.readFully(body);
            if (in.available() != 0) {
                throw new ProtoException("trailing correction bytes");
            }
            return new FileData(lod, tileX, tileZ, revision, presence, PatchCodec.decode(body));
        } catch (final ProtoException e) {
            throw e;
        } catch (final IOException | IllegalArgumentException e) {
            throw new ProtoException("malformed correction file", e);
        }
    }

    public static void writeAtomic(final Path path, final FileData data) throws IOException {
        Files.createDirectories(path.getParent());
        final Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.write(tmp, encode(data), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static FileData read(final Path path) throws IOException, ProtoException {
        return decode(Files.readAllBytes(path));
    }
}
