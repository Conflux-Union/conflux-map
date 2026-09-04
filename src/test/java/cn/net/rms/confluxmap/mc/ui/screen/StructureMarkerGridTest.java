package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.predict.StructureIndex.Marker;
import cn.net.rms.confluxmap.core.predict.StructureIndex.State;
import cn.net.rms.confluxmap.core.predict.StructureIndex.StructureType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class StructureMarkerGridTest {
    @Test
    void panningKeepsWinnersInSharedCellsAndCoversBothEdges() {
        final List<Marker> candidates = new ArrayList<>();
        for (int x = -1024; x <= 1024; x += 32) {
            for (int z = -256; z <= 256; z += 32) {
                candidates.add(fossil(x, z));
            }
        }
        final int size = StructureMarkerGrid.cellSize(2);
        final List<Marker> before = viewport(candidates, -400, 400, size);
        final List<Marker> after = viewport(candidates, -300, 500, size);
        assertTrue(before.size() > 8);
        assertTrue(before.stream().anyMatch(m -> m.blockX() < -300));
        assertTrue(before.stream().anyMatch(m -> m.blockX() > 300));
        assertEquals(
            before.stream().filter(m -> m.blockX() >= -256 && m.blockX() < 384).toList(),
            after.stream().filter(m -> m.blockX() >= -256 && m.blockX() < 384).toList()
        );
        Collections.reverse(candidates);
        assertEquals(before, viewport(candidates, -400, 400, size));
        assertTrue(StructureMarkerGrid.select(candidates, StructureMarkerGrid.cellSize(0.5)).size()
            > StructureMarkerGrid.select(candidates, size).size());
    }

    @Test
    void queriesWholeNegativeCellsAndKeepsDifferentStructureTypes() {
        assertEquals(-64, StructureMarkerGrid.minimum(-1, 64));
        assertEquals(-1, StructureMarkerGrid.maximum(-1, 64));
        assertEquals(63, StructureMarkerGrid.maximum(0, 64));
        assertEquals(2, StructureMarkerGrid.select(List.of(
            fossil(0, 0), fossil(32, 32),
            new Marker(StructureType.FORTRESS, 0, 0, State.CANDIDATE)
        ), 64).size());
    }

    private static List<Marker> viewport(
        final List<Marker> candidates, final int min, final int max, final int size
    ) {
        return StructureMarkerGrid.select(candidates.stream().filter(m ->
            m.blockX() >= StructureMarkerGrid.minimum(min, size)
                && m.blockX() <= StructureMarkerGrid.maximum(max, size)
        ).toList(), size);
    }

    private static Marker fossil(final int x, final int z) {
        return new Marker(StructureType.NETHER_FOSSIL, x, z, State.CANDIDATE);
    }
}
