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
    void findsHeadsThroughRealVanillaPartTrees() {
        assertEquals(Set.of("root/head"), HeadPartSelector.select("minecraft:creeper", List.of(
            "root", "root/head", "root/body", "root/right_hind_leg", "root/left_hind_leg",
            "root/right_front_leg", "root/left_front_leg"
        )));
        assertEquals(Set.of("root/bone/body/head"), HeadPartSelector.select("minecraft:warden", List.of(
            "root", "root/bone", "root/bone/body", "root/bone/body/head",
            "root/bone/body/right_arm", "root/bone/right_leg"
        )));
        assertEquals(Set.of("root/head"), HeadPartSelector.select("minecraft:llama", List.of(
            "root", "root/head", "root/body", "root/right_hind_leg", "root/chest_right"
        )));
        assertEquals(
            Set.of("root/head", "root/right_ear", "root/left_ear", "root/nose"),
            HeadPartSelector.select("minecraft:rabbit", List.of(
                "root", "root/head", "root/body", "root/right_ear", "root/left_ear",
                "root/tail", "root/nose"
            ))
        );
        assertEquals(Set.of("root/head"), HeadPartSelector.select("minecraft:shulker", List.of(
            "root", "root/base", "root/lid", "root/head"
        )));
    }

    @Test
    void fallsBackThroughHeadPartsBodyCubeSegmentsAndNothing() {
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
        // A whole-body portrait reads as a rendering bug, so an unrecognizable model must report
        // nothing and let the caller fall back to its shaped category dot.
        assertEquals(Set.of(), HeadPartSelector.select("example:unknown", List.of("root")));
        assertEquals(Set.of(), HeadPartSelector.select(
            "example:unknown", List.of("root", "root/wing", "root/tail")
        ));
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

    @Test
    void dropsTheVillagerHatRimThatWouldShrinkTheFace() {
        assertFalse(HeadPartSelector.includesGeometry("minecraft:villager", "/hat/hat_rim"));
        assertFalse(HeadPartSelector.includesGeometry("minecraft:zombie_villager", "/hat/hat_rim"));
        assertTrue(HeadPartSelector.includesGeometry("minecraft:villager", "/hat"));
        assertTrue(HeadPartSelector.includesGeometry("minecraft:witch", "/hat/hat_rim"));
    }
}
