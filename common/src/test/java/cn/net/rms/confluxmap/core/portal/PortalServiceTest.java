package cn.net.rms.confluxmap.core.portal;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalServiceTest {
    private static final Logger LOGGER = LogManager.getLogger(PortalServiceTest.class);

    @TempDir
    Path tempDir;

    private static SessionGuard.Session session(
        final long token,
        final WorldIdentity world,
        final DimensionId dimension
    ) {
        return new SessionGuard.Session(token, world, dimension);
    }

    @Test
    void scansPersistAcrossSessionChangesAndReload() throws Exception {
        final WorldIdentity world = new WorldIdentity("local", "MyWorld");
        final MapExecutors executors = new MapExecutors();
        try {
            final PortalService service = new PortalService(tempDir, executors, LOGGER);
            service.onSessionChanged(session(1L, world, DimensionId.OVERWORLD));
            service.applyChunkScan(DimensionId.NETHER, 6, 6, List.of(
                new PortalMarker(DimensionId.NETHER, 6, 6, PortalKind.NETHER_PORTAL, 100, 100)
            ));
            // Dimension change inside one world: same file, state survives.
            service.onSessionChanged(session(2L, world, DimensionId.NETHER));
            assertEquals(1, service.list(DimensionId.NETHER).size());

            // Leaving the world flushes; a fresh service for the same world reloads it.
            service.onSessionChanged(SessionGuard.Session.NONE);
            final PortalService reloaded = new PortalService(tempDir, executors, LOGGER);
            reloaded.onSessionChanged(session(3L, world, DimensionId.OVERWORLD));
            assertEquals(
                List.of(new PortalMarker(DimensionId.NETHER, 6, 6, PortalKind.NETHER_PORTAL, 100, 100)),
                reloaded.list(DimensionId.NETHER)
            );
        } finally {
            executors.shutdown(2000L);
        }
    }

    @Test
    void removingEveryMarkerClearsTheFile() throws Exception {
        final WorldIdentity world = new WorldIdentity("local", "Empty");
        final MapExecutors executors = new MapExecutors();
        try {
            final PortalService service = new PortalService(tempDir, executors, LOGGER);
            service.onSessionChanged(session(1L, world, DimensionId.OVERWORLD));
            service.applyChunkScan(DimensionId.OVERWORLD, 0, 0, List.of(
                new PortalMarker(DimensionId.OVERWORLD, 0, 0, PortalKind.END_GATEWAY, 8, 8)
            ));
            service.applyChunkScan(DimensionId.OVERWORLD, 0, 0, List.of());
            service.onSessionChanged(SessionGuard.Session.NONE);
            final PortalService reloaded = new PortalService(tempDir, executors, LOGGER);
            reloaded.onSessionChanged(session(2L, world, DimensionId.OVERWORLD));
            assertTrue(reloaded.list(DimensionId.OVERWORLD).isEmpty());
        } finally {
            executors.shutdown(2000L);
        }
    }

    @Test
    void chunkQueriesDriveRescanDecisions() {
        final WorldIdentity world = new WorldIdentity("local", "Queries");
        final MapExecutors executors = new MapExecutors();
        try {
            final PortalService service = new PortalService(tempDir, executors, LOGGER);
            service.onSessionChanged(session(1L, world, DimensionId.OVERWORLD));
            assertFalse(service.hasMarkersInChunk(DimensionId.OVERWORLD, 2, 3));
            service.applyChunkScan(DimensionId.OVERWORLD, 2, 3, List.of(
                new PortalMarker(DimensionId.OVERWORLD, 2, 3, PortalKind.END_PORTAL, 40, 55)
            ));
            assertTrue(service.hasMarkersInChunk(DimensionId.OVERWORLD, 2, 3));
        } finally {
            executors.shutdown(2000L);
        }
    }
}
