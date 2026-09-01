package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.confluxmap.core.predict.StructureIndex;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

final class StructureCandidateScreenTest {
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

    @Test
    void variantPickerOffersAllVariantsAfterTheUnfilteredChoice() {
        assertEquals(
            List.of(
                OptionalInt.empty(),
                OptionalInt.of(0),
                OptionalInt.of(1),
                OptionalInt.of(2),
                OptionalInt.of(3)
            ),
            StructureVariantPickerScreen.options(
                StructureIndex.StructureType.BASTION_REMNANT
            )
        );
    }

    @Test
    void variantPickerUsesVariantNamesAndAnAllVariantsLabel() {
        assertEquals(
            "confluxmap.screen.structure_candidates.all_variants",
            StructureVariantPickerScreen.labelKey(
                StructureIndex.StructureType.END_CITY,
                OptionalInt.empty()
            )
        );
        assertEquals(
            "confluxmap.structure.end_city.ship",
            StructureVariantPickerScreen.labelKey(
                StructureIndex.StructureType.END_CITY,
                OptionalInt.of(1)
            )
        );
    }
}
