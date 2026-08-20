package cn.net.rms.confluxmap.core.cache;

import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.SampleSource;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.logging.log4j.Logger;

/** Explicit, loss-averse merge of one client map cache into another world namespace. */
public final class MapCacheMigration {
    private static final Pattern REGION_FILE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.cfr");

    public enum Status {
        APPLIED,
        SOURCE_NOT_FOUND,
        SOURCE_IS_TARGET,
        FAILED
    }

    public record Result(
        Status status,
        int copiedRegions,
        int mergedRegions,
        int migratedChunks
    ) {
    }

    private MapCacheMigration() {
    }

    /**
     * Merges persistent region files from {@code source} into {@code target} without removing the
     * source namespace. Existing real data in the target wins; source data fills unknown or
     * predicted chunks only. Every target write is atomic, so an interrupted file cannot leave a
     * half-written region behind.
     */
    public static Result merge(
        final Path root,
        final WorldIdentity source,
        final WorldIdentity target,
        final Logger logger
    ) {
        final Path sourceRoot = namespace(root, source);
        final Path targetRoot = namespace(root, target);
        if (source.equals(target) || sourceRoot.equals(targetRoot)) {
            return result(Status.SOURCE_IS_TARGET);
        }
        if (!Files.isDirectory(sourceRoot)) {
            return result(Status.SOURCE_NOT_FOUND);
        }

        int copiedRegions = 0;
        int mergedRegions = 0;
        int migratedChunks = 0;
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (final Path sourceFile : files.filter(Files::isRegularFile).toList()) {
                final Matcher matcher = REGION_FILE.matcher(sourceFile.getFileName().toString());
                if (!matcher.matches()) {
                    continue;
                }
                final Path layerPath = sourceFile.getParent();
                if (layerPath == null) {
                    continue;
                }
                final MapLayer layer;
                try {
                    layer = MapLayer.parse(layerPath.getFileName().toString());
                } catch (final IllegalArgumentException ignored) {
                    logger.warn("Skipping map cache file with unknown layer {}", sourceFile);
                    continue;
                }
                if (!layer.type().persistent()) {
                    continue;
                }
                final int regionX = Integer.parseInt(matcher.group(1));
                final int regionZ = Integer.parseInt(matcher.group(2));
                final Path targetFile = targetRoot.resolve(sourceRoot.relativize(sourceFile));
                if (!Files.exists(targetFile)) {
                    copyAtomic(sourceFile, targetFile);
                    copiedRegions++;
                    migratedChunks += countRealChunks(sourceFile, regionX, regionZ, layer.type().ordinal());
                    continue;
                }

                final RegionFileCodec.RegionData sourceData = read(sourceFile, regionX, regionZ, layer.type().ordinal());
                final RegionFileCodec.RegionData targetData = read(targetFile, regionX, regionZ, layer.type().ordinal());
                final MergeResult merged = mergeRegion(targetData, sourceData);
                if (merged.migratedChunks() > 0) {
                    writeAtomic(targetFile, layer.type().ordinal(), merged.data());
                    mergedRegions++;
                    migratedChunks += merged.migratedChunks();
                }
            }
            return new Result(Status.APPLIED, copiedRegions, mergedRegions, migratedChunks);
        } catch (final IOException | RuntimeException e) {
            logger.error("Could not merge map cache from {} to {}", sourceRoot, targetRoot, e);
            return new Result(Status.FAILED, copiedRegions, mergedRegions, migratedChunks);
        }
    }

    private static Path namespace(final Path root, final WorldIdentity world) {
        return root.resolve(world.serverId()).resolve(world.worldId());
    }

    private static RegionFileCodec.RegionData read(
        final Path file,
        final int regionX,
        final int regionZ,
        final int layerOrdinal
    ) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            try {
                return RegionFileCodec.decode(input, regionX, regionZ, layerOrdinal);
            } catch (final RegionFileCodec.RegionFileException e) {
                throw new IOException("invalid map cache region " + file, e);
            }
        }
    }

    private static int countRealChunks(
        final Path file,
        final int regionX,
        final int regionZ,
        final int layerOrdinal
    ) throws IOException {
        final RegionFileCodec.RegionData data = read(file, regionX, regionZ, layerOrdinal);
        int count = 0;
        for (final byte source : data.chunkSourceOrdinal()) {
            if (SampleSource.byOrdinal(source).priority() >= SampleSource.REAL_CACHED.priority()) {
                count++;
            }
        }
        return count;
    }

    private static MergeResult mergeRegion(
        final RegionFileCodec.RegionData target,
        final RegionFileCodec.RegionData source
    ) {
        final byte[] chunkSource = target.chunkSourceOrdinal().clone();
        final int[] chunkUpdateSeconds = target.chunkUpdateEpochSeconds().clone();
        final long[] chunkSourceRevision = target.chunkSourceRevision().clone();
        final short[] surfaceY = target.surfaceY().clone();
        final byte[] fluidDepth = target.fluidDepth().clone();
        final byte[] kind = target.kind().clone();
        final String[] biomeId = target.biomeId().clone();
        final int[] baseArgb = target.baseArgb().clone();
        final int[] xaeroBaseArgb = target.xaeroBaseArgb().clone();
        final int[] biomeTint = target.biomeTint().clone();
        final int[] overlayArgb = target.overlayArgb().clone();
        final byte[] light = target.light().clone();
        int migratedChunks = 0;

        for (int chunkIndex = 0; chunkIndex < RegionFileCodec.CHUNK_TABLE_ENTRIES; chunkIndex++) {
            final SampleSource existing = SampleSource.byOrdinal(chunkSource[chunkIndex]);
            final SampleSource incoming = SampleSource.byOrdinal(source.chunkSourceOrdinal()[chunkIndex]);
            if (incoming.priority() <= existing.priority()
                || existing.priority() >= SampleSource.REAL_CACHED.priority()) {
                continue;
            }
            copyChunk(
                chunkIndex, source, surfaceY, fluidDepth, kind, biomeId,
                baseArgb, xaeroBaseArgb, biomeTint, overlayArgb, light
            );
            chunkSource[chunkIndex] = source.chunkSourceOrdinal()[chunkIndex];
            chunkUpdateSeconds[chunkIndex] = source.chunkUpdateEpochSeconds()[chunkIndex];
            chunkSourceRevision[chunkIndex] = source.chunkSourceRevision()[chunkIndex];
            migratedChunks++;
        }
        if (migratedChunks == 0) {
            return new MergeResult(target, 0);
        }
        return new MergeResult(
            new RegionFileCodec.RegionData(
                target.rx(), target.rz(), Math.max(target.lastWriteEpochMs(), source.lastWriteEpochMs()),
                chunkSource, chunkUpdateSeconds, chunkSourceRevision,
                surfaceY, fluidDepth, kind, biomeId,
                baseArgb, xaeroBaseArgb, biomeTint, overlayArgb, light
            ),
            migratedChunks
        );
    }

    private static void copyChunk(
        final int chunkIndex,
        final RegionFileCodec.RegionData source,
        final short[] surfaceY,
        final byte[] fluidDepth,
        final byte[] kind,
        final String[] biomeId,
        final int[] baseArgb,
        final int[] xaeroBaseArgb,
        final int[] biomeTint,
        final int[] overlayArgb,
        final byte[] light
    ) {
        final int chunkX = chunkIndex % 16;
        final int chunkZ = chunkIndex / 16;
        final int baseX = chunkX * 16;
        final int baseZ = chunkZ * 16;
        for (int z = 0; z < 16; z++) {
            final int from = (baseZ + z) * 256 + baseX;
            final int to = from;
            System.arraycopy(source.surfaceY(), from, surfaceY, to, 16);
            System.arraycopy(source.fluidDepth(), from, fluidDepth, to, 16);
            System.arraycopy(source.kind(), from, kind, to, 16);
            System.arraycopy(source.biomeId(), from, biomeId, to, 16);
            System.arraycopy(source.baseArgb(), from, baseArgb, to, 16);
            System.arraycopy(source.xaeroBaseArgb(), from, xaeroBaseArgb, to, 16);
            System.arraycopy(source.biomeTint(), from, biomeTint, to, 16);
            System.arraycopy(source.overlayArgb(), from, overlayArgb, to, 16);
            System.arraycopy(source.light(), from, light, to, 16);
        }
    }

    private static void copyAtomic(final Path source, final Path target) throws IOException {
        Files.createDirectories(target.getParent());
        final Path temporary = target.resolveSibling(target.getFileName() + ".migrate.tmp");
        Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
        move(temporary, target);
    }

    private static void writeAtomic(
        final Path target,
        final int layerOrdinal,
        final RegionFileCodec.RegionData data
    ) throws IOException {
        Files.createDirectories(target.getParent());
        final Path temporary = target.resolveSibling(target.getFileName() + ".migrate.tmp");
        try (OutputStream output = Files.newOutputStream(temporary)) {
            RegionFileCodec.encode(output, layerOrdinal, data);
        }
        move(temporary, target);
    }

    private static void move(final Path temporary, final Path target) throws IOException {
        try {
            Files.move(
                temporary, target,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
            );
        } catch (final AtomicMoveNotSupportedException e) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Result result(final Status status) {
        return new Result(status, 0, 0, 0);
    }

    private record MergeResult(RegionFileCodec.RegionData data, int migratedChunks) {
    }
}
