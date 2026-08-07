package cn.net.rms.confluxmap.core.radar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HeadPartSelectorTest {
    @Test
    void identifiesModelsThatNeedTheirWholeGeometry() {
        assertTrue(HeadPartSelector.usesFullModel("MINECRAFT:SLIME"));
        assertFalse(HeadPartSelector.usesFullModel("minecraft:zombie"));
        assertFalse(HeadPartSelector.usesFullModel(null));
    }

    @Test
    void prefersAllNamedHeadsForMultiHeadedModels() {
        assertEquals(
            Set.of("root/center_head", "root/right_head", "root/left_head"),
            HeadPartSelector.select("minecraft:wither", List.of(
                "root/body", "root/center_head", "root/right_head", "root/left_head"
            ))
        );
    }

    @Test
    void keepsVillagerHatWithItsHead() {
        assertEquals(
            Set.of("root/head", "root/head/hat"),
            HeadPartSelector.select("minecraft:villager", List.of(
                "root/body", "root/head", "root/head/hat"
            ))
        );
    }

    @Test
    void fallsBackThroughHeadPartsBodyCubeSegmentsAndRoot() {
        assertEquals(Set.of("root/head_parts"), HeadPartSelector.select(
            "example:horse", List.of("root/body", "root/head_parts")
        ));
        assertEquals(Set.of("root/body"), HeadPartSelector.select(
            "example:body_only", List.of("root/body", "root/tail")
        ));
        assertEquals(Set.of("root/cube"), HeadPartSelector.select(
            "example:cube", List.of("root/cube")
        ));
        assertEquals(Set.of("root/segment0", "root/segment1"), HeadPartSelector.select(
            "example:segments", List.of("root/segment2", "root/segment1", "root/segment0")
        ));
        assertEquals(Set.of("root"), HeadPartSelector.select("example:unknown", List.of("root")));
    }

    @Test
    void keepsSpiderHeadAndFirstBodySegment() {
        assertEquals(
            Set.of("root/head", "root/body0"),
            HeadPartSelector.select("minecraft:spider", List.of(
                "root/head", "root/body0", "root/body1"
            ))
        );
    }
}
