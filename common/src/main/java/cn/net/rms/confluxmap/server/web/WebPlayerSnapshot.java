package cn.net.rms.confluxmap.server.web;

import java.util.List;

/** Immutable public radar sample serialized directly into an SSE event. */
public record WebPlayerSnapshot(long revision, List<Player> players) {
    public record Player(
        String id,
        String name,
        int dimension,
        double x,
        double z,
        boolean translucent
    ) {
        String toJson() {
            return "{\"id\":\"" + json(id) + "\",\"name\":\"" + json(name)
                + "\",\"dimension\":" + dimension + ",\"x\":" + x
                + ",\"z\":" + z + ",\"translucent\":" + translucent + "}";
        }
    }

    public static final WebPlayerSnapshot EMPTY = new WebPlayerSnapshot(0L, List.of());

    public WebPlayerSnapshot {
        players = players == null ? List.of() : List.copyOf(players);
    }

    public String toJson() {
        final StringBuilder out = new StringBuilder(64 + players.size() * 96);
        out.append("{\"revision\":").append(revision).append(",\"players\":[");
        for (int i = 0; i < players.size(); i++) {
            if (i > 0) out.append(',');
            out.append(players.get(i).toJson());
        }
        return out.append("]}").toString();
    }

    private static String json(final String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r");
    }
}
