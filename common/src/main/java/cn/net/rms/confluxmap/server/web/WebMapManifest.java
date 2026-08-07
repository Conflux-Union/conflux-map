package cn.net.rms.confluxmap.server.web;

import cn.net.rms.confluxmap.core.predict.WorldPreset;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.predict.BiomeTable;
import cn.net.rms.confluxmap.core.predict.PredictionPalette;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Stable public metadata needed before a browser requests prediction or authoritative regions. */
public record WebMapManifest(
    String worldId,
    String worldgenVersion,
    Long seed,
    int predictionVersion,
    List<Dimension> dimensions,
    List<Material> materials
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

    public record Material(
        String id,
        int baseArgb,
        double[] detailOffsets,
        String tint,
        int fixedTintArgb,
        int patternSalt
    ) {
        public Material {
            if (id == null || id.isBlank() || detailOffsets == null
                || detailOffsets.length != 16 || tint == null || tint.isBlank()) {
                throw new IllegalArgumentException("invalid web material sample");
            }
            detailOffsets = detailOffsets.clone();
        }

        @Override
        public double[] detailOffsets() {
            return detailOffsets.clone();
        }

        public String toJson() {
            final StringBuilder result = new StringBuilder(256)
                .append("{\"id\":\"").append(json(id))
                .append("\",\"baseArgb\":").append(Integer.toUnsignedLong(baseArgb))
                .append(",\"detailOffsets\":[");
            for (int i = 0; i < detailOffsets.length; i++) {
                if (i > 0) result.append(',');
                result.append(detailOffsets[i]);
            }
            return result.append("],\"tint\":\"").append(json(tint))
                .append("\",\"fixedTintArgb\":").append(Integer.toUnsignedLong(fixedTintArgb))
                .append(",\"patternSalt\":").append(patternSalt).append('}').toString();
        }
    }

    public WebMapManifest(
        final String worldId,
        final String worldgenVersion,
        final Long seed,
        final int predictionVersion,
        final List<Dimension> dimensions
    ) {
        this(worldId, worldgenVersion, seed, predictionVersion, dimensions, List.of());
    }

    public WebMapManifest {
        if (worldId == null || worldId.isBlank()) {
            throw new IllegalArgumentException("worldId cannot be blank");
        }
        worldgenVersion = worldgenVersion == null ? "unknown" : worldgenVersion;
        if ((seed == null) != (predictionVersion < 0)) {
            throw new IllegalArgumentException("prediction seed and version must be available together");
        }
        dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
        materials = materials == null ? List.of() : List.copyOf(materials);
    }

    public boolean predictionAvailable() {
        return seed != null;
    }

    public String toJson() {
        final StringBuilder result = new StringBuilder(256);
        result.append("{\"worldId\":\"").append(json(worldId))
            .append("\",\"worldgenVersion\":\"").append(json(worldgenVersion))
            .append("\",\"predictionAvailable\":").append(predictionAvailable());
        if (predictionAvailable()) {
            result.append(",\"seed\":\"").append(seed).append('"')
                .append(",\"predictionVersion\":").append(predictionVersion);
        }
        result.append(",\"predictionBiomes\":");
        appendPredictionBiomes(result);
        result.append(",\"dimensions\":[");
        for (int i = 0; i < dimensions.size(); i++) {
            if (i > 0) {
                result.append(',');
            }
            result.append(dimensions.get(i).toJson());
        }
        result.append("],\"materials\":[");
        for (int i = 0; i < materials.size(); i++) {
            if (i > 0) result.append(',');
            result.append(materials.get(i).toJson());
        }
        return result.append("]}").toString();
    }

    private static void appendPredictionBiomes(final StringBuilder result) {
        final PredictionPalette palette = PredictionPalette.defaults();
        final List<Integer> ids = new ArrayList<>(BiomeTable.knownIds());
        ids.sort(Comparator.naturalOrder());
        result.append('[');
        for (int i = 0; i <= ids.size(); i++) {
            final int id = i == ids.size() ? -1 : ids.get(i);
            final BiomeTable.Entry entry = BiomeTable.get(id);
            if (i > 0) {
                result.append(',');
            }
            result.append("{\"id\":").append(id)
                .append(",\"kind\":\"").append(entry.kind().name()).append('"')
                .append(",\"snowLine\":");
            final java.util.OptionalInt snowLine = BiomeTable.altitudeSnowLine(id);
            if (snowLine.isPresent()) {
                result.append(snowLine.getAsInt());
            } else {
                result.append("null");
            }
            result
                .append(",\"waterBiome\":").append(entry.waterBiome())
                .append(",\"grassTint\":").append(palette.grassTint(id) & 0xFFFFFF)
                .append(",\"foliageTint\":").append(palette.foliageTint(id) & 0xFFFFFF)
                .append(",\"surfaceColor\":").append(surfaceColor(entry.kind(), id, palette))
                .append(",\"canopyColor\":").append(palette.canopyColor(id) & 0xFFFFFF)
                .append(",\"waterTint\":").append(palette.waterTint(id) & 0xFFFFFF)
                .append('}');
        }
        result.append(']');
    }

    private static int surfaceColor(
        final SurfaceKind kind,
        final int biomeId,
        final PredictionPalette palette
    ) {
        return switch (kind) {
            case SAND -> palette.sandBase & 0xFFFFFF;
            case SNOW -> palette.snowBase & 0xFFFFFF;
            case ICE -> palette.iceBase & 0xFFFFFF;
            case FOLIAGE -> palette.canopyColor(biomeId) & 0xFFFFFF;
            default -> palette.groundColor(biomeId) & 0xFFFFFF;
        };
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
