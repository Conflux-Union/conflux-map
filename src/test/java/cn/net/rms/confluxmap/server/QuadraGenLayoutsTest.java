package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.server.QuadraGenLayouts.Config;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link QuadraGenLayouts#parse} is the only MC-free part of quadra-gen detection, and the gate
 * that decides whether prediction may trust a quadrant layout at all: a config this build does
 * not fully understand must parse to empty rather than half-apply someone's quadrants.
 */
class QuadraGenLayoutsTest {
    /** quadra-gen's bundled default config, verbatim (schema 1). */
    private static final String DEFAULT_CONFIG = """
        {
          "schema_version": 1,
          "enabled": true,
          "enabled_in_singleplayer": false,
          "overworld": {
            "enabled": true,
            "advertised_world_type": "auto",
            "quadrants": {
              "x_positive_z_positive": {
                "generator": "flat",
                "clear_generated_content": false,
                "flat": { "biome": "minecraft:the_void", "layers": [] }
              },
              "x_positive_z_negative": {
                "generator": "flat",
                "clear_generated_content": false,
                "flat": {
                  "biome": "minecraft:plains",
                  "layers": [ { "block": "minecraft:white_stained_glass", "count": 1 } ]
                }
              },
              "x_negative_z_positive": {
                "generator": "noise",
                "clear_generated_content": true
              },
              "x_negative_z_negative": {
                "generator": "noise",
                "clear_generated_content": false
              }
            }
          },
          "nether": {
            "enabled": true,
            "advertised_world_type": "auto",
            "quadrants": {
              "x_positive_z_positive": {
                "generator": "flat",
                "clear_generated_content": false,
                "flat": { "biome": "minecraft:the_void", "layers": [] }
              },
              "x_positive_z_negative": {
                "generator": "flat",
                "clear_generated_content": false,
                "flat": {
                  "biome": "minecraft:nether_wastes",
                  "layers": [ { "block": "minecraft:white_stained_glass", "count": 1 } ]
                }
              },
              "x_negative_z_positive": {
                "generator": "noise",
                "clear_generated_content": true
              },
              "x_negative_z_negative": {
                "generator": "noise",
                "clear_generated_content": false
              }
            }
          }
        }
        """;

    @TempDir
    Path tempDir;

    @Test
    void parsesTheBundledDefaultConfig() throws Exception {
        final Path file = write("config.json", DEFAULT_CONFIG);

        final Optional<Config> parsed = QuadraGenLayouts.parse(file);

        assertTrue(parsed.isPresent());
        final Config config = parsed.get();
        assertEquals(1, config.schemaVersion);
        assertTrue(config.enabled);
        assertFalse(config.enabledInSingleplayer);
        final QuadraGenLayouts.DimensionSpec overworld = config.overworld;
        assertTrue(overworld.enabled);
        assertEquals(4, overworld.quadrants.size());
        final QuadraGenLayouts.QuadrantSpec glass =
            overworld.quadrants.get("x_positive_z_negative");
        assertEquals("flat", glass.generator);
        assertEquals(1, glass.flat.layers.get(0).count);
        assertEquals("minecraft:white_stained_glass", glass.flat.layers.get(0).block);
        final QuadraGenLayouts.QuadrantSpec cleared =
            overworld.quadrants.get("x_negative_z_positive");
        assertEquals("noise", cleared.generator);
        assertTrue(cleared.clearGeneratedContent);
    }

    @Test
    void anUnknownSchemaVersionIsRefused() throws Exception {
        final Path file = write(
            "config.json", DEFAULT_CONFIG.replace("\"schema_version\": 1", "\"schema_version\": 2")
        );

        assertTrue(QuadraGenLayouts.parse(file).isEmpty());
    }

    @Test
    void malformedJsonIsRefused() throws Exception {
        final Path file = write("config.json", "{ not json");

        assertTrue(QuadraGenLayouts.parse(file).isEmpty());
    }

    @Test
    void aMissingFileIsRefused() {
        assertTrue(QuadraGenLayouts.parse(tempDir.resolve("config.json")).isEmpty());
    }

    private Path write(final String name, final String content) throws Exception {
        final Path file = tempDir.resolve(name);
        Files.writeString(file, content);
        return file;
    }
}
