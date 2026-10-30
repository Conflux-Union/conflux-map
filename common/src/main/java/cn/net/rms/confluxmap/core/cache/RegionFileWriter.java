package cn.net.rms.confluxmap.core.cache;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.apache.logging.log4j.Logger;

/**
 * Atomic region-file writer shared by the live session flush and the terrain
 * import: tmp file, encode, fsync, atomic move with a non-atomic fallback.
 */
public final class RegionFileWriter {
    private RegionFileWriter() {
    }

    /** Returns true when the region file was written; failures are logged, not thrown. */
    public static boolean writeAtomic(
        final Path file,
        final RegionFileCodec.RegionData data,
        final int layerOrdinal,
        final Logger logger
    ) {
        try {
            Files.createDirectories(file.getParent());
            final Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp.toFile())) {
                RegionFileCodec.encode(fos, layerOrdinal, data);
                fos.flush();
                fos.getChannel().force(true);
            }
            move(tmp, file);
            return true;
        } catch (final IOException e) {
            logger.warn("cache: failed to write region file {} ({})", file, e.toString());
            return false;
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
