package cn.net.rms.confluxmap.server.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.predict.WorldPreset;
import java.util.List;
import org.junit.jupiter.api.Test;

final class WebMapManifestTest {
    @Test
    void authorityPaletteDoesNotRequireSharingTheWorldSeed() {
        final WebMapManifest manifest = new WebMapManifest(
            "private-world",
            "1.21.8",
            null,
            -1,
            List.of(new WebMapManifest.Dimension(
                0,
                "minecraft:overworld",
                "overworld",
                true,
                WorldPreset.DEFAULT
            ))
        );

        final String json = manifest.toJson();
        assertTrue(json.contains("\"predictionAvailable\":false"));
        assertTrue(json.contains("\"predictionBiomes\":["));
        assertFalse(json.contains("\"seed\":"));
    }

    @Test
    void publishesTheEffectiveBrowserRequestLimits() {
        final WebMapManifest manifest = new WebMapManifest(
            "limited-world",
            "1.21.8",
            null,
            -1,
            List.of(),
            new WebMapManifest.Limits(4, 350)
        );

        final String json = manifest.toJson();

        assertTrue(json.contains(
            "\"limits\":{\"maxTilesPerRequest\":4,\"minRequestIntervalMs\":350}"
        ));
    }
}
