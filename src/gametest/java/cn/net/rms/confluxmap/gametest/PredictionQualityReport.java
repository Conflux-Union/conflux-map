package cn.net.rms.confluxmap.gametest;

import cn.net.rms.confluxmap.core.quality.PredictionQualityCorpus;
import cn.net.rms.confluxmap.core.quality.PredictionQualityEvaluator;
import cn.net.rms.confluxmap.core.util.Argb;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;

/** Writes machine-readable metrics plus browsable worst-case render artifacts. */
final class PredictionQualityReport {
    private static final int WORST_ARTIFACTS = 5;

    private PredictionQualityReport() {
    }

    static void write(
        final Path directory,
        final long worldSeed,
        final List<PredictionQualitySampleResult> source
    ) throws IOException {
        resetDirectory(directory);
        final List<PredictionQualitySampleResult> results = new ArrayList<>(source);
        results.sort(Comparator.comparingDouble(result -> result.metrics().combinedScore()));

        final JsonObject root = new JsonObject();
        root.addProperty("worldSeed", worldSeed);
        root.addProperty("corpusSeed", PredictionQualityCorpus.DEFAULT_CORPUS_SEED);
        root.addProperty("sampleCount", results.size());
        root.add("aggregate", aggregateJson(results));
        final JsonArray samples = new JsonArray();
        root.add("samples", samples);

        for (int i = 0; i < results.size(); i++) {
            final PredictionQualitySampleResult result = results.get(i);
            final JsonObject sample = new JsonObject();
            sample.addProperty("id", result.sample().id());
            sample.addProperty("dimension", result.sample().dimension().toString());
            sample.addProperty("tileX", result.sample().tileX());
            sample.addProperty("tileZ", result.sample().tileZ());
            sample.addProperty("dominantBiomeId", result.dominantBiomeId());
            sample.add("metrics", metricsJson(result.metrics()));
            if (i < Math.min(WORST_ARTIFACTS, results.size())) {
                final String prefix = String.format(Locale.ROOT, "worst-%02d-%s", i + 1, result.sample().id());
                writeImage(directory.resolve(prefix + "-generated.png"), result.reference());
                writeImage(directory.resolve(prefix + "-predicted.png"), result.prediction());
                writeDiff(directory.resolve(prefix + "-diff.png"), result.reference(), result.prediction());
                sample.addProperty("generatedImage", prefix + "-generated.png");
                sample.addProperty("predictedImage", prefix + "-predicted.png");
                sample.addProperty("diffImage", prefix + "-diff.png");
            }
            samples.add(sample);
        }

        Files.writeString(
            directory.resolve("report.json"),
            new GsonBuilder().setPrettyPrinting().create().toJson(root),
            StandardCharsets.UTF_8
        );
        Files.writeString(directory.resolve("index.html"), html(worldSeed, results), StandardCharsets.UTF_8);
    }

    private static JsonObject aggregateJson(final List<PredictionQualitySampleResult> results) {
        final JsonObject aggregate = new JsonObject();
        aggregate.addProperty("meanCombinedScore", PredictionQualityAggregate.mean(results, metrics -> metrics.combinedScore()));
        aggregate.addProperty(
            "minimumCombinedScore",
            PredictionQualityAggregate.minimum(results, metrics -> metrics.combinedScore())
        );
        aggregate.addProperty("meanCoverageAccuracy", PredictionQualityAggregate.mean(results, metrics -> metrics.coverageAccuracy()));
        aggregate.addProperty(
            "meanSurfaceKindAccuracy",
            PredictionQualityAggregate.mean(results, metrics -> metrics.surfaceKindAccuracy())
        );
        aggregate.addProperty("meanHeightMae", PredictionQualityAggregate.mean(results, metrics -> metrics.heightMae()));
        aggregate.addProperty(
            "meanHeightWithinTwo",
            PredictionQualityAggregate.mean(results, metrics -> metrics.heightWithinTwo())
        );
        aggregate.addProperty(
            "waterWeightedFluidBucketAccuracy",
            PredictionQualityAggregate.waterWeightedFluidAccuracy(results)
        );
        aggregate.addProperty(
            "meanColorSimilarity",
            PredictionQualityAggregate.mean(results, metrics -> metrics.colorSimilarity())
        );
        aggregate.addProperty(
            "meanStructuralSimilarity",
            PredictionQualityAggregate.mean(results, metrics -> metrics.structuralSimilarity())
        );
        aggregate.addProperty("meanEdgeF1", PredictionQualityAggregate.mean(results, metrics -> metrics.edgeF1()));
        return aggregate;
    }

    private static JsonObject metricsJson(final PredictionQualityEvaluator.Metrics metrics) {
        final JsonObject json = new JsonObject();
        json.addProperty("evaluatedPixels", metrics.evaluatedPixels());
        json.addProperty("evaluatedWaterPixels", metrics.evaluatedWaterPixels());
        json.addProperty("coverageAccuracy", metrics.coverageAccuracy());
        json.addProperty("surfaceKindAccuracy", metrics.surfaceKindAccuracy());
        json.addProperty("heightMae", metrics.heightMae());
        json.addProperty("heightP95", metrics.heightP95());
        json.addProperty("heightWithinTwo", metrics.heightWithinTwo());
        json.addProperty("fluidBucketAccuracy", metrics.fluidBucketAccuracy());
        json.addProperty("colorSimilarity", metrics.colorSimilarity());
        json.addProperty("structuralSimilarity", metrics.structuralSimilarity());
        json.addProperty("edgeF1", metrics.edgeF1());
        json.addProperty("combinedScore", metrics.combinedScore());
        return json;
    }

    private static void writeImage(
        final Path file,
        final PredictionQualityEvaluator.TileData tile
    ) throws IOException {
        final BufferedImage image = new BufferedImage(tile.width(), tile.height(), BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, tile.width(), tile.height(), tile.pixels(), 0, tile.width());
        ImageIO.write(image, "png", file.toFile());
    }

    private static void writeDiff(
        final Path file,
        final PredictionQualityEvaluator.TileData reference,
        final PredictionQualityEvaluator.TileData prediction
    ) throws IOException {
        final int[] pixels = new int[reference.pixels().length];
        for (int i = 0; i < pixels.length; i++) {
            final int actual = reference.pixels()[i];
            final int predicted = prediction.pixels()[i];
            if ((Argb.alpha(actual) == 0) != (Argb.alpha(predicted) == 0)) {
                pixels[i] = 0xFFFF00FF;
                continue;
            }
            final int delta = Math.min(255, (
                Math.abs(Argb.red(actual) - Argb.red(predicted))
                    + Math.abs(Argb.green(actual) - Argb.green(predicted))
                    + Math.abs(Argb.blue(actual) - Argb.blue(predicted))
            ) / 2);
            pixels[i] = 0xFF000000 | (delta << 16) | (Math.max(0, delta - 96) << 8);
        }
        final BufferedImage image = new BufferedImage(reference.width(), reference.height(), BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, reference.width(), reference.height(), pixels, 0, reference.width());
        ImageIO.write(image, "png", file.toFile());
    }

    private static String html(final long worldSeed, final List<PredictionQualitySampleResult> results) {
        final StringBuilder body = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            final PredictionQualitySampleResult result = results.get(i);
            final PredictionQualityEvaluator.Metrics metrics = result.metrics();
            body.append("<tr><td>").append(result.sample().id()).append("</td><td>")
                .append(result.dominantBiomeId()).append("</td><td>")
                .append(percent(metrics.combinedScore())).append("</td><td>")
                .append(percent(metrics.coverageAccuracy())).append("</td><td>")
                .append(percent(metrics.surfaceKindAccuracy())).append("</td><td>")
                .append(String.format(Locale.ROOT, "%.2f / %.0f", metrics.heightMae(), metrics.heightP95()))
                .append("</td><td>")
                .append(percent(metrics.colorSimilarity())).append("</td><td>")
                .append(percent(metrics.structuralSimilarity())).append("</td><td>")
                .append(percent(metrics.edgeF1())).append("</td><td>");
            if (i < Math.min(WORST_ARTIFACTS, results.size())) {
                final String prefix = String.format(Locale.ROOT, "worst-%02d-%s", i + 1, result.sample().id());
                body.append("<a href=\"").append(prefix).append("-generated.png\">generated</a> ")
                    .append("<a href=\"").append(prefix).append("-predicted.png\">predicted</a> ")
                    .append("<a href=\"").append(prefix).append("-diff.png\">diff</a>");
            }
            body.append("</td></tr>\n");
        }
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>Prediction quality</title>"
            + "<style>body{font:14px system-ui;background:#111;color:#eee;padding:24px}table{border-collapse:collapse;width:100%}"
            + "th,td{padding:7px 9px;border:1px solid #444;text-align:right}th:first-child,td:first-child{text-align:left}"
            + "a{color:#7ec8ff}code{color:#ffd580}</style></head><body><h1>Prediction quality</h1>"
            + "<p>World seed: <code>" + worldSeed + "</code>; corpus seed: <code>"
            + PredictionQualityCorpus.DEFAULT_CORPUS_SEED + "</code>; samples: " + results.size() + "; mean score: "
            + percent(PredictionQualityAggregate.mean(results, metrics -> metrics.combinedScore()))
            + "; minimum: "
            + percent(PredictionQualityAggregate.minimum(results, metrics -> metrics.combinedScore()))
            + "</p>"
            + "<table><thead><tr><th>Sample</th><th>Biome ID</th><th>Combined</th><th>Coverage</th><th>Kind</th>"
            + "<th>Height MAE/P95</th><th>Color</th><th>SSIM</th><th>Edge F1</th><th>Artifacts</th></tr></thead><tbody>"
            + body + "</tbody></table></body></html>";
    }

    private static String percent(final double value) {
        return String.format(Locale.ROOT, "%.2f%%", value * 100.0);
    }

    private static void resetDirectory(final Path directory) throws IOException {
        Files.createDirectories(directory);
        try (var files = Files.list(directory)) {
            for (final Path file : files.toList()) {
                final String name = file.getFileName().toString();
                if (Files.isRegularFile(file) && (
                    name.equals("report.json")
                        || name.equals("index.html")
                        || name.startsWith("worst-")
                )) {
                    Files.delete(file);
                }
            }
        }
    }

}
