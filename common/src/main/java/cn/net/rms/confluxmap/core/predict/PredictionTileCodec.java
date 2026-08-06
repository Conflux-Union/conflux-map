package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.net.MapSyncCompatibility;
import cn.net.rms.confluxmap.core.net.PatchCodec;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.net.ProtoException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;

/** Atomic on-disk codec for one client's committed prediction correction tile. */
public final class PredictionTileCodec {
    public static final byte[] MAGIC = {'C', 'F', 'P', 'T'};
    /**
     * Bumped whenever persisted correction semantics change; old corrections are non-authoritative.
     * Version 12 persists the client's last final server-validation time. Version 13 stores raw
     * authoritative snapshots with pixel-level evaluated coverage and submerged floor colours.
     * Version 14 discards snapshots derived from inclusive live-chunk heightmap values. Version
     * 15 stores protocol-v4 compressed patch bodies. Version 16 adds exact per-chunk generated,
     * revision, and validation metadata for cropped summary-region pages. Version 15 remains
     * drawable, but its tile-wide validation cannot prove that an exact chunk range is fresh.
     * Version 17 records the wire patch mode and predictor baseline that omitted residual pixels
     * reconstruct from. Version 18 stores enhanced patch bodies with source revisions and light.
     * Version 19 discards corrections that may have been produced by the light-dropping native
     * Anvil scanner.
     */
    public static final int FORMAT_VERSION = 19;
    private static final int SOURCE_PROFILE_VERSION = 17;
    private static final int OLDEST_READABLE_FORMAT_VERSION = 19;
    private static final int CHUNK_METADATA_VERSION = 1;
    private static final int MAX_CHUNK_METADATA_RAW_BYTES = 1_250_000;
    private static final int MAX_CHUNK_METADATA_COMPRESSED_BYTES = 1_250_000;

    private PredictionTileCodec() {
    }

    public record FileData(
        int lod,
        int tileX,
        int tileZ,
        long revision,
        long validatedAtMillis,
        byte[] presence,
        PatchCodec.Patch patch,
        byte[] generatedChunks,
        long[] chunkRevisions,
        long[] chunkValidatedAtMillis,
        int patchMode,
        String baselineProfile
    ) {
        public FileData {
            if (presence == null || presence.length != Proto.PATCH_PRESENCE_BYTES) {
                throw new IllegalArgumentException("presence must be 32 bytes");
            }
            if (lod < 0 || lod > 4 || patch == null) {
                throw new IllegalArgumentException("invalid correction file data");
            }
            if ((patchMode != Proto.PATCH_MODE_RESIDUAL && patchMode != Proto.PATCH_MODE_ABSOLUTE)
                || baselineProfile == null
                || (patchMode == Proto.PATCH_MODE_RESIDUAL && baselineProfile.isEmpty())) {
                throw new IllegalArgumentException("invalid correction source profile");
            }
            presence = presence.clone();
            generatedChunks = generatedChunks == null ? new byte[0] : generatedChunks.clone();
            chunkRevisions = chunkRevisions == null ? new long[0] : chunkRevisions.clone();
            chunkValidatedAtMillis = chunkValidatedAtMillis == null
                ? new long[0] : chunkValidatedAtMillis.clone();
            final int chunksPerSide = 16 << lod;
            final int chunks = chunksPerSide * chunksPerSide;
            final boolean absent = generatedChunks.length == 0
                && chunkRevisions.length == 0 && chunkValidatedAtMillis.length == 0;
            if (!absent && (generatedChunks.length != (chunks + 7) / 8
                || chunkRevisions.length != chunks || chunkValidatedAtMillis.length != chunks)) {
                throw new IllegalArgumentException("chunk correction metadata has the wrong length");
            }
        }

        public FileData(
            final int lod,
            final int tileX,
            final int tileZ,
            final long revision,
            final long validatedAtMillis,
            final byte[] presence,
            final PatchCodec.Patch patch,
            final byte[] generatedChunks,
            final long[] chunkRevisions,
            final long[] chunkValidatedAtMillis
        ) {
            this(
                lod, tileX, tileZ, revision, validatedAtMillis, presence, patch,
                generatedChunks, chunkRevisions, chunkValidatedAtMillis,
                Proto.PATCH_MODE_RESIDUAL, MapSyncCompatibility.STABLE_PREDICTOR
            );
        }

        public FileData(
            final int lod,
            final int tileX,
            final int tileZ,
            final long revision,
            final long validatedAtMillis,
            final byte[] presence,
            final PatchCodec.Patch patch
        ) {
            this(
                lod, tileX, tileZ, revision, validatedAtMillis, presence, patch,
                new byte[0], new long[0], new long[0],
                Proto.PATCH_MODE_RESIDUAL, MapSyncCompatibility.STABLE_PREDICTOR
            );
        }

        @Override
        public byte[] generatedChunks() {
            return generatedChunks.clone();
        }

        @Override
        public long[] chunkRevisions() {
            return chunkRevisions.clone();
        }

        @Override
        public long[] chunkValidatedAtMillis() {
            return chunkValidatedAtMillis.clone();
        }

        public boolean hasChunkMetadata() {
            return chunkRevisions.length != 0;
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
            header.writeLong(data.validatedAtMillis());
            header.writeByte(data.patchMode());
            writeBoundedUtf(header, data.baselineProfile());
            header.write(data.presence());
            final byte[] body = PatchCodec.encode(data.patch());
            header.writeInt(body.length);
            header.write(body);
            final byte[] metadata = encodeChunkMetadata(data);
            header.writeInt(metadata.length);
            header.write(metadata);
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
            if (!Arrays.equals(MAGIC, magic)) {
                throw new ProtoException("invalid correction header");
            }
            final int formatVersion = in.readUnsignedByte();
            if (formatVersion < OLDEST_READABLE_FORMAT_VERSION || formatVersion > FORMAT_VERSION) {
                throw new ProtoException("unsupported correction version " + formatVersion);
            }
            final int lod = in.readUnsignedByte();
            if (lod < 0 || lod > 4) {
                throw new ProtoException("invalid correction LOD " + lod);
            }
            final int tileX = in.readInt();
            final int tileZ = in.readInt();
            final long revision = in.readLong();
            final long validatedAtMillis = in.readLong();
            final int patchMode = formatVersion >= SOURCE_PROFILE_VERSION
                ? in.readUnsignedByte() : Proto.PATCH_MODE_RESIDUAL;
            final String baselineProfile = formatVersion >= SOURCE_PROFILE_VERSION
                ? readBoundedUtf(in) : MapSyncCompatibility.STABLE_PREDICTOR;
            final byte[] presence = new byte[Proto.PATCH_PRESENCE_BYTES];
            in.readFully(presence);
            final int bodyLength = in.readInt();
            if (bodyLength <= 0 || bodyLength > PatchCodec.MAX_COMPRESSED_BYTES || bodyLength > in.available()) {
                throw new ProtoException("invalid correction body length " + bodyLength);
            }
            final byte[] body = new byte[bodyLength];
            in.readFully(body);
            final PatchCodec.Patch patch = PatchCodec.decode(body);
            final int metadataLength = in.readInt();
            if (metadataLength < 0 || metadataLength > MAX_CHUNK_METADATA_COMPRESSED_BYTES
                || metadataLength > in.available()) {
                throw new ProtoException("invalid chunk correction metadata length " + metadataLength);
            }
            final byte[] metadata = new byte[metadataLength];
            in.readFully(metadata);
            if (in.available() != 0) {
                throw new ProtoException("trailing correction bytes");
            }
            return decodeChunkMetadata(
                lod, tileX, tileZ, revision, validatedAtMillis, presence, patch,
                metadata, patchMode, baselineProfile
            );
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

    private static byte[] encodeChunkMetadata(final FileData data) throws IOException {
        if (!data.hasChunkMetadata()) {
            return new byte[0];
        }
        final ByteArrayOutputStream rawBytes = new ByteArrayOutputStream();
        final DataOutputStream raw = new DataOutputStream(rawBytes);
        raw.writeByte(CHUNK_METADATA_VERSION);
        final byte[] generated = data.generatedChunks();
        raw.writeInt(generated.length);
        raw.write(generated);
        final long[] revisions = data.chunkRevisions();
        final long[] validated = data.chunkValidatedAtMillis();
        int known = 0;
        for (final long chunkRevision : revisions) {
            if (chunkRevision != Long.MIN_VALUE) {
                known++;
            }
        }
        raw.writeInt(known);
        for (int chunk = 0; chunk < revisions.length; chunk++) {
            if (revisions[chunk] == Long.MIN_VALUE) {
                continue;
            }
            raw.writeShort(chunk);
            raw.writeLong(revisions[chunk]);
            raw.writeLong(validated[chunk]);
        }
        raw.flush();
        if (rawBytes.size() > MAX_CHUNK_METADATA_RAW_BYTES) {
            throw new IllegalArgumentException("chunk correction metadata exceeds raw cap");
        }
        final ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(compressed)) {
            deflater.write(rawBytes.toByteArray());
        }
        if (compressed.size() > MAX_CHUNK_METADATA_COMPRESSED_BYTES) {
            throw new IllegalArgumentException("chunk correction metadata exceeds compressed cap");
        }
        return compressed.toByteArray();
    }

    private static FileData decodeChunkMetadata(
        final int lod,
        final int tileX,
        final int tileZ,
        final long revision,
        final long validatedAtMillis,
        final byte[] presence,
        final PatchCodec.Patch patch,
        final byte[] compressed,
        final int patchMode,
        final String baselineProfile
    ) throws IOException, ProtoException {
        if (compressed.length == 0) {
            return new FileData(
                lod, tileX, tileZ, revision, validatedAtMillis, presence, patch,
                new byte[0], new long[0], new long[0], patchMode, baselineProfile
            );
        }
        final byte[] rawBytes = inflateChunkMetadata(compressed);
        final DataInputStream raw = new DataInputStream(new ByteArrayInputStream(rawBytes));
        if (raw.readUnsignedByte() != CHUNK_METADATA_VERSION) {
            throw new ProtoException("unsupported chunk correction metadata version");
        }
        final int chunksPerSide = 16 << lod;
        final int chunks = chunksPerSide * chunksPerSide;
        final int generatedLength = raw.readInt();
        if (generatedLength != (chunks + 7) / 8 || generatedLength > raw.available()) {
            throw new ProtoException("invalid generated chunk mask length " + generatedLength);
        }
        final byte[] generated = new byte[generatedLength];
        raw.readFully(generated);
        final int known = raw.readInt();
        if (known < 0 || known > chunks || known * 18L > raw.available()) {
            throw new ProtoException("invalid known chunk metadata count " + known);
        }
        final long[] revisions = new long[chunks];
        Arrays.fill(revisions, Long.MIN_VALUE);
        final long[] validated = new long[chunks];
        int previousChunk = -1;
        for (int i = 0; i < known; i++) {
            final int chunk = raw.readUnsignedShort();
            if (chunk >= chunks || chunk <= previousChunk) {
                throw new ProtoException("invalid chunk metadata index " + chunk);
            }
            revisions[chunk] = raw.readLong();
            validated[chunk] = raw.readLong();
            previousChunk = chunk;
        }
        if (raw.available() != 0) {
            throw new ProtoException("trailing chunk correction metadata");
        }
        return new FileData(
            lod, tileX, tileZ, revision, validatedAtMillis, presence, patch,
            generated, revisions, validated, patchMode, baselineProfile
        );
    }

    private static void writeBoundedUtf(
        final DataOutputStream out, final String value
    ) throws IOException {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > Proto.MAX_UTF8_BYTES) {
            throw new IllegalArgumentException("correction baseline profile is too long");
        }
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private static String readBoundedUtf(
        final DataInputStream in
    ) throws IOException, ProtoException {
        final int length = in.readUnsignedShort();
        if (length > Proto.MAX_UTF8_BYTES || length > in.available()) {
            throw new ProtoException("invalid correction baseline profile length " + length);
        }
        final byte[] bytes = new byte[length];
        in.readFully(bytes);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (final CharacterCodingException e) {
            throw new ProtoException("malformed correction baseline profile", e);
        }
    }

    private static byte[] inflateChunkMetadata(final byte[] compressed) throws ProtoException {
        final Inflater inflater = new Inflater();
        inflater.setInput(compressed);
        final ByteArrayOutputStream raw = new ByteArrayOutputStream();
        final byte[] buffer = new byte[8192];
        try {
            while (!inflater.finished()) {
                final int count = inflater.inflate(buffer);
                if (count > 0) {
                    raw.write(buffer, 0, count);
                    if (raw.size() > MAX_CHUNK_METADATA_RAW_BYTES) {
                        throw new ProtoException("chunk correction metadata exceeds raw cap");
                    }
                } else if (inflater.needsDictionary() || inflater.needsInput()) {
                    throw new ProtoException("truncated chunk correction metadata");
                }
            }
            if (inflater.getRemaining() != 0) {
                throw new ProtoException("trailing compressed chunk correction metadata");
            }
            return raw.toByteArray();
        } catch (final DataFormatException e) {
            throw new ProtoException("malformed chunk correction metadata", e);
        } finally {
            inflater.end();
        }
    }
}
