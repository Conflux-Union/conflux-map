package cn.net.rms.confluxmap.mc.radar;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Radar portraits are derived from the entity's own vanilla model at runtime. No pre-drawn icon
 * sheet from another project may come back: a bundled sheet only covers the species that shipped
 * with it, freezes their look against the running version's models, and drags a foreign license
 * into the jar.
 */
final class RadarIconSourceTest {
    @Test
    void shipsNoPreDrawnEntityIconSheet() {
        final Path radarTextures = findProjectRoot()
            .resolve("src/main/resources/assets/confluxmap/textures/radar");

        assertFalse(Files.exists(radarTextures.resolve("entity_icons.png")));
        assertFalse(Files.exists(radarTextures.resolve("entity_icons_layout.json")));
        assertFalse(Files.exists(radarTextures.resolve("ENTITY_ICONS_LICENSE.txt")));
        assertFalse(Files.exists(
            findProjectRoot().resolve("docs/reference-specs/entity-icon-cellmap.json")
        ));
    }

    @Test
    void portraitsComeFromTheLiveModelInsteadOfALookupTable() throws IOException {
        final String manager = Files.readString(findProjectRoot().resolve(
            "src/main/java/cn/net/rms/confluxmap/mc/radar/EntityIconManager.java"
        ));

        assertFalse(manager.contains("BUNDLED"), "no bundled sprite-sheet lookup may return");
        assertTrue(
            manager.contains("EntityHeadGeometry.projectNeutral("),
            "portraits must keep being baked from the entity's own neutral model geometry"
        );
        assertTrue(
            manager.contains("OffscreenCanvas.atlasDrawPlaneZ()"),
            "portrait baking must use the canvas depth valid outside a GUI pass"
        );
    }

    @Test
    void documentsNoThirdPartyIconAssets() throws IOException {
        final Path root = findProjectRoot();

        assertFalse(Files.readString(root.resolve("THIRD_PARTY_NOTICES.md")).contains("Entity-Icons"));
        assertFalse(
            Files.readString(root.resolve("docs/reference-specs/README.md"))
                .contains("entity-icon-cellmap.json"),
            "the reference-spec index must not point at a removed cell map"
        );
    }

    private static Path findProjectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("common.gradle"))
                && Files.isDirectory(current.resolve("src/main/java"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate the Conflux Map project root");
    }
}
