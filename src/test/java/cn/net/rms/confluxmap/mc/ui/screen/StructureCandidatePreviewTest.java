package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.predict.StructureIndex;
import java.util.List;
import org.junit.jupiter.api.Test;

final class StructureCandidatePreviewTest {
    @Test
    void projectsCandidatesWithTheSameNorthUpOrientationAsTheFullscreenMap() {
        final List<StructureIndex.Marker> candidates = List.of(
            marker(-100, -100),
            marker(100, 100)
        );
        final StructureCandidatePreview.Layout layout = StructureCandidatePreview.layout(
            0, 0, 160, 120, 0, 0, 0, 0, candidates
        );

        assertTrue(layout.screenX(-100) < layout.screenX(100));
        assertTrue(layout.screenY(-100) < layout.screenY(100));
    }

    @Test
    void hitTestingSelectsTheNearestNumberedCandidate() {
        final List<StructureIndex.Marker> candidates = List.of(
            marker(-100, 0),
            marker(100, 0)
        );
        final StructureCandidatePreview.Layout layout = StructureCandidatePreview.layout(
            10, 20, 160, 120, 0, 0, 0, 0, candidates
        );

        assertEquals(
            1,
            layout.candidateAt(candidates, layout.screenX(100), layout.screenY(0), 7)
        );
    }

    @Test
    void keepsThePlayerInsideTheTerrainPreview() {
        final StructureCandidatePreview.Layout layout = StructureCandidatePreview.layout(
            10, 20, 160, 120, 0, 0, 500, -300, List.of(marker(100, 100))
        );

        assertTrue(layout.screenX(500) >= layout.x());
        assertTrue(layout.screenX(500) < layout.x() + layout.width());
        assertTrue(layout.screenY(-300) >= layout.y());
        assertTrue(layout.screenY(-300) < layout.y() + layout.height());
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
