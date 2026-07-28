package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.core.net.SummaryCodec;
import cn.net.rms.confluxmap.nativepredict.NativeChunkNbtScanner;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
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
//#if MC>=12005
//$$ import net.jpountz.lz4.LZ4BlockInputStream;
//#endif
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;

/** Reads one Anvil file once and produces all four 16x16-chunk summary regions it contains. */
final class AnvilMcaScanner {
    private static final int SECTOR_BYTES = 4_096;
    private static final int LOCATION_BYTES = SECTOR_BYTES;
    private static final int CHUNKS_PER_SIDE = 32;
    private static final int CHUNKS_PER_MCA = CHUNKS_PER_SIDE * CHUNKS_PER_SIDE;
    private static final int SUMMARY_REGIONS_PER_SIDE = 2;
    private static final int MAX_INLINE_CHUNK_BYTES = 255 * SECTOR_BYTES - Integer.BYTES;
    private static final int MAX_DECOMPRESSED_CHUNK_BYTES = 64 * 1_024 * 1_024;
    private final boolean nativeEnabled;

    private record ChunkBytes(byte[] buffer, int length) {
    }

    private static final class ChunkBuffer extends java.io.ByteArrayOutputStream {
        ChunkBuffer() {
            super(32 * 1_024);
        }

        ChunkBytes result() {
            return new ChunkBytes(buf, count);
        }
    }

    AnvilMcaScanner() {
        this(true);
    }

    AnvilMcaScanner(final boolean nativeEnabled) {
        this.nativeEnabled = nativeEnabled;
    }

    record Scan(int mcaX, int mcaZ, long sourceMcaMtimeMs, SummaryCodec.SampledRegion[] regions) {
        Scan {
            if (regions == null || regions.length != 4) {
                throw new IllegalArgumentException("Anvil scan must contain four summary regions");
            }
            regions = regions.clone();
        }

        SummaryCodec.SampledRegion region(final int summaryRegionX, final int summaryRegionZ) {
            final int localX = Math.floorMod(summaryRegionX, SUMMARY_REGIONS_PER_SIDE);
            final int localZ = Math.floorMod(summaryRegionZ, SUMMARY_REGIONS_PER_SIDE);
            return regions[localZ * SUMMARY_REGIONS_PER_SIDE + localX];
        }
    }

    /** Returns {@code null} only when the file cannot be trusted and vanilla must be used as fallback. */
    Scan scan(
        final Path path,
        final int mcaX,
        final int mcaZ,
        final long sourceMcaMtimeMs,
        final int lod,
        final ChunkSummarizer summarizer
    ) {
        final int sampleStride = 1 << lod;
        final SummaryCodec.SampledChunk[][] chunks = new SummaryCodec.SampledChunk[4][SummaryCodec.CHUNKS];
        for (final SummaryCodec.SampledChunk[] region : chunks) {
            Arrays.fill(region, SummaryCodec.SampledChunk.empty(sampleStride));
        }

        try (FileChannel file = FileChannel.open(path, StandardOpenOption.READ)) {
            final ByteBuffer locations = ByteBuffer.allocate(LOCATION_BYTES);
            if (!readFully(file, locations, 0L)) {
                return null;
            }
            locations.flip();
            final long fileSize = file.size();
            for (int mcaChunkIndex = 0; mcaChunkIndex < CHUNKS_PER_MCA; mcaChunkIndex++) {
                final int location = locations.getInt();
                if (location == 0) {
                    continue;
                }
                final int sectorOffset = location >>> 8;
                final int sectorCount = location & 0xFF;
                final SummaryCodec.SampledChunk summary = scanChunk(
                    file, fileSize, path.getParent(), mcaX, mcaZ, mcaChunkIndex,
                    sectorOffset, sectorCount, lod, summarizer
                );
                if (summary == null) {
                    continue;
                }
                final int localX = mcaChunkIndex % CHUNKS_PER_SIDE;
                final int localZ = mcaChunkIndex / CHUNKS_PER_SIDE;
                final int regionIndex = (localZ >>> 4) * SUMMARY_REGIONS_PER_SIDE + (localX >>> 4);
                final int regionChunkIndex = (localZ & 15) * 16 + (localX & 15);
                chunks[regionIndex][regionChunkIndex] = summary;
            }
        } catch (final IOException | RuntimeException e) {
            return null;
        }

        final SummaryCodec.SampledRegion[] regions = new SummaryCodec.SampledRegion[4];
        for (int localZ = 0; localZ < SUMMARY_REGIONS_PER_SIDE; localZ++) {
            for (int localX = 0; localX < SUMMARY_REGIONS_PER_SIDE; localX++) {
                final int index = localZ * SUMMARY_REGIONS_PER_SIDE + localX;
                regions[index] = new SummaryCodec.SampledRegion(
                    mcaX * SUMMARY_REGIONS_PER_SIDE + localX,
                    mcaZ * SUMMARY_REGIONS_PER_SIDE + localZ,
                    sourceMcaMtimeMs,
                    sampleStride,
                    chunks[index]
                );
            }
        }
        return new Scan(mcaX, mcaZ, sourceMcaMtimeMs, regions);
    }

    private SummaryCodec.SampledChunk scanChunk(
        final FileChannel file,
        final long fileSize,
        final Path regionDirectory,
        final int mcaX,
        final int mcaZ,
        final int mcaChunkIndex,
        final int sectorOffset,
        final int sectorCount,
        final int lod,
        final ChunkSummarizer summarizer
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
                final int localX = mcaChunkIndex % CHUNKS_PER_SIDE;
                final int localZ = mcaChunkIndex / CHUNKS_PER_SIDE;
                final int chunkX = mcaX * CHUNKS_PER_SIDE + localX;
                final int chunkZ = mcaZ * CHUNKS_PER_SIDE + localZ;
                raw = Files.newInputStream(regionDirectory.resolve("c." + chunkX + "." + chunkZ + ".mcc"));
            } else {
                final byte[] payload = new byte[length - 1];
                final ByteBuffer bytes = ByteBuffer.wrap(payload);
                if (!readFully(file, bytes, byteOffset + Integer.BYTES + 1L)) {
                    return null;
                }
                raw = new ByteArrayInputStream(payload);
            }
            try (InputStream decompressed = decompress(raw, version)) {
                final ChunkBytes rawNbt = readChunkBytes(decompressed);
                final NativeChunkNbtScanner.Chunk nativeChunk = nativeEnabled
                    ? NativeChunkNbtScanner.scan(rawNbt.buffer(), rawNbt.length(), lod)
                    : null;
                if (nativeChunk != null) {
                    return summarizer.summarizeNative(nativeChunk);
                }
                final NbtCompound nbt = NbtIo.read(
                    new DataInputStream(new ByteArrayInputStream(
                        rawNbt.buffer(), 0, rawNbt.length()
                    ))
                );
                return nbt == null ? null : summarizer.summarizeForLod(nbt, lod);
            }
        } catch (final IOException | RuntimeException e) {
            return null;
        }
    }

    private static InputStream decompress(final InputStream input, final int version) throws IOException {
        try {
            if (version == 1) {
                return new GZIPInputStream(input);
            }
            if (version == 2) {
                return new InflaterInputStream(input);
            }
            if (version == 3) {
                return input;
            }
            //#if MC>=12005
            //$$ if (version == 4) {
            //$$     return new LZ4BlockInputStream(input);
            //$$ }
            //#endif
            throw new IOException("unsupported Anvil compression version " + version);
        } catch (final IOException | RuntimeException e) {
            try {
                input.close();
            } catch (final IOException ignored) {
                // Preserve the decompressor construction failure.
            }
            throw e;
        }
    }

    private static ChunkBytes readChunkBytes(final InputStream input) throws IOException {
        final ChunkBuffer output = new ChunkBuffer();
        final byte[] buffer = new byte[16 * 1_024];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) {
                continue;
            }
            total += read;
            if (total > MAX_DECOMPRESSED_CHUNK_BYTES) {
                throw new IOException("decompressed chunk NBT exceeds safety cap");
            }
            output.write(buffer, 0, read);
        }
        return output.result();
    }

    private static boolean readFully(
        final FileChannel file,
        final ByteBuffer target,
        final long offset
    ) throws IOException {
        long position = offset;
        while (target.hasRemaining()) {
            final int read = file.read(target, position);
            if (read < 0) {
                return false;
            }
            if (read == 0) {
                continue;
            }
            position += read;
        }
        return true;
    }
}
