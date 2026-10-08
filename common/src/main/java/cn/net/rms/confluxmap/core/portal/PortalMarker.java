package cn.net.rms.confluxmap.core.portal;

import cn.net.rms.confluxmap.core.model.DimensionId;
import java.util.Objects;

/**
 * One activated portal, tracked at chunk granularity: the identity is
 * (dimension, chunk, kind) so a portal spanning a chunk boundary simply
 * produces one marker per chunk that contains its blocks, and dismantling
 * detection is a rescan of that chunk. The anchor is the first tracked block
 * found by a deterministic bottom-up scan and is where the map icon sits.
 */
public record PortalMarker(
    DimensionId dimension,
    int chunkX,
    int chunkZ,
    PortalKind kind,
    int anchorX,
    int anchorZ
) {
    public PortalMarker {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(kind, "kind");
    }
}
