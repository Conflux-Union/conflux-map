package cn.net.rms.confluxmap.mc.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FullscreenMapBrowseServiceTest {
    @TempDir
    Path cacheRoot;

    @Test
    void listsAllKnownDimensionsForTheLiveWorldButOnlyCachedTargetsForOtherWorlds()
        throws Exception {
        final WorldIdentity liveWorld = new WorldIdentity("server", "live");
        final WorldIdentity archivedWorld = new WorldIdentity("server", "archive");
        Files.createDirectories(
            cacheRoot.resolve("server/archive").resolve(DimensionId.NETHER.fileName())
        );
        final SessionGuard.Session live = new SessionGuard.Session(
            7L, liveWorld, DimensionId.OVERWORLD
        );

        final List<FullscreenMapBrowseService.Target> targets =
            FullscreenMapBrowseService.targets(
                cacheRoot,
                live,
                List.of(archivedWorld),
                List.of(DimensionId.of("example", "moon"))
            );

        assertEquals(
            new FullscreenMapBrowseService.Target(liveWorld, DimensionId.OVERWORLD),
            targets.get(0)
        );
        assertTrue(targets.contains(
            new FullscreenMapBrowseService.Target(liveWorld, DimensionId.NETHER)
        ));
        assertTrue(targets.contains(
            new FullscreenMapBrowseService.Target(archivedWorld, DimensionId.NETHER)
        ));
        assertFalse(targets.contains(
            new FullscreenMapBrowseService.Target(archivedWorld, DimensionId.OVERWORLD)
        ));
    }

    @Test
    void exposesIndependentWorldAndDimensionOptionsForTopSelectors() throws Exception {
        final WorldIdentity liveWorld = new WorldIdentity("server", "live");
        final WorldIdentity archivedWorld = new WorldIdentity("server", "archive");
        final WorldIdentity emptyWorld = new WorldIdentity("server", "empty");
        final DimensionId moon = DimensionId.of("example", "moon");
        Files.createDirectories(
            cacheRoot.resolve("server/archive").resolve(DimensionId.NETHER.fileName())
        );
        Files.createDirectories(
            cacheRoot.resolve("server/archive").resolve(moon.fileName())
        );
        final SessionGuard.Session live = new SessionGuard.Session(
            7L, liveWorld, DimensionId.OVERWORLD
        );

        assertEquals(
            List.of(liveWorld, archivedWorld),
            FullscreenMapBrowseService.worlds(
                cacheRoot, live, List.of(archivedWorld, emptyWorld)
            )
        );
        assertEquals(
            List.of(DimensionId.OVERWORLD, DimensionId.NETHER, DimensionId.END, moon),
            FullscreenMapBrowseService.dimensions(
                cacheRoot, live, liveWorld, List.of(moon)
            )
        );
        assertEquals(
            List.of(DimensionId.NETHER, moon),
            FullscreenMapBrowseService.dimensions(
                cacheRoot, live, archivedWorld, List.of(moon)
            )
        );
    }

    @Test
    void archivedWorldUsesItsOwnEditableContentSession() {
        final WorldIdentity liveWorld = new WorldIdentity("server", "live");
        final WorldIdentity archivedWorld = new WorldIdentity("server", "archive");
        final SessionGuard.Session live = new SessionGuard.Session(
            7L, liveWorld, DimensionId.OVERWORLD
        );

        assertEquals(
            SessionGuard.Session.NONE,
            FullscreenMapBrowseService.contentSession(
                new SessionGuard.Session(8L, liveWorld, DimensionId.NETHER), live
            )
        );
        assertEquals(
            new SessionGuard.Session(9L, archivedWorld, DimensionId.NETHER),
            FullscreenMapBrowseService.contentSession(
                new SessionGuard.Session(9L, archivedWorld, DimensionId.NETHER), live
            )
        );
    }
}
