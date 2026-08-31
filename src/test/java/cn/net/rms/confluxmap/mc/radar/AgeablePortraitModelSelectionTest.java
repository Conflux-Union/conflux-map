package cn.net.rms.confluxmap.mc.radar;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class AgeablePortraitModelSelectionTest {
    @Test
    void portraitBakeSelectsTheModelMatchingTheEntityAge() throws IOException {
        final Path root = findProjectRoot();
        final String manager = Files.readString(root.resolve(
            "src/main/java/cn/net/rms/confluxmap/mc/radar/EntityIconManager.java"
        )).replace("//$$ ", "");
        final String accessor = Files.readString(root.resolve(
            "src/main/java/cn/net/rms/confluxmap/mixin/AgeableMobEntityRendererAccessor.java"
        )).replace("//$$ ", "");

        assertTrue(
            manager.contains("model = portraitModel(livingRenderer, entity, state);"),
            "portrait baking must not reuse the last ageable model rendered in the world"
        );
        assertTrue(manager.contains("state.baby"), "mapped versions must select by the baby render state");
        assertTrue(manager.contains("state.isBaby"), "unobfuscated versions must select by the baby render state");
        assertTrue(accessor.contains("@Accessor(\"adultModel\")"));
        assertTrue(accessor.contains("@Accessor(\"babyModel\")"));
    }

    @Test
    void ageableAccessorStartsAtTheFirstDualModelVersion() throws IOException {
        final Path root = findProjectRoot();
        final String base = Files.readString(root.resolve("src/main/resources/confluxmap.mixins.json"));
        final String oneTwentyOneOne = Files.readString(root.resolve(
            "versions/1.21.1/src/main/resources/confluxmap.mixins.json"
        ));
        final String oneTwentyOneThree = Files.readString(root.resolve(
            "versions/1.21.3/src/main/resources/confluxmap.mixins.json"
        ));

        assertFalse(base.contains("AgeableMobEntityRendererAccessor"));
        assertFalse(oneTwentyOneOne.contains("AgeableMobEntityRendererAccessor"));
        assertTrue(oneTwentyOneThree.contains("AgeableMobEntityRendererAccessor"));
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
