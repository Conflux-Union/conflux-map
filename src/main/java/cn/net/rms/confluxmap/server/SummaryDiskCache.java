package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.core.net.ProtoException;
import cn.net.rms.confluxmap.core.net.SummaryCodec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Persistent region-summary cache; corrupt entries are quarantined and treated as cold. */
public final class SummaryDiskCache {
    private final Path root;

    public SummaryDiskCache(final Path worldFolder) {
        this.root = worldFolder.resolve("confluxmap").resolve("summary");
    }

    public SummaryCodec.Region load(final String dimension, final int regionX, final int regionZ) {
        final Path path = pathFor(dimension, regionX, regionZ);
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            final SummaryCodec.Region region = SummaryCodec.decode(Files.readAllBytes(path));
            if (region.rx() != regionX || region.rz() != regionZ) {
                throw new ProtoException("summary coordinates do not match file name");
            }
            return region;
        } catch (IOException | ProtoException e) {
            quarantine(path);
            return null;
        }
    }

    public void save(final String dimension, final SummaryCodec.Region region) throws IOException {
        final Path path = pathFor(dimension, region.rx(), region.rz());
        Files.createDirectories(path.getParent());
        final Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.write(tmp, SummaryCodec.encode(region));
        try {
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public boolean isStale(final String dimension, final int regionX, final int regionZ, final long sourceMcaMtimeMs) {
        final SummaryCodec.Region region = load(dimension, regionX, regionZ);
        return region == null || sourceMcaMtimeMs > region.sourceMcaMtimeMs();
    }

    private Path pathFor(final String dimension, final int x, final int z) {
        return root.resolve(sanitize(dimension)).resolve("r." + x + "." + z + ".cfs");
    }

    private static String sanitize(final String value) {
        final String safe = value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.startsWith(".") ? "_" + safe.replaceFirst("^\\.+", "") : safe;
    }

    private static void quarantine(final Path path) {
        try {
            Files.move(path, path.resolveSibling(path.getFileName() + ".bad"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // Cache corruption must not affect world loading.
        }
    }
}
