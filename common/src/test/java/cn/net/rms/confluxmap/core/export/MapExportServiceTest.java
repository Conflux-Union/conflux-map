package cn.net.rms.confluxmap.core.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.loadstate.FullscreenDisplayMode;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.predict.PredictionViewMode;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MapExportServiceTest {
    @TempDir
    Path temp;

    @Test
    void completesPngAtomicallyAndReportsResult() throws Exception {
        final SessionGuard guard = new SessionGuard();
        final SessionGuard.Session session = guard.begin(
            new WorldIdentity("server", "world"), DimensionId.OVERWORLD
        );
        try (MapExportService service = new MapExportService(temp, guard, request -> key -> {
            final int[] real = new int[256 * 256];
            real[0] = 0xFF123456;
            return CompletableFuture.completedFuture(new MapExportTile(real, null));
        })) {
            service.start(request(session));
            final MapExportStatus status = waitForTerminal(service);

            assertEquals(MapExportStatus.State.COMPLETED, status.state());
            assertNotNull(status.output());
            assertTrue(Files.isRegularFile(status.output()));
            assertFalse(Files.exists(status.output().resolveSibling(status.output().getFileName() + ".part")));
            final BufferedImage image = ImageIO.read(status.output().toFile());
            assertEquals(0xFF123456, image.getRGB(0, 0));
        }
    }

    @Test
    void failureLeavesNoPngOrTemporaryFiles() throws Exception {
        final SessionGuard guard = new SessionGuard();
        final SessionGuard.Session session = guard.begin(
            new WorldIdentity("server", "world"), DimensionId.OVERWORLD
        );
        try (MapExportService service = new MapExportService(temp, guard, request -> key ->
            CompletableFuture.failedFuture(new IllegalStateException("broken tile"))
        )) {
            service.start(request(session));
            final MapExportStatus status = waitForTerminal(service);

            assertEquals(MapExportStatus.State.FAILED, status.state());
            try (var paths = Files.list(temp)) {
                assertEquals(0L, paths.count());
            }
        }
    }

    @Test
    void cancelledTileIsReportedAsFailureWithItsReason() throws Exception {
        final SessionGuard guard = new SessionGuard();
        final SessionGuard.Session session = guard.begin(
            new WorldIdentity("server", "world"), DimensionId.OVERWORLD
        );
        try (MapExportService service = new MapExportService(temp, guard, request -> key ->
            CompletableFuture.failedFuture(new CancellationException("Map tile snapshot was superseded"))
        )) {
            service.start(request(session));
            final MapExportStatus status = waitForTerminal(service);

            assertEquals(MapExportStatus.State.FAILED, status.state());
            assertEquals("Map tile snapshot was superseded", status.error());
        }
    }

    @Test
    void explicitUserCancellationIsReportedAsCancelled() throws Exception {
        final SessionGuard guard = new SessionGuard();
        final SessionGuard.Session session = guard.begin(
            new WorldIdentity("server", "world"), DimensionId.OVERWORLD
        );
        try (MapExportService service = new MapExportService(
            temp, guard, request -> key -> new CompletableFuture<>()
        )) {
            service.start(request(session));
            service.cancel();
            final MapExportStatus status = waitForTerminal(service);

            assertEquals(MapExportStatus.State.CANCELLED, status.state());
            assertNull(status.error());
        }
    }

    @Test
    void slowClientTicksAreReportedAsFailure() throws Exception {
        final SessionGuard guard = new SessionGuard();
        final SessionGuard.Session session = guard.begin(
            new WorldIdentity("server", "world"), DimensionId.OVERWORLD
        );
        try (MapExportService service = new MapExportService(
            temp, guard, request -> key -> new CompletableFuture<>()
        )) {
            service.start(request(session));
            for (int tick = 0; tick < 6; tick++) {
                Thread.sleep(510L);
                service.tick();
            }
            final MapExportStatus status = waitForTerminal(service);

            assertEquals(MapExportStatus.State.FAILED, status.state());
            assertEquals(
                "Map export stopped after five consecutive client tick intervals exceeded 500 ms",
                status.error()
            );
        }
    }

    @Test
    void sessionChangeIsReportedAsFailure() throws Exception {
        final SessionGuard guard = new SessionGuard();
        final SessionGuard.Session session = guard.begin(
            new WorldIdentity("server", "world"), DimensionId.OVERWORLD
        );
        try (MapExportService service = new MapExportService(
            temp, guard, request -> key -> new CompletableFuture<>()
        )) {
            service.start(request(session));
            guard.begin(new WorldIdentity("server", "other-world"), DimensionId.OVERWORLD);
            final MapExportStatus status = waitForTerminal(service);

            assertEquals(MapExportStatus.State.FAILED, status.state());
            assertEquals("Map session changed during export", status.error());
        }
    }

    @Test
    void serviceCloseIsReportedAsFailure() throws Exception {
        final SessionGuard guard = new SessionGuard();
        final SessionGuard.Session session = guard.begin(
            new WorldIdentity("server", "world"), DimensionId.OVERWORLD
        );
        final CompletableFuture<Void> snapshotStarted = new CompletableFuture<>();
        final MapExportService service = new MapExportService(temp, guard, request -> key -> {
            snapshotStarted.complete(null);
            return new CompletableFuture<>();
        });
        service.start(request(session));
        snapshotStarted.get(1L, TimeUnit.SECONDS);

        service.close();

        assertEquals(MapExportStatus.State.FAILED, service.status().state());
        assertEquals("Map export service closed", service.status().error());
    }

    private static MapExportRequest request(final SessionGuard.Session session) {
        return new MapExportRequest(
            session,
            MapLayer.SURFACE,
            MapExportBounds.between(0, 0, 0, 0),
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
    }

    private static MapExportStatus waitForTerminal(final MapExportService service) throws Exception {
        final long deadline = System.nanoTime() + 5_000_000_000L;
        while (!service.status().terminal() && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }
        assertTrue(service.status().terminal(), "export did not finish before timeout");
        return service.status();
    }
}
