package cn.net.rms.confluxmap.server.web;

import java.util.List;

/** Immutable public state pushed to browser map clients. */
public record WebMapSnapshot(
    long playerRevision,
    List<WebPlayerSnapshot.Player> players,
    long waypointRevision,
    List<Waypoint> waypoints
) {
    public record Waypoint(
        String id,
        String name,
        int dimension,
        double x,
        double y,
        double z,
        int colorArgb,
        String type
    ) {
        String toJson() {
            return "{\"id\":\"" + json(id) + "\",\"name\":\"" + json(name)
                + "\",\"dimension\":" + dimension + ",\"x\":" + x
                + ",\"y\":" + y + ",\"z\":" + z
                + ",\"colorArgb\":" + colorArgb + ",\"type\":\""
                + json(type) + "\"}";
        }
    }

    public static final WebMapSnapshot EMPTY = new WebMapSnapshot(
        0L, List.of(), 0L, List.of()
    );

    public WebMapSnapshot(
        final long revision,
        final List<WebPlayerSnapshot.Player> players,
        final List<Waypoint> waypoints
    ) {
        this(revision, players, revision, waypoints);
    }

    public WebMapSnapshot {
        if (playerRevision < 0 || waypointRevision < 0) {
            throw new IllegalArgumentException("revisions must be non-negative");
        }
        players = players == null ? List.of() : List.copyOf(players);
        waypoints = waypoints == null ? List.of() : List.copyOf(waypoints);
    }

    public WebMapSnapshot next(
        final List<WebPlayerSnapshot.Player> nextPlayers,
        final List<Waypoint> nextWaypoints
    ) {
        final List<WebPlayerSnapshot.Player> playerCopy = nextPlayers == null
            ? List.of() : List.copyOf(nextPlayers);
        final List<Waypoint> waypointCopy = nextWaypoints == null
            ? List.of() : List.copyOf(nextWaypoints);
        final boolean playersChanged = !players.equals(playerCopy);
        final boolean waypointsChanged = !waypoints.equals(waypointCopy);
        if (!playersChanged && !waypointsChanged) return this;
        return new WebMapSnapshot(
            playersChanged ? increment(playerRevision) : playerRevision,
            playerCopy,
            waypointsChanged ? increment(waypointRevision) : waypointRevision,
            waypointCopy
        );
    }

    public long revision() {
        return Math.max(playerRevision, waypointRevision);
    }

    public String toJson() {
        final StringBuilder out = new StringBuilder(
            96 + players.size() * 96 + waypoints.size() * 128
        );
        out.append("{\"revision\":").append(revision()).append(",\"players\":[");
        for (int i = 0; i < players.size(); i++) {
            if (i > 0) out.append(',');
            out.append(players.get(i).toJson());
        }
        out.append("],\"waypoints\":[");
        for (int i = 0; i < waypoints.size(); i++) {
            if (i > 0) out.append(',');
            out.append(waypoints.get(i).toJson());
        }
        return out.append("]}").toString();
    }

    String playersJson() {
        final StringBuilder out = new StringBuilder(48 + players.size() * 96)
            .append("{\"revision\":").append(playerRevision).append(",\"players\":[");
        for (int i = 0; i < players.size(); i++) {
            if (i > 0) out.append(',');
            out.append(players.get(i).toJson());
        }
        return out.append("]}").toString();
    }

    String waypointsJson() {
        final StringBuilder out = new StringBuilder(48 + waypoints.size() * 128)
            .append("{\"revision\":").append(waypointRevision).append(",\"waypoints\":[");
        for (int i = 0; i < waypoints.size(); i++) {
            if (i > 0) out.append(',');
            out.append(waypoints.get(i).toJson());
        }
        return out.append("]}").toString();
    }

    private static long increment(final long revision) {
        return revision == Long.MAX_VALUE ? Long.MAX_VALUE : revision + 1L;
    }

    private static String json(final String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r");
    }
}
