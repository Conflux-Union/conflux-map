package cn.net.rms.confluxmap.mc.radar;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

final class ItemIconOutlineTextureTest {
    private static final Pattern NON_LIVING_MODEL_ENTITY = Pattern.compile(
        "updateForNonLivingEntity\\(\\s*[^,]+,\\s*[^,]+,\\s*[^,]+,\\s*entity\\s*\\)",
        Pattern.DOTALL
    );
    private static final Pattern NULL_NON_LIVING_MODEL_ENTITY = Pattern.compile(
        "updateForNonLivingEntity\\(\\s*[^,]+,\\s*[^,]+,\\s*[^,]+,\\s*null\\s*\\)",
        Pattern.DOTALL
    );

    @Test
    void radarItemOutlineKeepsTheLiveEntityThroughModelResolution() throws IOException {
        final Path radar = findProjectRoot().resolve(
            "src/main/java/cn/net/rms/confluxmap/mc/radar"
        );
        final String renderer = Files.readString(radar.resolve("RadarMarkerRenderer.java"));
        final String manager = Files.readString(radar.resolve("EntityIconManager.java"));
        final String outline = Files.readString(radar.resolve("ItemIconOutlineTexture.java"))
            .replace("//$$ ", "");

        assertTrue(
            renderer.contains("iconManager, itemIcon, live, x, y"),
            "the shared HUD/fullscreen renderer must forward the live non-living entity"
        );
        assertTrue(
            manager.contains("itemOutlineTexture.bind(client, stack, entity)"),
            "the icon manager must not drop the entity before item-model resolution"
        );
        assertTrue(
            NON_LIVING_MODEL_ENTITY.matcher(outline).find(),
            "the vanilla non-living item-model API requires the source entity"
        );
        assertFalse(
            NULL_NON_LIVING_MODEL_ENTITY.matcher(outline).find(),
            "passing null makes vanilla dereference a missing entity while rendering the radar"
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
