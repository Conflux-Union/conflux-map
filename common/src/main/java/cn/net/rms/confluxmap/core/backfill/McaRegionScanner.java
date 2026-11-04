package cn.net.rms.confluxmap.core.backfill;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.net.SummaryCodec;

/**
 * Platform seam for reading one summary region out of the world save's Anvil
 * files. Implemented on the Minecraft side (save-path resolution, NBT
 * decoding, native scan); the backfill service in this package is game-free
 * and testable against fakes.
 */
public interface McaRegionScanner {
    /**
     * Current mtime of the MCA file backing this summary region, {@code 0}
     * when the file is absent or unreadable.
     */
    long mtimeMs(DimensionId dimension, int regionX, int regionZ);

    /**
     * Scans the region's chunks at full column density (stride 1). Returns
     * {@code null} when the file cannot be read; generated chunks carry real
     * columns, ungenerated chunks come back empty. Implementations should
     * verify the file mtime still equals {@code expectedMtimeMs} after the
     * read and reject the result otherwise.
     */
    SummaryCodec.SampledRegion scanRegion(
        DimensionId dimension, int regionX, int regionZ, long expectedMtimeMs
    );
}
