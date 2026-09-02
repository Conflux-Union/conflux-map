package cn.net.rms.confluxmap.core.radar;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Merges server-authoritative players into the ordinary client entity radar snapshot. */
public final class ServerPlayerRadarEntries {
    private ServerPlayerRadarEntries() {
    }

    public static List<RadarEntry> merge(
        final List<RadarEntry> local,
        final List<ServerPlayerRadarState.PlayerView> serverPlayers,
        final UUID selfId,
        final double viewerY
    ) {
        final List<RadarEntry> result = new ArrayList<>(local);
        final Set<UUID> present = new HashSet<>();
        for (final RadarEntry entry : local) {
            if (entry.playerId() != null) {
                present.add(entry.playerId());
            }
        }
        for (final ServerPlayerRadarState.PlayerView player : serverPlayers) {
            if (player.playerId().equals(selfId) || !present.add(player.playerId())) {
                continue;
            }
            result.add(entry(player, viewerY));
        }
        return List.copyOf(result);
    }

    public static RadarEntry entry(
        final ServerPlayerRadarState.PlayerView player,
        final double viewerY
    ) {
        return new RadarEntry(
            player.x(),
            player.z(),
            Double.isFinite(viewerY) ? (int) Math.round(player.y() - viewerY) : 0,
            RadarCategory.PLAYER,
            player.name(),
            -1,
            player.spectator(),
            player.playerId()
        );
    }
}
