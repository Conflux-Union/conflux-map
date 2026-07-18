package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import cn.net.rms.confluxmap.core.model.WorldIdentity;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StructureIndexTest {
    @TempDir
    Path tempDir;

    @Test
    void structureMarkersDoNotLeakBetweenWorlds() {
        final Path cacheRoot = tempDir.resolve("cache");
        final long firstWorldMarker = pack(32, 48);
        final long secondWorldMarker = pack(96, 112);

        final StructureIndex firstWorld = new StructureIndex(
            cacheRoot,
            WorldIdentity.singleplayer("first-world"),
            "minecraft:overworld",
            (type, regionX, regionZ) -> type == StructureIndex.StructureType.VILLAGE
                ? new long[] {firstWorldMarker} : new long[0]
        );
        assertEquals(1, firstWorld.query(0, 128, 0, 128).size());
        firstWorld.save();

        final StructureIndex secondWorld = new StructureIndex(
            cacheRoot,
            WorldIdentity.singleplayer("second-world"),
            "minecraft:overworld",
            (type, regionX, regionZ) -> type == StructureIndex.StructureType.VILLAGE
                ? new long[] {secondWorldMarker} : new long[0]
        );
        final List<StructureIndex.Marker> markers = secondWorld.query(0, 128, 0, 128);

        assertEquals(1, markers.size());
        assertFalse(markers.stream().anyMatch(marker -> marker.blockX() == 32 && marker.blockZ() == 48));
        assertEquals(96, markers.get(0).blockX());
        assertEquals(112, markers.get(0).blockZ());
    }

    private static long pack(final int x, final int z) {
        return ((long) x << 32) | (z & 0xFFFF_FFFFL);
    }
}
