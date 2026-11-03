package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.core.net.SummaryCodec;
import cn.net.rms.confluxmap.core.util.ChunkRegionSlice;
import cn.net.rms.confluxmap.nativepredict.NativeChunkNbtScanner;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
//#if MC>=12005
//$$ import net.jpountz.lz4.LZ4BlockInputStream;
//#endif
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;

/** Reads one Anvil file once and produces all four 16x16-chunk summary regions it contains. */
public final class AnvilMcaScanner {
    private static final int CHUNKS_PER_SIDE = AnvilRegionReader.CHUNKS_PER_SIDE;
    private static final int CHUNKS_PER_MCA = CHUNKS_PER_SIDE * CHUNKS_PER_SIDE;
    private static final int SUMMARY_REGIONS_PER_SIDE = 2;
    //#if MC>=12005
    //$$ private static final AnvilRegionReader.Lz4Stream LZ4 = LZ4BlockInputStream::new;
    //#else
    private static final AnvilRegionReader.Lz4Stream LZ4 = null;
    //#endif
    private final boolean nativeEnabled;

    public AnvilMcaScanner() {
        this(true);
    }

    public AnvilMcaScanner(final boolean nativeEnabled) {
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
        return scan(path, mcaX, mcaZ, sourceMcaMtimeMs, lod, null, summarizer);
    }

    /** Parses only the requested crop while still reading the MCA location table once. */
    public SummaryCodec.SampledRegion scanRegion(
        final Path path,
        final int mcaX,
        final int mcaZ,
        final long sourceMcaMtimeMs,
        final int lod,
        final ChunkRegionSlice slice,
        final ChunkSummarizer summarizer
    ) {
        if (slice == null || Math.floorDiv(slice.regionX(), 2) != mcaX
            || Math.floorDiv(slice.regionZ(), 2) != mcaZ) {
            throw new IllegalArgumentException("region slice does not belong to MCA");
        }
        final Scan scan = scan(path, mcaX, mcaZ, sourceMcaMtimeMs, lod, slice, summarizer);
        return scan == null ? null : scan.region(slice.regionX(), slice.regionZ());
    }

    private Scan scan(
        final Path path,
        final int mcaX,
        final int mcaZ,
        final long sourceMcaMtimeMs,
        final int lod,
        final ChunkRegionSlice slice,
        final ChunkSummarizer summarizer
    ) {
        final int sampleStride = 1 << lod;
        final SummaryCodec.SampledChunk[][] chunks = new SummaryCodec.SampledChunk[4][SummaryCodec.CHUNKS];
        for (final SummaryCodec.SampledChunk[] region : chunks) {
            Arrays.fill(region, SummaryCodec.SampledChunk.empty(sampleStride));
        }

        try (FileChannel file = FileChannel.open(path, StandardOpenOption.READ)) {
            final ByteBuffer locations = AnvilRegionReader.readLocationTable(file);
            if (locations == null) {
                return null;
            }
            final long fileSize = file.size();
            for (int mcaChunkIndex = 0; mcaChunkIndex < CHUNKS_PER_MCA; mcaChunkIndex++) {
                final int location = locations.getInt();
                if (location == 0) {
                    continue;
                }
                final int localX = mcaChunkIndex % CHUNKS_PER_SIDE;
                final int localZ = mcaChunkIndex / CHUNKS_PER_SIDE;
                if (slice != null && !contains(slice, mcaX, mcaZ, localX, localZ)) {
                    continue;
                }
                final SummaryCodec.SampledChunk summary = scanChunk(
                    file, fileSize, path.getParent(), mcaX, mcaZ, mcaChunkIndex,
                    location, lod, summarizer
                );
                if (summary == null) {
                    continue;
                }
                final int regionIndex = (localZ >>> 4) * SUMMARY_REGIONS_PER_SIDE + (localX >>> 4);
                final int regionChunkIndex = (localZ & 15) * 16 + (localX & 15);
                chunks[regionIndex][regionChunkIndex] = summary;
            }
        } catch (final IOException e) {
            // Corrupt or concurrently modified Anvil files are routine; stay quiet and fall back.
            return null;
        } catch (final RuntimeException e) {
            ConfluxMapMod.LOGGER.debug(
                "Unexpected Anvil scan failure for {} (mca {}.{})", path, mcaX, mcaZ, e);
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

    private static boolean contains(
        final ChunkRegionSlice slice,
        final int mcaX,
        final int mcaZ,
        final int localMcaChunkX,
        final int localMcaChunkZ
    ) {
        final int summaryRegionX = mcaX * 2 + (localMcaChunkX >>> 4);
        final int summaryRegionZ = mcaZ * 2 + (localMcaChunkZ >>> 4);
        final int localRegionChunkX = localMcaChunkX & 15;
        final int localRegionChunkZ = localMcaChunkZ & 15;
        return summaryRegionX == slice.regionX() && summaryRegionZ == slice.regionZ()
            && localRegionChunkX >= slice.minLocalChunkX()
            && localRegionChunkX <= slice.maxLocalChunkX()
            && localRegionChunkZ >= slice.minLocalChunkZ()
            && localRegionChunkZ <= slice.maxLocalChunkZ();
    }

    private SummaryCodec.SampledChunk scanChunk(
        final FileChannel file,
        final long fileSize,
        final Path regionDirectory,
        final int mcaX,
        final int mcaZ,
        final int mcaChunkIndex,
        final int location,
        final int lod,
        final ChunkSummarizer summarizer
    ) {
        final int chunkX = mcaX * CHUNKS_PER_SIDE + mcaChunkIndex % CHUNKS_PER_SIDE;
        final int chunkZ = mcaZ * CHUNKS_PER_SIDE + mcaChunkIndex / CHUNKS_PER_SIDE;
        try {
            final byte[] rawNbt = AnvilRegionReader.readChunkNbt(
                file, fileSize, regionDirectory, chunkX, chunkZ, location, LZ4
            );
            if (rawNbt == null) {
                return null;
            }
            final NativeChunkNbtScanner.Chunk nativeChunk = nativeEnabled
                ? NativeChunkNbtScanner.scan(rawNbt, rawNbt.length, lod)
                : null;
            if (nativeChunk != null) {
                return summarizer.summarizeNative(nativeChunk);
            }
            final NbtCompound nbt = NbtIo.read(new DataInputStream(new ByteArrayInputStream(rawNbt)));
            return nbt == null ? null : summarizer.summarizeForLod(nbt, lod);
        } catch (final IOException e) {
            // Corrupt or concurrently modified Anvil files are routine; stay quiet and fall back.
            return null;
        } catch (final RuntimeException e) {
            ConfluxMapMod.LOGGER.debug(
                "Unexpected Anvil chunk scan failure for chunk {},{} in {}",
                chunkX, chunkZ, regionDirectory, e);
            return null;
        }
    }
}
