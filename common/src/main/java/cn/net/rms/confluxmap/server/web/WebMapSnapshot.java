package cn.net.rms.confluxmap.server.web;

import java.util.List;

/** Immutable public state pushed to browser map clients. */
public record WebMapSnapshot(
    long revision,
    List<WebPlayerSnapshot.Player> players,
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

    public static final WebMapSnapshot EMPTY = new WebMapSnapshot(0L, List.of(), List.of());

    public WebMapSnapshot {
        if (revision < 0) throw new IllegalArgumentException("revision must be non-negative");
        players = players == null ? List.of() : List.copyOf(players);
        waypoints = waypoints == null ? List.of() : List.copyOf(waypoints);
    }

    public String toJson() {
        final StringBuilder out = new StringBuilder(
            96 + players.size() * 96 + waypoints.size() * 128
        );
        out.append("{\"revision\":").append(revision).append(",\"players\":[");
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

    private static String json(final String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r");
    }
}
