package cn.net.rms.confluxmap.core.radar;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.confluxmap.core.model.DimensionId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ServerPlayerRadarEntriesTest {
    private static final UUID SELF =
        UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ALEX =
        UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID STEVE =
        UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Test
    void keepsLocalPlayersOnceAndAddsUnloadedServerPlayers() {
        final RadarEntry localAlex = new RadarEntry(
            10.0, 20.0, 2, RadarCategory.PLAYER, "Alex", 42, false, ALEX
        );
        final List<ServerPlayerRadarState.PlayerView> server = List.of(
            player(SELF, "Self", 0.0),
            player(ALEX, "Alex", 10.0),
            player(STEVE, "Steve", 30.0)
        );

        final List<RadarEntry> merged = ServerPlayerRadarEntries.merge(
            List.of(localAlex), server, SELF, 64.0
        );

        assertEquals(ALEX, merged.get(0).playerId());
        assertEquals(STEVE, merged.get(1).playerId());
        assertEquals(-1, merged.get(1).entityId());
        assertEquals(6, merged.get(1).yDelta());
    }

    private static ServerPlayerRadarState.PlayerView player(
        final UUID id,
        final String name,
        final double x
    ) {
        return new ServerPlayerRadarState.PlayerView(
            id, name, DimensionId.OVERWORLD, x, 70.0, 5.0, false
        );
    }
}
