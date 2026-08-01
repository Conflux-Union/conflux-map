package cn.net.rms.confluxmap.core.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.annotation.Annotation;
import cn.net.rms.confluxmap.core.annotation.AnnotationPersistence;
import cn.net.rms.confluxmap.core.annotation.AnnotationPoint;
import cn.net.rms.confluxmap.core.annotation.AnnotationStyle;
import cn.net.rms.confluxmap.core.annotation.CircleAnnotationGeometry;
import cn.net.rms.confluxmap.core.annotation.FreehandAnnotationGeometry;
import cn.net.rms.confluxmap.core.annotation.LineAnnotationGeometry;
import cn.net.rms.confluxmap.core.annotation.RectangleAnnotationGeometry;
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
import java.util.UUID;
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

    @Test
    void drawingSettingControlsWhetherAnnotationsAreComposited() throws Exception {
        final SessionGuard.Session session = new SessionGuard.Session(
            11L, new WorldIdentity("server", "world"), DimensionId.OVERWORLD
        );
        final MapExportRequest base = new MapExportRequest(
            session,
            MapLayer.SURFACE,
            MapExportBounds.between(0, 0, 4, 4),
            MapExportResolution.ONE_BLOCK,
            FullscreenDisplayMode.TERRAIN,
            false,
            PredictionViewMode.EVERYWHERE,
            0xFFFFFFFF,
            0xFF101018,
            false,
            1.0f,
            MapExportLoadState.empty()
        );
        final Annotation line = new Annotation(
            UUID.fromString("00000000-0000-0000-0000-000000000011"),
            DimensionId.OVERWORLD,
            new LineAnnotationGeometry(new AnnotationPoint(1.0, 2.0), new AnnotationPoint(4.0, 2.0)),
            new AnnotationStyle(0xFFE74C3C),
            "",
            AnnotationPersistence.TRANSIENT,
            1L
        );
        final Path withoutDrawings = temp.resolve("without-drawings.argb");
        final Path withDrawings = temp.resolve("with-drawings.argb");
        final MapExportTileSource source = key -> CompletableFuture.completedFuture(
            new MapExportTile(new int[256 * 256], null)
        );

        MapExportRasterizer.rasterize(
            base, source, withoutDrawings, (done, total) -> {}, () -> false
        );
        MapExportRasterizer.rasterize(
            base.withAnnotations(List.of(line)).withSelection(
                MapExportBounds.between(0, 0, 4, 4), MapExportResolution.ONE_BLOCK
            ),
            source,
            withDrawings,
            (done, total) -> {},
            () -> false
        );

        assertEquals(0xFF101018, argbAt(withoutDrawings, 5, 2, 1));
        assertEquals(0xFFE74C3C, argbAt(withDrawings, 5, 2, 1));
    }

    @Test
    void rasterizesCircleRectangleAndFreehandDrawings() throws Exception {
        final SessionGuard.Session session = new SessionGuard.Session(
            12L, new WorldIdentity("server", "world"), DimensionId.OVERWORLD
        );
        final List<Annotation> drawings = List.of(
            annotation(
                session, 1L, new CircleAnnotationGeometry(new AnnotationPoint(3.0, 3.0), 2.0),
                0xFFE74C3C
            ),
            annotation(
                session, 2L, RectangleAnnotationGeometry.between(
                    new AnnotationPoint(7.0, 1.0), new AnnotationPoint(11.0, 5.0)
                ),
                0xFF2ECC71
            ),
            annotation(
                session, 3L, new FreehandAnnotationGeometry(List.of(
                    new AnnotationPoint(1.0, 10.0),
                    new AnnotationPoint(5.0, 10.0),
                    new AnnotationPoint(5.0, 7.0)
                )),
                0xFF3498DB
            )
        );
        final MapExportRequest request = new MapExportRequest(
            session,
            MapLayer.SURFACE,
            MapExportBounds.between(0, 0, 15, 15),
            MapExportResolution.ONE_BLOCK,
            FullscreenDisplayMode.TERRAIN,
            false,
            PredictionViewMode.EVERYWHERE,
            0xFFFFFFFF,
            0xFF101018,
            false,
            1.0f,
            MapExportLoadState.empty()
        ).withAnnotations(drawings);
        final Path spool = temp.resolve("drawing-shapes.argb");

        MapExportRasterizer.rasterize(
            request,
            key -> CompletableFuture.completedFuture(
                new MapExportTile(new int[256 * 256], null)
            ),
            spool,
            (done, total) -> {},
            () -> false
        );

        assertEquals(0xFFE74C3C, argbAt(spool, 16, 3, 1));
        assertEquals(0xFF2ECC71, argbAt(spool, 16, 9, 1));
        assertEquals(0xFF3498DB, argbAt(spool, 16, 3, 9));
    }

    @Test
    void projectsDrawingCoordinatesAtTheSelectedExportResolution() throws Exception {
        final SessionGuard.Session session = new SessionGuard.Session(
            13L, new WorldIdentity("server", "world"), DimensionId.OVERWORLD
        );
        final Annotation line = annotation(
            session,
            4L,
            new LineAnnotationGeometry(
                new AnnotationPoint(0.0, 4.0), new AnnotationPoint(7.0, 4.0)
            ),
            0xFFF1C40F
        );
        final MapExportRequest request = new MapExportRequest(
            session,
            MapLayer.SURFACE,
            MapExportBounds.between(0, 0, 7, 7),
            MapExportResolution.TWO_BLOCKS,
            FullscreenDisplayMode.TERRAIN,
            false,
            PredictionViewMode.EVERYWHERE,
            0xFFFFFFFF,
            0xFF101018,
            false,
            1.0f,
            MapExportLoadState.empty()
        ).withAnnotations(List.of(line));
        final Path spool = temp.resolve("drawing-resolution.argb");

        MapExportRasterizer.rasterize(
            request,
            key -> CompletableFuture.completedFuture(
                new MapExportTile(new int[256 * 256], null)
            ),
            spool,
            (done, total) -> {},
            () -> false
        );

        assertEquals(0xFF101018, argbAt(spool, 4, 2, 0));
        assertEquals(0xFFF1C40F, argbAt(spool, 4, 2, 1));
    }

    private static Annotation annotation(
        final SessionGuard.Session session,
        final long id,
        final cn.net.rms.confluxmap.core.annotation.AnnotationGeometry geometry,
        final int color
    ) {
        return new Annotation(
            new UUID(0L, id),
            session.dimension(),
            geometry,
            new AnnotationStyle(color),
            "",
            AnnotationPersistence.TRANSIENT,
            id
        );
    }

    private static int argbAt(
        final Path raster,
        final int width,
        final int x,
        final int y
    ) throws Exception {
        try (DataInputStream in = new DataInputStream(Files.newInputStream(raster))) {
            in.skipNBytes(((long) y * width + x) * 4L);
            return in.readInt();
        }
    }
}
