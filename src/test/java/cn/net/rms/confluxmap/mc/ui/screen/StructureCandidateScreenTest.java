package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

final class StructureCandidateScreenTest {
    @Test
    void compactWindowStillShowsFourCandidateRows() {
        assertEquals(4, StructureCandidateScreen.visibleRowsForHeight(240));
    }

    @Test
    void splitCandidateRowKeepsEnoughSpaceForCoordinates() {
        final StructureCandidateScreen.CandidateRowLayout layout =
            StructureCandidateScreen.candidateRowLayout(188);

        assertEquals(75, layout.coordinateWidth());
        assertEquals(41, layout.locateWidth());
        assertEquals(60, layout.waypointWidth());
    }

    @Test
    void candidateWaypointAlwaysReceivesAYCoordinate() {
        assertEquals(82, FullscreenMapScreen.candidateWaypointY(
            OptionalInt.of(82), OptionalInt.of(70)
        ));
        assertEquals(70, FullscreenMapScreen.candidateWaypointY(
            OptionalInt.empty(), OptionalInt.of(70)
        ));
        assertEquals(64, FullscreenMapScreen.candidateWaypointY(
            OptionalInt.empty(), OptionalInt.empty()
        ));
    }
}
