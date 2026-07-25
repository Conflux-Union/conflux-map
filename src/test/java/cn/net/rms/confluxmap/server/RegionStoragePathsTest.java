package cn.net.rms.confluxmap.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RegionStoragePathsTest {
    @Test
    void resolvesVanillaAndCustomDimensionRegionDirectories() {
        final Path root = Path.of("world");

        assertEquals(root.resolve("region"), RegionStoragePaths.regionDirectory(root, "minecraft:overworld"));
        assertEquals(root.resolve("DIM-1/region"), RegionStoragePaths.regionDirectory(root, "minecraft:the_nether"));
        assertEquals(root.resolve("DIM1/region"), RegionStoragePaths.regionDirectory(root, "minecraft:the_end"));
        assertEquals(
            root.resolve("dimensions/example/moon/craters/region"),
            RegionStoragePaths.regionDirectory(root, "example:moon/craters")
        );
    }

    @Test
    void invalidDimensionSegmentsCannotEscapeTheWorldFolder() {
        final Path root = Path.of("world");

        assertEquals(
            root.resolve("dimensions/example/unknown/outside/region"),
            RegionStoragePaths.regionDirectory(root, "example:../outside")
        );
    }
}
