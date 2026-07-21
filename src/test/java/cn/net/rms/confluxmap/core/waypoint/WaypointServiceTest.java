package cn.net.rms.confluxmap.core.waypoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WaypointServiceTest {
    private static final Logger LOGGER = LogManager.getLogger("WaypointServiceTest");

    @Test
    void firstSessionMigratesAndLoadsLegacySingleplayerWaypoints(@TempDir final Path tempDir) throws IOException {
        final Path saveRoot = createSave(tempDir.resolve("saves").resolve("New World"));
        final WorldIdentity world = WorldIdentity.singleplayerSave(saveRoot);
        final String oldestLegacyId = world.legacyStorageIds().get(world.legacyStorageIds().size() - 1);
        final Path legacyFile = tempDir.resolve("local").resolve(oldestLegacyId + ".json");
        final Path currentFile = tempDir.resolve("local").resolve(world.worldId() + ".json");
        final Waypoint waypoint = Waypoint.create(
            "Home", DimensionId.OVERWORLD, 12.0, 64.0, -8.0, 0xFFFF0000, "", Waypoint.Type.NORMAL
        );
        WaypointIo.save(legacyFile, List.of(waypoint), LOGGER);

        final MapExecutors executors = new MapExecutors();
        try {
            final WaypointService service = new WaypointService(tempDir, executors, LOGGER);
            service.onSessionChanged(new SessionGuard.Session(1L, world, DimensionId.OVERWORLD));

            assertEquals(1, service.list().size());
            assertEquals("Home", service.list().get(0).name);
            assertTrue(Files.isRegularFile(currentFile));
            assertFalse(Files.exists(legacyFile));
        } finally {
            executors.shutdown(1000L);
        }
    }

    @Test
    void collidingLegacyWaypointsAreNotAssignedToEitherSave(@TempDir final Path tempDir) throws IOException {
        final Path saves = tempDir.resolve("saves");
        final Path firstSave = createSave(saves.resolve("A B"));
        createSave(saves.resolve("a_b"));
        final WorldIdentity world = WorldIdentity.singleplayerSave(firstSave);
        final Path legacyFile = tempDir.resolve("local").resolve("a_b.json");
        WaypointIo.save(legacyFile, List.of(Waypoint.create(
            "Mixed", DimensionId.OVERWORLD, 0.0, 64.0, 0.0, 0xFFFFFFFF, "", Waypoint.Type.NORMAL
        )), LOGGER);

        final MapExecutors executors = new MapExecutors();
        try {
            final WaypointService service = new WaypointService(tempDir, executors, LOGGER);
            service.onSessionChanged(new SessionGuard.Session(1L, world, DimensionId.OVERWORLD));

            assertTrue(service.list().isEmpty());
            assertTrue(Files.isRegularFile(legacyFile));
        } finally {
            executors.shutdown(1000L);
        }
    }

    @Test
    void recreatedSaveWithTheSameDirectoryNameDoesNotInheritWaypoints(@TempDir final Path tempDir) throws IOException {
        final Path saveRoot = createSave(tempDir.resolve("saves").resolve("New World"));
        final WorldIdentity deletedWorld = WorldIdentity.singleplayerSave(saveRoot);
        final MapExecutors executors = new MapExecutors();
        try {
            final WaypointService service = new WaypointService(tempDir.resolve("waypoints"), executors, LOGGER);
            service.onSessionChanged(new SessionGuard.Session(1L, deletedWorld, DimensionId.OVERWORLD));
            service.current().add(Waypoint.create(
                "Old home", DimensionId.OVERWORLD, 12.0, 64.0, -8.0, 0xFFFF0000, "", Waypoint.Type.NORMAL
            ));
            service.onSessionChanged(SessionGuard.Session.NONE);

            deleteSave(saveRoot);
            final WorldIdentity recreatedWorld = WorldIdentity.singleplayerSave(createSave(saveRoot));
            service.onSessionChanged(new SessionGuard.Session(2L, recreatedWorld, DimensionId.OVERWORLD));

            assertTrue(service.list().isEmpty());
        } finally {
            executors.shutdown(1000L);
        }
    }

    @Test
    void sameWorldResumeWaitsForTheOutgoingSnapshot(@TempDir final Path tempDir) throws Exception {
        final Path saveRoot = createSave(tempDir.resolve("saves").resolve("New World"));
        final WorldIdentity world = WorldIdentity.singleplayerSave(saveRoot);
        final SessionGuard.Session session = new SessionGuard.Session(1L, world, DimensionId.OVERWORLD);
        final CountDownLatch ioBlocked = new CountDownLatch(1);
        final CountDownLatch releaseIo = new CountDownLatch(1);
        final MapExecutors executors = new MapExecutors();
        try {
            executors.io().execute(() -> {
                ioBlocked.countDown();
                try {
                    releaseIo.await();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(ioBlocked.await(1L, TimeUnit.SECONDS));

            final WaypointService service = new WaypointService(tempDir, executors, LOGGER);
            service.onSessionChanged(session);
            service.current().add(Waypoint.create(
                "Latest", DimensionId.OVERWORLD, 1.0, 64.0, 2.0, 0xFFFFFFFF, "", Waypoint.Type.NORMAL
            ));

            final Thread releaser = new Thread(() -> {
                try {
                    Thread.sleep(200L);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    releaseIo.countDown();
                }
            });
            releaser.start();
            service.onSessionChanged(SessionGuard.Session.NONE);
            service.onSessionChanged(new SessionGuard.Session(2L, world, DimensionId.OVERWORLD));
            releaser.join();

            assertEquals(1, service.list().size());
            assertEquals("Latest", service.list().get(0).name);
        } finally {
            releaseIo.countDown();
            executors.shutdown(1000L);
        }
    }

    private static Path createSave(final Path root) throws IOException {
        Files.createDirectories(root);
        Files.writeString(root.resolve("level.dat"), "test save");
        return root;
    }

    private static void deleteSave(final Path root) throws IOException {
        Files.delete(root.resolve("confluxmap").resolve("world_uuid.json"));
        Files.delete(root.resolve("confluxmap"));
        Files.delete(root.resolve("level.dat"));
        Files.delete(root);
    }
}
