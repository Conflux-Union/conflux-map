package cn.net.rms.confluxmap.core.radar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ServerPlayerRadarStateTest {
    private static final UUID ALEX =
        UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void interpolatesBetweenConsecutiveSnapshotsInTheSameDimension() {
        final ServerPlayerRadarState state = new ServerPlayerRadarState();
        state.accept(List.of(sample(DimensionId.OVERWORLD, 10.0)), 1_000L);
        state.accept(List.of(sample(DimensionId.OVERWORLD, 14.0)), 1_250L);

        final ServerPlayerRadarState.PlayerView player =
            state.playersIn(DimensionId.OVERWORLD, 1_375L).get(0);

        assertEquals(12.0, player.x(), 0.0001);
    }

    @Test
    void keepsAHighlightedPlayersLastPositionWhenTheyChangeDimension() {
        final ServerPlayerRadarState state = new ServerPlayerRadarState();
        state.accept(List.of(sample(DimensionId.OVERWORLD, 10.0)), 1_000L);
        assertTrue(state.highlight(ALEX));
        state.accept(List.of(sample(DimensionId.NETHER, 80.0)), 1_250L);

        final ServerPlayerRadarState.HighlightView highlight = state.highlightedIn(
            DimensionId.OVERWORLD, 2_000L, 30_000L
        ).orElseThrow();

        assertTrue(highlight.ghost());
        assertEquals(10.0, highlight.player().x(), 0.0001);
        assertEquals(DimensionId.NETHER, highlight.destination());
    }

    @Test
    void expiresTheOldDimensionGhostAfterTheConfiguredDuration() {
        final ServerPlayerRadarState state = new ServerPlayerRadarState();
        state.accept(List.of(sample(DimensionId.OVERWORLD, 10.0)), 1_000L);
        assertTrue(state.highlight(ALEX));
        state.accept(List.of(sample(DimensionId.NETHER, 80.0)), 1_250L);

        assertTrue(state.highlightedIn(
            DimensionId.OVERWORLD, 31_001L, 30_000L
        ).isEmpty());
    }

    @Test
    void showsTheLivePositionWhenViewingTheHighlightedPlayersNewDimension() {
        final ServerPlayerRadarState state = new ServerPlayerRadarState();
        state.accept(List.of(sample(DimensionId.OVERWORLD, 10.0)), 1_000L);
        assertTrue(state.highlight(ALEX));
        state.accept(List.of(sample(DimensionId.NETHER, 80.0)), 1_250L);

        final ServerPlayerRadarState.HighlightView highlight = state.highlightedIn(
            DimensionId.NETHER, 1_500L, 30_000L
        ).orElseThrow();

        assertFalse(highlight.ghost());
        assertEquals(80.0, highlight.player().x(), 0.0001);
    }

    @Test
    void clearsTheHighlightWhenThePlayerLeavesTheFullSnapshot() {
        final ServerPlayerRadarState state = new ServerPlayerRadarState();
        state.accept(List.of(sample(DimensionId.OVERWORLD, 10.0)), 1_000L);
        assertTrue(state.highlight(ALEX));

        state.accept(List.of(), 1_250L);

        assertFalse(state.isHighlighted(ALEX));
    }

    @Test
    void keepsTheHighlightWhenOnlyTheViewerDimensionChanges() {
        final ServerPlayerRadarState state = new ServerPlayerRadarState();
        final WorldIdentity world = new WorldIdentity("server", "world");
        state.onSessionChanged(new SessionGuard.Session(1L, world, DimensionId.OVERWORLD));
        state.accept(List.of(sample(DimensionId.OVERWORLD, 10.0)), 1_000L);
        assertTrue(state.highlight(ALEX));

        state.onSessionChanged(new SessionGuard.Session(2L, world, DimensionId.NETHER));

        assertTrue(state.isHighlighted(ALEX));
    }

    @Test
    void clearsPlayersAndHighlightWhenTheViewerChangesWorld() {
        final ServerPlayerRadarState state = new ServerPlayerRadarState();
        state.onSessionChanged(new SessionGuard.Session(
            1L, new WorldIdentity("server", "first"), DimensionId.OVERWORLD
        ));
        state.accept(List.of(sample(DimensionId.OVERWORLD, 10.0)), 1_000L);
        assertTrue(state.highlight(ALEX));

        state.onSessionChanged(new SessionGuard.Session(
            2L, new WorldIdentity("server", "second"), DimensionId.OVERWORLD
        ));

        assertTrue(state.playersIn(DimensionId.OVERWORLD, 1_250L).isEmpty());
        assertFalse(state.isHighlighted(ALEX));
    }

    private static ServerPlayerRadarState.Sample sample(
        final DimensionId dimension,
        final double x
    ) {
        return new ServerPlayerRadarState.Sample(
            ALEX, "Alex", dimension, x, 70.0, -5.0, false
        );
    }
}
