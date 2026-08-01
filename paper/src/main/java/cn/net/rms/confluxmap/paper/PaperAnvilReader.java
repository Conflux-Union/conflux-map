package cn.net.rms.confluxmap.paper;

import cn.net.rms.confluxmap.core.net.SummaryCodec;
import cn.net.rms.confluxmap.core.util.ChunkRegionSlice;
import cn.net.rms.confluxmap.server.ChunkColumnSummarizer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import net.jpountz.lz4.LZ4BlockInputStream;
import net.querz.nbt.io.NBTInputStream;
import net.querz.nbt.io.NamedTag;
import net.querz.nbt.tag.CompoundTag;

/** Bounded, read-only Anvil scanner that never asks Paper to load or generate chunks. */
final class PaperAnvilReader {
    private static final int SECTOR_BYTES = 4_096;
    private static final int CHUNKS_PER_SIDE = 32;
    private static final int MAX_INLINE_CHUNK_BYTES = 255 * SECTOR_BYTES - Integer.BYTES;
    private static final int MAX_DECOMPRESSED_CHUNK_BYTES = 64 * 1_024 * 1_024;

    static long regionMtime(final Path regionDirectory, final int chunkX, final int chunkZ) {
        return mcaMtime(
            regionDirectory,
            Math.floorDiv(chunkX, CHUNKS_PER_SIDE),
            Math.floorDiv(chunkZ, CHUNKS_PER_SIDE)
        );
    }

    static long summaryRegionMtime(
        final Path regionDirectory,
        final int regionX,
        final int regionZ
    ) {
        return mcaMtime(
            regionDirectory, Math.floorDiv(regionX, 2), Math.floorDiv(regionZ, 2)
        );
    }

    private static long mcaMtime(final Path regionDirectory, final int mcaX, final int mcaZ) {
        final Path path = regionDirectory.resolve("r." + mcaX + "." + mcaZ + ".mca");
        try {
            return Files.isRegularFile(path) ? Files.getLastModifiedTime(path).toMillis() : 0L;
        } catch (final IOException e) {
            return 0L;
        }
    }

    SummaryCodec.SampledRegion scanRegion(
        final Path regionDirectory,
        final int lod,
        final ChunkRegionSlice slice,
        final ChunkColumnSummarizer summarizer
    ) {
        final int stride = 1 << lod;
        final SummaryCodec.SampledChunk[] chunks = new SummaryCodec.SampledChunk[SummaryCodec.CHUNKS];
        Arrays.fill(chunks, SummaryCodec.SampledChunk.empty(stride));
        final int mcaX = Math.floorDiv(slice.regionX(), 2);
        final int mcaZ = Math.floorDiv(slice.regionZ(), 2);
        final Path path = regionDirectory.resolve("r." + mcaX + "." + mcaZ + ".mca");
        if (!Files.isRegularFile(path)) {
            return new SummaryCodec.SampledRegion(slice.regionX(), slice.regionZ(), 0L, stride, chunks);
        }
        final long mtime;
        try {
            mtime = Files.getLastModifiedTime(path).toMillis();
        } catch (final IOException e) {
            return null;
        }
        try (FileChannel file = FileChannel.open(path, StandardOpenOption.READ)) {
            final ByteBuffer locations = ByteBuffer.allocate(SECTOR_BYTES);
            if (!readFully(file, locations, 0L)) {
                return null;
            }
            locations.flip();
            final long fileSize = file.size();
            for (int localZ = slice.minLocalChunkZ(); localZ <= slice.maxLocalChunkZ(); localZ++) {
                for (int localX = slice.minLocalChunkX(); localX <= slice.maxLocalChunkX(); localX++) {
                    if (Thread.currentThread().isInterrupted()) {
                        return null;
                    }
                    final int worldChunkX = slice.regionX() * 16 + localX;
                    final int worldChunkZ = slice.regionZ() * 16 + localZ;
                    final int mcaLocalX = Math.floorMod(worldChunkX, CHUNKS_PER_SIDE);
                    final int mcaLocalZ = Math.floorMod(worldChunkZ, CHUNKS_PER_SIDE);
                    final int location = locations.getInt((mcaLocalZ * CHUNKS_PER_SIDE + mcaLocalX) * 4);
                    if (location == 0) {
                        continue;
                    }
                    final SummaryCodec.SampledChunk summary = scanChunk(
                        file,
                        fileSize,
                        regionDirectory,
                        worldChunkX,
                        worldChunkZ,
                        location >>> 8,
                        location & 0xFF,
                        lod,
                        summarizer
                    );
                    if (summary != null) {
                        chunks[localZ * 16 + localX] = summary;
                    }
                }
            }
            if (file.size() != fileSize
                || Files.getLastModifiedTime(path).toMillis() != mtime) {
                return null;
            }
            return new SummaryCodec.SampledRegion(
                slice.regionX(), slice.regionZ(), mtime, stride, chunks
            );
        } catch (final IOException | RuntimeException e) {
            return null;
        }
    }

    private SummaryCodec.SampledChunk scanChunk(
        final FileChannel file,
        final long fileSize,
        final Path regionDirectory,
        final int chunkX,
        final int chunkZ,
        final int sectorOffset,
        final int sectorCount,
        final int lod,
        final ChunkColumnSummarizer summarizer
    ) {
        if (sectorOffset < 2 || sectorCount <= 0) {
            return null;
        }
        final long byteOffset = (long) sectorOffset * SECTOR_BYTES;
        final long allocatedEnd = byteOffset + (long) sectorCount * SECTOR_BYTES;
        if (allocatedEnd > fileSize || byteOffset + Integer.BYTES + 1L > fileSize) {
            return null;
        }
        final ByteBuffer prefix = ByteBuffer.allocate(Integer.BYTES + 1);
        try {
            if (!readFully(file, prefix, byteOffset)) {
                return null;
            }
            prefix.flip();
            final int length = prefix.getInt();
            final int versionByte = prefix.get() & 0xFF;
            final boolean external = (versionByte & 0x80) != 0;
            final int version = versionByte & 0x7F;
            if (length <= 0 || length > MAX_INLINE_CHUNK_BYTES
                || (!external && length > sectorCount * SECTOR_BYTES - Integer.BYTES)) {
                return null;
            }
            final InputStream raw;
            if (external) {
                raw = Files.newInputStream(regionDirectory.resolve(
                    "c." + chunkX + "." + chunkZ + ".mcc"
                ));
            } else {
                final byte[] payload = new byte[length - 1];
                if (!readFully(file, ByteBuffer.wrap(payload), byteOffset + Integer.BYTES + 1L)) {
                    return null;
                }
                raw = new ByteArrayInputStream(payload);
            }
            try (InputStream decompressed = decompress(raw, version)) {
                final byte[] nbtBytes = readBounded(decompressed);
                try (NBTInputStream nbt = new NBTInputStream(new ByteArrayInputStream(nbtBytes))) {
                    final NamedTag named = nbt.readTag(512);
                    if (named == null || !(named.getTag() instanceof final CompoundTag root)) {
                        return null;
                    }
                    return summarizer.summarizeForLod(new PaperNbtChunkColumnSource(root), lod);
                }
            }
        } catch (final IOException | RuntimeException e) {
            return null;
        }
    }

    private static InputStream decompress(final InputStream input, final int version) throws IOException {
        try {
            return switch (version) {
                case 1 -> new GZIPInputStream(input);
                case 2 -> new InflaterInputStream(input);
                case 3 -> input;
                case 4 -> new LZ4BlockInputStream(input);
                default -> throw new IOException("unsupported Anvil compression version " + version);
            };
        } catch (final IOException | RuntimeException e) {
            try {
                input.close();
            } catch (final IOException ignored) {
                // Preserve the decompressor construction failure.
            }
            throw e;
        }
    }

    private static byte[] readBounded(final InputStream input) throws IOException {
        final java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream(32 * 1_024);
        final byte[] buffer = new byte[16 * 1_024];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("Anvil scan interrupted");
            }
            if (read == 0) {
                continue;
            }
            total += read;
            if (total > MAX_DECOMPRESSED_CHUNK_BYTES) {
                throw new IOException("decompressed chunk NBT exceeds safety cap");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static boolean readFully(
        final FileChannel file,
        final ByteBuffer target,
        final long offset
    ) throws IOException {
        long position = offset;
        while (target.hasRemaining()) {
            if (Thread.currentThread().isInterrupted()) {
                return false;
            }
            final int read = file.read(target, position);
            if (read < 0) {
                return false;
            }
            if (read > 0) {
                position += read;
            }
        }
        return true;
    }
}
