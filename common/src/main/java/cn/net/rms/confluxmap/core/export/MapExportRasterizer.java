package cn.net.rms.confluxmap.core.export;

import cn.net.rms.confluxmap.core.loadstate.FullscreenDisplayMode;
import cn.net.rms.confluxmap.core.model.TileKey;
import cn.net.rms.confluxmap.core.tile.BiomeTileKeys;
import cn.net.rms.confluxmap.core.util.TileMath;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/** Stitches bounded CPU tile snapshots into a disk-backed row-major ARGB raster. */
public final class MapExportRasterizer {
    @FunctionalInterface
    public interface Progress {
        void update(long completedTiles, long totalTiles);
    }

    private MapExportRasterizer() {
    }

    public static void rasterize(
        final MapExportRequest request,
        final MapExportTileSource source,
        final Path spool,
        final Progress progress,
        final BooleanSupplier cancelled
    ) throws IOException {
        final int lod = request.resolution().lod();
        final int blocksPerPixel = request.resolution().blocksPerPixel();
        final int width = request.pixelWidth();
        final int height = request.pixelHeight();
        final long lastBlockX = request.bounds().minX() + (long) (width - 1) * blocksPerPixel;
        final long lastBlockZ = request.bounds().minZ() + (long) (height - 1) * blocksPerPixel;
        final int minTileX = TileMath.blockToTile(request.bounds().minX(), lod);
        final int maxTileX = TileMath.blockToTile((int) lastBlockX, lod);
        final int minTileZ = TileMath.blockToTile(request.bounds().minZ(), lod);
        final int maxTileZ = TileMath.blockToTile((int) lastBlockZ, lod);
        final long totalTiles = Math.multiplyExact(
            (long) maxTileX - minTileX + 1L,
            (long) maxTileZ - minTileZ + 1L
        );

        try (FileChannel out = FileChannel.open(
            spool,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE
        )) {
            writeFully(out, ByteBuffer.wrap(new byte[] {0}), request.spoolBytes() - 1L);
            long completed = 0L;
            for (int tileZ = minTileZ; tileZ <= maxTileZ; tileZ++) {
                for (int tileX = minTileX; tileX <= maxTileX; tileX++) {
                    checkCancelled(cancelled);
                    final TileKey terrainKey = new TileKey(
                        request.session().world(),
                        request.session().dimension(),
                        request.layer().cacheId(),
                        lod,
                        tileX,
                        tileZ
                    );
                    final TileKey displayKey = request.displayMode() == FullscreenDisplayMode.BIOME
                        ? BiomeTileKeys.toBiome(terrainKey)
                        : terrainKey;
                    final MapExportTile tile = awaitTile(
                        source.snapshot(displayKey), cancelled
                    );
                    writeTile(out, request, tile, width, tileX, tileZ);
                    completed++;
                    progress.update(completed, totalTiles);
                }
            }
        } catch (final RuntimeException | IOException e) {
            java.nio.file.Files.deleteIfExists(spool);
            throw e;
        }
    }

    private static void writeTile(
        final FileChannel out,
        final MapExportRequest request,
        final MapExportTile tile,
        final int outputWidth,
        final int tileX,
        final int tileZ
    ) throws IOException {
        final int lod = request.resolution().lod();
        final int blocksPerPixel = request.resolution().blocksPerPixel();
        final long tileOriginX = (long) tileX * TileMath.blocksPerTile(lod);
        final long tileOriginZ = (long) tileZ * TileMath.blocksPerTile(lod);
        final int startX = clampOutputStart(tileOriginX, request.bounds().minX(), blocksPerPixel, request.pixelWidth());
        final int endX = clampOutputEnd(
            tileOriginX + TileMath.blocksPerTile(lod) - 1L,
            request.bounds().minX(), blocksPerPixel, request.pixelWidth()
        );
        final int startY = clampOutputStart(tileOriginZ, request.bounds().minZ(), blocksPerPixel, request.pixelHeight());
        final int endY = clampOutputEnd(
            tileOriginZ + TileMath.blocksPerTile(lod) - 1L,
            request.bounds().minZ(), blocksPerPixel, request.pixelHeight()
        );
        final int outputTileWidth = endX - startX + 1;
        final int[] drawings = MapExportAnnotationRasterizer.rasterize(
            request, startX, startY, endX, endY
        );
        final ByteBuffer row = ByteBuffer.allocate(outputTileWidth * 4);
        for (int outputY = startY; outputY <= endY; outputY++) {
            row.clear();
            final int blockZ = (int) (request.bounds().minZ() + (long) outputY * blocksPerPixel);
            final int tilePixelZ = TileMath.blockToPixelInTile(blockZ, lod);
            for (int outputX = startX; outputX <= endX; outputX++) {
                final int blockX = (int) (request.bounds().minX() + (long) outputX * blocksPerPixel);
                final int tilePixelX = TileMath.blockToPixelInTile(blockX, lod);
                final int tileIndex = tilePixelZ * TileMath.TILE_SIZE + tilePixelX;
                final int predicted = tile.predicted() == null ? 0 : tile.predicted()[tileIndex];
                final int overlay = request.displayMode() == FullscreenDisplayMode.CHUNK_LOAD_STATE
                    ? tile.loadState().overlayAt(blockX, blockZ, request.resolution())
                    : 0;
                final int drawing = drawings == null ? 0
                    : drawings[(outputY - startY) * outputTileWidth + outputX - startX];
                row.putInt(MapExportCompositor.compose(
                    request.background(), predicted, tile.real()[tileIndex],
                    request.predictionTint(), overlay, drawing
                ));
            }
            row.flip();
            final long outputPixel = (long) outputY * outputWidth + startX;
            writeFully(out, row, outputPixel * 4L);
        }
    }

    private static void writeFully(
        final FileChannel out,
        final ByteBuffer bytes,
        final long position
    ) throws IOException {
        long offset = position;
        while (bytes.hasRemaining()) {
            final int written = out.write(bytes, offset);
            if (written <= 0) {
                throw new IOException("Unable to make progress writing export raster");
            }
            offset += written;
        }
    }

    private static int clampOutputStart(
        final long tileMin,
        final int selectionMin,
        final int blocksPerPixel,
        final int outputSize
    ) {
        return (int) Math.max(0L, Math.min(
            outputSize - 1L,
            ceilDiv(tileMin - selectionMin, blocksPerPixel)
        ));
    }

    private static int clampOutputEnd(
        final long tileMax,
        final int selectionMin,
        final int blocksPerPixel,
        final int outputSize
    ) {
        return (int) Math.max(0L, Math.min(
            outputSize - 1L,
            Math.floorDiv(tileMax - selectionMin, blocksPerPixel)
        ));
    }

    private static long ceilDiv(final long value, final long divisor) {
        return -Math.floorDiv(-value, divisor);
    }

    private static MapExportTile awaitTile(
        final java.util.concurrent.CompletableFuture<MapExportTile> future,
        final BooleanSupplier cancelled
    ) {
        while (true) {
            if (cancelled.getAsBoolean()) {
                future.cancel(true);
                throw new CancellationException("Map export cancelled");
            }
            try {
                return future.get(100L, TimeUnit.MILLISECONDS);
            } catch (final TimeoutException ignored) {
                // Keep cancellation responsive while disk/native/network work is pending.
            } catch (final InterruptedException e) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw new CancellationException("Map export interrupted");
            } catch (final ExecutionException e) {
                if (e.getCause() instanceof final CancellationException cancellation) {
                    throw cancellation;
                }
                if (e.getCause() instanceof final RuntimeException runtime) {
                    throw runtime;
                }
                throw new IllegalStateException("Map tile snapshot failed", e.getCause());
            }
        }
    }

    private static void checkCancelled(final BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("Map export cancelled");
        }
    }
}
