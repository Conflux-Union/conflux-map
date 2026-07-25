package cn.net.rms.confluxmap.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Resolves vanilla Anvil region files without loading or generating chunks. */
final class RegionStoragePaths {
    private RegionStoragePaths() {
    }

    static long mcaMtimeMs(
        final Path worldRoot,
        final String dimension,
        final int regionX,
        final int regionZ
    ) {
        final Path path = regionDirectory(worldRoot, dimension)
            .resolve("r." + regionX + "." + regionZ + ".mca");
        if (!Files.isRegularFile(path)) {
            return 0L;
        }
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (final IOException e) {
            return 0L;
        }
    }

    static Path regionDirectory(final Path worldRoot, final String dimension) {
        if ("minecraft:overworld".equals(dimension)) {
            return worldRoot.resolve("region");
        }
        if ("minecraft:the_nether".equals(dimension)) {
            return worldRoot.resolve("DIM-1").resolve("region");
        }
        if ("minecraft:the_end".equals(dimension)) {
            return worldRoot.resolve("DIM1").resolve("region");
        }
        final String[] id = dimension == null ? new String[0] : dimension.split(":", 2);
        final String namespace = id.length == 2 && safeSegment(id[0]) ? id[0] : "unknown";
        Path path = worldRoot.resolve("dimensions").resolve(namespace);
        if (id.length == 2) {
            for (final String segment : id[1].split("/")) {
                path = path.resolve(safeSegment(segment) ? segment : "unknown");
            }
        } else {
            path = path.resolve("unknown");
        }
        return path.resolve("region");
    }

    private static boolean safeSegment(final String value) {
        return value != null && !value.isEmpty() && !".".equals(value) && !"..".equals(value)
            && value.matches("[a-z0-9._-]+");
    }
}
