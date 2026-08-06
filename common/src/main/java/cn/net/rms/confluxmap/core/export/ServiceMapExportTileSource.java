package cn.net.rms.confluxmap.core.export;

import cn.net.rms.confluxmap.core.model.TileKey;
import cn.net.rms.confluxmap.core.predict.PredictedTileKeys;
import cn.net.rms.confluxmap.core.predict.PredictionTileService;
import cn.net.rms.confluxmap.core.tile.TileService;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** Binds export to the local-real plane and the selected prediction/sync composition plane. */
public final class ServiceMapExportTileSource implements MapExportTileSource {
    private final TileService realTiles;
    private final PredictionTileService predictedTiles;
    private final MapExportRequest request;
    private final Function<TileKey, CompletableFuture<MapExportLoadState>> loadStates;

    public ServiceMapExportTileSource(
        final TileService realTiles,
        final PredictionTileService predictedTiles,
        final MapExportRequest request
    ) {
        this(
            realTiles,
            predictedTiles,
            request,
            ignored -> CompletableFuture.completedFuture(request.loadState())
        );
    }

    public ServiceMapExportTileSource(
        final TileService realTiles,
        final PredictionTileService predictedTiles,
        final MapExportRequest request,
        final Function<TileKey, CompletableFuture<MapExportLoadState>> loadStates
    ) {
        this.realTiles = Objects.requireNonNull(realTiles, "realTiles");
        this.predictedTiles = Objects.requireNonNull(predictedTiles, "predictedTiles");
        this.request = Objects.requireNonNull(request, "request");
        this.loadStates = Objects.requireNonNull(loadStates, "loadStates");
    }

    @Override
    public CompletableFuture<MapExportTile> snapshot(final TileKey displayKey) {
        final CompletableFuture<int[]> real = realTiles.snapshotTile(
            displayKey,
            request.dynamicLighting(),
            request.daylightFactor()
        );
        final CompletableFuture<int[]> predicted = request.predictionActive()
            ? predictedTiles.snapshotTile(
                PredictedTileKeys.toPredicted(displayKey), request.predictionMode()
            )
            : CompletableFuture.completedFuture(null);
        final CompletableFuture<MapExportLoadState> loadState = loadStates.apply(displayKey);
        final CompletableFuture<MapExportTile> result = real.thenCombine(predicted, PixelPlanes::new)
            .thenCombine(loadState, (pixels, plane) -> new MapExportTile(
                pixels.real(), pixels.predicted(), plane
            ));
        result.whenComplete((ignored, error) -> {
            if (result.isCancelled()) {
                real.cancel(true);
                predicted.cancel(true);
                loadState.cancel(true);
            }
        });
        return result;
    }

    private record PixelPlanes(int[] real, int[] predicted) {
    }
}
