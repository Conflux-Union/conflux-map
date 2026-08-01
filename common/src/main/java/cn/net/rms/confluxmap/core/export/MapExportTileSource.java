package cn.net.rms.confluxmap.core.export;

import cn.net.rms.confluxmap.core.model.TileKey;
import java.util.concurrent.CompletableFuture;

/** Asynchronous CPU tile seam used by the bounded export rasterizer. */
@FunctionalInterface
public interface MapExportTileSource {
    CompletableFuture<MapExportTile> snapshot(TileKey displayKey);
}
