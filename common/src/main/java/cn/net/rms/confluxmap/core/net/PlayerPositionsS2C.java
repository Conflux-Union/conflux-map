package cn.net.rms.confluxmap.core.net;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Complete bounded snapshot of online players visible through the companion radar. */
public record PlayerPositionsS2C(List<Entry> entries) implements Message {
    public PlayerPositionsS2C {
        entries = List.copyOf(entries);
    }

    public record Entry(
        UUID playerId,
        String name,
        String dimensionId,
        double x,
        double y,
        double z,
        boolean spectator
    ) {
        public Entry {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(dimensionId, "dimensionId");
        }
    }

    @Override
    public int typeId() {
        return Proto.MSG_PLAYER_POSITIONS_S2C;
    }
}
