package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import java.nio.file.Path;
import java.util.ArrayList;
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
            DimensionId.OVERWORLD,
            (type, regionX, regionZ) -> type == StructureIndex.StructureType.VILLAGE
                ? new long[] {firstWorldMarker} : new long[0]
        );
        assertEquals(1, firstWorld.query(0, 128, 0, 128).size());
        firstWorld.save();

        final StructureIndex secondWorld = new StructureIndex(
            cacheRoot,
            WorldIdentity.singleplayer("second-world"),
            DimensionId.OVERWORLD,
            (type, regionX, regionZ) -> type == StructureIndex.StructureType.VILLAGE
                ? new long[] {secondWorldMarker} : new long[0]
        );
        final List<StructureIndex.Marker> markers = secondWorld.query(0, 128, 0, 128);

        assertEquals(1, markers.size());
        assertFalse(markers.stream().anyMatch(marker -> marker.blockX() == 32 && marker.blockZ() == 48));
        assertEquals(96, markers.get(0).blockX());
        assertEquals(112, markers.get(0).blockZ());
    }

    @Test
    void queryUsesStructureSpecificRegionsAndDoesNotRepeatCoveredQueries() {
        final List<String> requests = new ArrayList<>();
        final StructureIndex index = new StructureIndex(
            tempDir.resolve("cache"),
            WorldIdentity.singleplayer("world"),
            DimensionId.OVERWORLD,
            (type, regionX, regionZ) -> {
                requests.add(type.id() + ":" + regionX + ":" + regionZ);
                return new long[0];
            }
        );

        index.query(0, 639, 0, 639);
        final List<String> firstQuery = List.copyOf(requests);
        index.query(0, 639, 0, 639);

        assertEquals(14, firstQuery.size());
        assertEquals(List.of(
            "village:0:0",
            "village:1:0",
            "village:0:1",
            "village:1:1",
            "ocean_monument:0:0",
            "ocean_monument:1:0",
            "ocean_monument:0:1",
            "ocean_monument:1:1",
            "woodland_mansion:0:0",
            "pillager_outpost:0:0",
            "pillager_outpost:1:0",
            "pillager_outpost:0:1",
            "pillager_outpost:1:1",
            "ruined_portal:0:0"
        ), firstQuery);
        assertEquals(firstQuery, requests);
    }

    @Test
    void structureTypesAreRestrictedToTheirGenerationDimension() {
        assertTrue(StructureIndex.StructureType.VILLAGE.supports(DimensionId.OVERWORLD));
        assertFalse(StructureIndex.StructureType.VILLAGE.supports(DimensionId.END));
        assertTrue(StructureIndex.StructureType.END_CITY.supports(DimensionId.END));
        assertFalse(StructureIndex.StructureType.END_CITY.supports(DimensionId.OVERWORLD));
    }

    private static long pack(final int x, final int z) {
        return ((long) x << 32) | (z & 0xFFFF_FFFFL);
    }
}
