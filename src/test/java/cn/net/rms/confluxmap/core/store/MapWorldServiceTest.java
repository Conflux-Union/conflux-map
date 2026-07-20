package cn.net.rms.confluxmap.core.store;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.SampleSource;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import org.junit.jupiter.api.Test;

class MapWorldServiceTest {
    @Test
    void switchingSessionsSealsTheEndingWorldBeforeReturningItForFlush() {
        final MapWorldService worlds = new MapWorldService();
        final SessionGuard.Session firstSession = session(1L, "first");
        final SessionGuard.Session secondSession = session(2L, "second");
        worlds.switchSession(firstSession);
        final MapWorld firstWorld = worlds.current();
        assertTrue(firstWorld.put(MapLayer.SURFACE, snapshot(firstSession.token()), SampleSource.REAL_LIVE));

        final MapWorld endingWorld = worlds.switchSession(secondSession);

        assertSame(firstWorld, endingWorld);
        assertFalse(firstWorld.put(MapLayer.SURFACE, snapshot(firstSession.token()), SampleSource.REAL_LIVE));
        assertSame(worlds.current(), worlds.ifCurrent(secondSession.token()));
    }

    private static SessionGuard.Session session(final long token, final String worldId) {
        return new SessionGuard.Session(token, WorldIdentity.singleplayer(worldId), DimensionId.OVERWORLD);
    }

    private static ChunkSnapshot snapshot(final long token) {
        return new ChunkSnapshot(
            0,
            0,
            token,
            new short[ChunkSnapshot.COLUMNS],
            new byte[ChunkSnapshot.COLUMNS],
            new int[ChunkSnapshot.COLUMNS],
            new int[ChunkSnapshot.COLUMNS],
            new int[ChunkSnapshot.COLUMNS],
            new byte[ChunkSnapshot.COLUMNS],
            new byte[ChunkSnapshot.COLUMNS]
        );
    }
}
