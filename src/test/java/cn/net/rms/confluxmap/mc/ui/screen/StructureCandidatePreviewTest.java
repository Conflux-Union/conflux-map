package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.predict.StructureIndex;
import java.util.List;
import org.junit.jupiter.api.Test;

final class StructureCandidatePreviewTest {
    @Test
    void projectsCandidatesWithMinecraftNorthAtTheTopOfThePreview() {
        final List<StructureIndex.Marker> candidates = List.of(
            marker(-100, -100),
            marker(100, 100)
        );
        final StructureCandidatePreview.Layout layout = StructureCandidatePreview.layout(
            0, 0, 160, 120, 0, 0, candidates
        );

        assertTrue(layout.centerScreenX(-100) < layout.centerScreenX(100));
        assertTrue(layout.centerScreenY(100) < layout.centerScreenY(-100));
    }

    @Test
    void hitTestingSelectsTheNearestNumberedCandidate() {
        final List<StructureIndex.Marker> candidates = List.of(
            marker(-100, 0),
            marker(100, 0)
        );
        final StructureCandidatePreview.Layout layout = StructureCandidatePreview.layout(
            10, 20, 160, 120, 0, 0, candidates
        );

        assertEquals(
            1,
            layout.candidateAt(candidates, layout.centerScreenX(100), layout.centerScreenY(0), 7)
        );
    }

    private static StructureIndex.Marker marker(final int x, final int z) {
        return new StructureIndex.Marker(
            StructureIndex.StructureType.VILLAGE,
            x,
            z,
            StructureIndex.State.CANDIDATE
        );
    }
}
