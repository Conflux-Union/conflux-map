package cn.net.rms.confluxmap.server.web;

import cn.net.rms.confluxmap.core.predict.WorldPreset;
import java.util.List;

/** Stable public metadata needed before a browser requests prediction or authoritative regions. */
public record WebMapManifest(
    String worldId,
    String worldgenVersion,
    boolean predictionAvailable,
    List<Dimension> dimensions
) {
    public record Dimension(
        int index,
        String id,
        String type,
        boolean predictable,
        WorldPreset preset
    ) {
        public String toJson() {
            return "{\"index\":" + index
                + ",\"id\":\"" + json(id) + "\""
                + ",\"type\":\"" + json(type) + "\""
                + ",\"predictable\":" + predictable
                + ",\"preset\":\"" + preset.name() + "\"}";
        }
    }

    public WebMapManifest {
        if (worldId == null || worldId.isBlank()) {
            throw new IllegalArgumentException("worldId cannot be blank");
        }
        worldgenVersion = worldgenVersion == null ? "unknown" : worldgenVersion;
        dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
    }

    public String toJson() {
        final StringBuilder result = new StringBuilder(256);
        result.append("{\"worldId\":\"").append(json(worldId))
            .append("\",\"worldgenVersion\":\"").append(json(worldgenVersion))
            .append("\",\"predictionAvailable\":").append(predictionAvailable)
            .append(",\"dimensions\":[");
        for (int i = 0; i < dimensions.size(); i++) {
            if (i > 0) {
                result.append(',');
            }
            result.append(dimensions.get(i).toJson());
        }
        return result.append("]}").toString();
    }

    private static String json(final String input) {
        final StringBuilder escaped = new StringBuilder(input.length() + 8);
        for (int i = 0; i < input.length(); i++) {
            final char c = input.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
