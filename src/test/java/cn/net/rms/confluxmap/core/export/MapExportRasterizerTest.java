package cn.net.rms.confluxmap.core.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.loadstate.FullscreenDisplayMode;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.TileKey;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.predict.PredictionViewMode;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MapExportRasterizerTest {
    @TempDir
    Path temp;

    @Test
    void stitchesGloballyAlignedTilePixelsAcrossNegativeAndPositiveTiles() throws Exception {
        final SessionGuard.Session session = new SessionGuard.Session(
            7L, new WorldIdentity("server", "world"), DimensionId.OVERWORLD
        );
        final MapExportRequest request = new MapExportRequest(
            session,
            MapLayer.SURFACE,
            MapExportBounds.between(-1, 0, 256, 0),
            MapExportResolution.ONE_BLOCK,
            FullscreenDisplayMode.TERRAIN,
            false,
            PredictionViewMode.EVERYWHERE,
            0xFFFFFFFF,
            0xFF101018,
            true,
            0.75f,
            MapExportLoadState.empty()
        );
        final List<TileKey> requested = new ArrayList<>();
        final MapExportTileSource source = key -> {
            requested.add(key);
            final int[] real = new int[256 * 256];
            for (int x = 0; x < 256; x++) {
                real[x] = 0xFF000000 | (key.tileX() & 0xFF) << 8 | x;
            }
            return CompletableFuture.completedFuture(new MapExportTile(real, null));
        };
        final Path spool = temp.resolve("map.argb");

        MapExportRasterizer.rasterize(request, source, spool, (done, total) -> {}, () -> false);

        assertEquals(List.of(-1, 0, 1), requested.stream().map(TileKey::tileX).toList());
        try (DataInputStream in = new DataInputStream(Files.newInputStream(spool))) {
            assertEquals(0xFF00FFFF, in.readInt());
            assertEquals(0xFF000000, in.readInt());
            for (int x = 1; x < 256; x++) {
                in.readInt();
            }
            assertEquals(0xFF000100, in.readInt());
        }
    }

    @Test
    void biomeModeRequestsBiomePlaneAndCompositesPrediction() throws Exception {
        final SessionGuard.Session session = new SessionGuard.Session(
            9L, new WorldIdentity("server", "world"), DimensionId.OVERWORLD
        );
        final MapExportRequest request = new MapExportRequest(
            session,
            MapLayer.SURFACE,
            MapExportBounds.between(0, 0, 0, 0),
            MapExportResolution.ONE_BLOCK,
            FullscreenDisplayMode.BIOME,
            true,
            PredictionViewMode.GENERATED_ONLY,
            0xFFFFFFFF,
            0xFF101018,
            false,
            1.0f,
            MapExportLoadState.empty()
        );
        final Path spool = temp.resolve("biome.argb");
        final MapExportTileSource source = key -> {
            assertTrue(key.layerId().endsWith("!biome"));
            final int[] real = new int[256 * 256];
            final int[] predicted = new int[256 * 256];
            predicted[0] = 0xFF336699;
            return CompletableFuture.completedFuture(new MapExportTile(real, predicted));
        };

        MapExportRasterizer.rasterize(request, source, spool, (done, total) -> {}, () -> false);

        try (DataInputStream in = new DataInputStream(Files.newInputStream(spool))) {
            assertEquals(0xFF336699, in.readInt());
        }
    }
}
